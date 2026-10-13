// Package server wires mnemos together: the auth endpoints, the notes API, the
// raw-file route used by the preview, the embedded front end and security headers.
package server

import (
	"bytes"
	"compress/gzip"
	"crypto/sha256"
	"encoding/hex"
	"html"
	"io/fs"
	"log"
	"mime"
	"net/http"
	"path"
	"strings"
	"sync"
	"time"

	"gitlab.com/niharokz/mnemos/internal/auth"
	"gitlab.com/niharokz/mnemos/internal/httpx"
	"gitlab.com/niharokz/mnemos/internal/search"
	"gitlab.com/niharokz/mnemos/internal/vault"
	"gitlab.com/niharokz/mnemos/web"
)

// Options are the settings of the app.
type Options struct {
	Version      string
	AutosaveMS   int
	CaptureNote  string // vault path quick capture appends to
	TemplatesDir string // vault folder listed by "New from template"
	MaxUploadMB  int    // largest image that can be added to a note
}

// Limits for the http.Server.
const (
	ReadHeaderTimeout = 10 * time.Second
	ReadTimeout       = 2 * time.Minute // room for a phone photo on a slow link
	WriteTimeout      = 60 * time.Second
	IdleTimeout       = 120 * time.Second
	MaxHeaderBytes    = 64 << 10
)

// New returns the HTTP handler. Each signed-in user brings their own vault
// (the owner's notes, or the demo vault), so every API call works on that one.
func New(a *auth.Auth, opt Options) http.Handler {
	mux := http.NewServeMux()
	rt := &httpx.Router{Mux: mux, Resolve: a.Resolve}
	api := &api{opt: opt, indexes: map[*vault.Vault]*search.Index{}}

	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("ok\n")) })
	a.Register(mux)

	rt.Handle("GET /api/config", api.config)
	rt.Handle("GET /api/tree", api.tree)
	rt.Handle("GET /api/file", api.read)
	rt.Handle("GET /api/version", api.version)
	rt.Handle("GET /api/folder", api.folder)
	rt.Handle("GET /api/search", api.search)
	rt.Handle("GET /api/templates", api.templates)
	rt.Handle("GET /api/trash", api.trashList)
	rt.Handle("GET /api/conflicts", api.conflicts)
	rt.Handle("PUT /api/file", api.write)
	rt.Handle("POST /api/new", api.create)
	rt.Handle("POST /api/move", api.move)
	rt.Handle("POST /api/trash", api.trash)
	rt.Handle("POST /api/restore", api.restore)
	rt.Handle("POST /api/capture", api.capture)
	rt.Handle("POST /api/attach", api.attach)
	rt.Handle("POST /api/conflicts/resolve", api.resolve)
	rt.HandleSafe("POST /api/render", api.render)
	rt.Handle("GET /raw/{path...}", api.raw)
	mux.HandleFunc("GET /api/", func(w http.ResponseWriter, r *http.Request) {
		httpx.Error(w, http.StatusNotFound, "no such API endpoint")
	})

	static, _ := fs.Sub(web.Files, "static")
	assets := newAssets(static, opt.Version)
	mux.HandleFunc("GET /{$}", assets.page("landing.html")) // public about page
	mux.HandleFunc("GET /app", assets.page("index.html"))   // the app (sign-in card when signed out)
	mux.HandleFunc("GET /app/", func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "/app", http.StatusMovedPermanently)
	})
	mux.HandleFunc("GET /robots.txt", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		w.Write([]byte("User-agent: *\nAllow: /$\nDisallow: /app\nDisallow: /api/\nDisallow: /raw/\n"))
	})
	mux.HandleFunc("GET /", assets.serve)
	return accessLog(headers(mux))
}

// accessLog records every request that changes something, and every refusal of
// one, with time, client IP, method, path and status — never bodies or content.
func accessLog(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodGet || r.Method == http.MethodHead {
			next.ServeHTTP(w, r)
			return
		}
		sw := &statusWriter{ResponseWriter: w, code: http.StatusOK}
		start := time.Now()
		next.ServeHTTP(sw, r)
		if r.URL.Path == "/api/render" && sw.code < 400 {
			return // the live preview: one per pause in typing, not worth a line
		}
		target := r.URL.Path
		if p := r.URL.Query().Get("path"); p != "" {
			target += " " + p
		}
		log.Printf("%s %s %s → %d (%s)", auth.ClientIP(r), r.Method, target, sw.code, time.Since(start).Round(time.Millisecond))
	})
}

