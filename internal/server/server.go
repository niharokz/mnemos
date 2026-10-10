// Package server wires samanote together: the auth endpoints, the notes API, the
// raw-file route used by the preview, the embedded front end and security headers.
package server

import (
	"bytes"
	"compress/gzip"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"html"
	"io/fs"
	"log"
	"mime"
	"net/http"
	"os"
	"path"
	"strings"
	"sync"
	"time"

	"gitlab.com/niharokz/samanote/internal/auth"
	"gitlab.com/niharokz/samanote/internal/httpx"
	"gitlab.com/niharokz/samanote/internal/render"
	"gitlab.com/niharokz/samanote/internal/vault"
	"gitlab.com/niharokz/samanote/web"
)

// Options are the settings the browser needs.
type Options struct {
	Version    string
	AutosaveMS int
}

// Limits for the http.Server.
const (
	ReadHeaderTimeout = 10 * time.Second
	ReadTimeout       = 30 * time.Second
	WriteTimeout      = 60 * time.Second
	IdleTimeout       = 120 * time.Second
	MaxHeaderBytes    = 64 << 10
)

// New returns the HTTP handler.
func New(a *auth.Auth, v *vault.Vault, opt Options) http.Handler {
	mux := http.NewServeMux()
	rt := &httpx.Router{Mux: mux, SignedIn: a.SignedIn}
	api := &api{v: v, opt: opt}

	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("ok\n")) })
	a.Register(mux)

	rt.Handle("GET /api/config", api.config)
	rt.Handle("GET /api/tree", api.tree)
	rt.Handle("GET /api/file", api.read)
	rt.Handle("GET /api/version", api.version)
	rt.Handle("PUT /api/file", api.write)
	rt.Handle("POST /api/new", api.create)
	rt.Handle("POST /api/move", api.move)
	rt.Handle("POST /api/trash", api.trash)
	rt.Handle("POST /api/render", api.render)
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

// ---- notes API ---------------------------------------------------------------

type api struct {
	v   *vault.Vault
	opt Options
}

func (a *api) config(w http.ResponseWriter, r *http.Request) error {
	httpx.JSON(w, http.StatusOK, map[string]any{
		"version": a.opt.Version, "autosave_ms": a.opt.AutosaveMS, "max_bytes": a.v.MaxBytes(),
	})
	return nil
}

func (a *api) tree(w http.ResponseWriter, r *http.Request) error {
	t, err := a.v.Tree()
	if err != nil {
		return err
	}
	httpx.JSON(w, http.StatusOK, t)
	return nil
}

func (a *api) read(w http.ResponseWriter, r *http.Request) error {
	f, err := a.v.Read(r.URL.Query().Get("path"))
	if err != nil {
		return vaultErr(err)
	}
	httpx.JSON(w, http.StatusOK, f)
	return nil
}

// version is the cheap "did it change?" check the browser runs on focus.
func (a *api) version(w http.ResponseWriter, r *http.Request) error {
	ver, err := a.v.Stat(r.URL.Query().Get("path"))
	if err != nil {
		return vaultErr(err)
	}
	httpx.JSON(w, http.StatusOK, map[string]string{"version": ver})
	return nil
}

func (a *api) write(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		Content string `json:"content"`
		Version string `json:"version"`
		Force   bool   `json:"force"`
	}
	if err := httpx.Decode(w, r, a.v.MaxBytes()*2+64<<10, &in); err != nil {
		return err
	}
	ver, err := a.v.Write(r.URL.Query().Get("path"), in.Content, in.Version, in.Force)
	if err != nil {
		return vaultErr(err)
	}
	httpx.JSON(w, http.StatusOK, map[string]string{"version": ver})
	return nil
}

func (a *api) create(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		Path   string `json:"path"`
		Folder bool   `json:"folder"`
	}
	if err := httpx.Decode(w, r, 16<<10, &in); err != nil {
		return err
	}
	p, err := a.v.Create(in.Path, in.Folder)
	if err != nil {
		return vaultErr(err)
	}
	httpx.JSON(w, http.StatusOK, map[string]string{"path": p})
	return nil
}