type statusWriter struct {
	http.ResponseWriter
	code int
}

func (s *statusWriter) WriteHeader(code int) {
	s.code = code
	s.ResponseWriter.WriteHeader(code)
}

// ---- embedded front end -----------------------------------------------------

// assets serves the embedded files with an ETag and a gzip copy made once.
type assets struct {
	fsys    fs.FS
	version string // replaces {{VERSION}} in HTML
	mu      sync.Mutex
	cache   map[string]*asset
}

type asset struct {
	body, gz []byte
	etag, ct string
}

func newAssets(fsys fs.FS, version string) *assets {
	return &assets{fsys: fsys, version: version, cache: map[string]*asset{}}
}

func (s *assets) get(name string) (*asset, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if a, ok := s.cache[name]; ok {
		return a, nil
	}
	b, err := fs.ReadFile(s.fsys, name)
	if err != nil {
		return nil, err
	}
	if strings.HasSuffix(name, ".html") {
		b = bytes.ReplaceAll(b, []byte("{{VERSION}}"), []byte(html.EscapeString(s.version)))
	}
	sum := sha256.Sum256(b)
	a := &asset{body: b, etag: `"` + hex.EncodeToString(sum[:8]) + `"`, ct: mime.TypeByExtension(path.Ext(name))}
	switch path.Ext(name) {
	case ".webmanifest":
		a.ct = "application/manifest+json"
	case ".js":
		a.ct = "text/javascript; charset=utf-8"
	case ".ico": // not in Go's built-in table, and the scratch image has no mime.types
		a.ct = "image/x-icon"
	}
	if a.ct == "" {
		a.ct = "application/octet-stream"
	}
	if strings.HasPrefix(a.ct, "text/") || strings.Contains(a.ct, "json") || strings.Contains(a.ct, "svg") {
		var buf bytes.Buffer
		zw, _ := gzip.NewWriterLevel(&buf, gzip.BestCompression)
		zw.Write(b)
		zw.Close()
		a.gz = buf.Bytes()
	}
	s.cache[name] = a
	return a, nil
}

func (s *assets) write(w http.ResponseWriter, r *http.Request, name string) {
	a, err := s.get(name)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	h := w.Header()
	h.Set("Content-Type", a.ct)
	h.Set("ETag", a.etag)
	h.Set("Cache-Control", "no-cache") // revalidate (cheap 304) so a redeploy shows at once
	h.Add("Vary", "Accept-Encoding")
	if r.Header.Get("If-None-Match") == a.etag {
		w.WriteHeader(http.StatusNotModified)
		return
	}
	body := a.body
	if a.gz != nil && strings.Contains(r.Header.Get("Accept-Encoding"), "gzip") {
		h.Set("Content-Encoding", "gzip")
		body = a.gz
	}
	if r.Method == http.MethodHead {
		return
	}
	w.Write(body)
}

func (s *assets) page(name string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) { s.write(w, r, name) }
}

func (s *assets) serve(w http.ResponseWriter, r *http.Request) {
	name := strings.TrimPrefix(path.Clean(r.URL.Path), "/")
	if name == "" || strings.HasSuffix(name, ".html") {
		http.NotFound(w, r)
		return
	}
	s.write(w, r, name)
}

// headers adds security headers to every response.
func headers(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("Content-Security-Policy", "default-src 'self'; img-src 'self' data: https:; style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'; manifest-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("Referrer-Policy", "same-origin")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()")
		h.Set("Cross-Origin-Opener-Policy", "same-origin")
		h.Set("Cross-Origin-Resource-Policy", "same-origin")
		if auth.Secure(r) {
			h.Set("Strict-Transport-Security", "max-age=31536000")
		}
		next.ServeHTTP(w, r)
	})
}