func (a *api) move(w http.ResponseWriter, r *http.Request) error {
	var in struct{ From, To string }
	if err := httpx.Decode(w, r, 16<<10, &in); err != nil {
		return err
	}
	p, err := a.v.Move(in.From, in.To)
	if err != nil {
		return vaultErr(err)
	}
	httpx.JSON(w, http.StatusOK, map[string]string{"path": p})
	return nil
}

func (a *api) trash(w http.ResponseWriter, r *http.Request) error {
	var in struct{ Path string }
	if err := httpx.Decode(w, r, 16<<10, &in); err != nil {
		return err
	}
	p, err := a.v.Trash(in.Path)
	if err != nil {
		return vaultErr(err)
	}
	httpx.JSON(w, http.StatusOK, map[string]string{"trash": p})
	return nil
}

func (a *api) render(w http.ResponseWriter, r *http.Request) error {
	var in struct{ Path, Content string }
	if err := httpx.Decode(w, r, a.v.MaxBytes()*2+64<<10, &in); err != nil {
		return err
	}
	out, err := render.Render(in.Path, in.Content)
	if err != nil {
		return err
	}
	httpx.JSON(w, http.StatusOK, map[string]string{"html": out})
	return nil
}

// raw serves a vault file as-is for the preview (images) and "open raw". Anything
// that a browser could run (HTML, SVG, JS) is sandboxed by its own CSP, and HTML
// and scripts are sent as plain text.
func (a *api) raw(w http.ResponseWriter, r *http.Request) error {
	abs, err := a.v.RawPath(r.PathValue("path"))
	if err != nil {
		return vaultErr(err)
	}
	f, err := os.Open(abs)
	if err != nil {
		return vaultErr(vault.ErrNotFound)
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return err
	}
	ext := strings.ToLower(path.Ext(abs))
	ct := mime.TypeByExtension(ext)
	switch {
	case ext == ".html" || ext == ".htm" || ext == ".js" || ext == ".mjs" || ext == ".xhtml" || ext == ".xml":
		ct = "text/plain; charset=utf-8"
	case ct == "" || strings.HasPrefix(ct, "text/"):
		ct = "text/plain; charset=utf-8"
	}
	h := w.Header()
	h.Set("Content-Type", ct)
	h.Set("Content-Security-Policy", "sandbox; default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'; media-src 'self'")
	h.Set("Cache-Control", "private, no-cache")
	http.ServeContent(w, r, info.Name(), info.ModTime(), f)
	return nil
}

// vaultErr maps vault errors to HTTP statuses and clear messages.
func vaultErr(err error) error {
	var ce *vault.ConflictError
	switch {
	case errors.As(err, &ce):
		return &httpx.StatusError{Code: http.StatusConflict, Msg: ce.Error(), Body: map[string]any{
			"conflict": true, "current": ce.Current, "version": ce.Version, "deleted": ce.Deleted,
		}}
	}
	var pe *vault.PermissionError
	switch {
	case errors.As(err, &pe):
		return httpx.Errorf(http.StatusForbidden, "%s", pe.Error())
	case errors.Is(err, vault.ErrNotFound):
		return httpx.Errorf(http.StatusNotFound, "%s", "not found — it may have been moved or deleted")
	case errors.Is(err, vault.ErrExists):
		return httpx.Errorf(http.StatusConflict, "%s", err.Error())
	case errors.Is(err, vault.ErrHidden):
		return httpx.Errorf(http.StatusForbidden, "%s", err.Error())
	case errors.Is(err, vault.ErrTooLarge):
		return httpx.Errorf(http.StatusRequestEntityTooLarge, "%s", err.Error())
	case errors.Is(err, vault.ErrBadPath), errors.Is(err, vault.ErrIsDir):
		return httpx.Errorf(http.StatusBadRequest, "%s", err.Error())
	}
	return err
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
