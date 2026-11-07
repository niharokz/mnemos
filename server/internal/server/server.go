// Package server wires the HTTP routes:
//
//	/            the home page (download links, sign in)
//	/app/        the editor (frontend/dist, built into the binary)
//	/api/...     sign-in and the notes folder API (signed-in only)
//	/health      liveness for the Docker health check
package server

import (
	"html/template"
	"io"
	"io/fs"
	"log"
	"net/http"
	"strconv"
	"strings"
	"time"

	"gitlab.com/niharokz/mnemostxt/server/internal/auth"
	"gitlab.com/niharokz/mnemostxt/server/internal/config"
	"gitlab.com/niharokz/mnemostxt/server/internal/httpx"
	"gitlab.com/niharokz/mnemostxt/server/internal/vault"
	"gitlab.com/niharokz/mnemostxt/server/web"
)

type Server struct {
	cfg     *config.Config
	auth    *auth.Auth
	vault   *vault.Vault
	version string
	mux     *http.ServeMux
}

func New(cfg *config.Config, a *auth.Auth, v *vault.Vault, version string) *Server {
	s := &Server{cfg: cfg, auth: a, vault: v, version: version, mux: http.NewServeMux()}
	s.routes()
	return s
}

func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	h := w.Header()
	h.Set("X-Content-Type-Options", "nosniff")
	h.Set("Referrer-Policy", "same-origin")
	h.Set("X-Frame-Options", "DENY")
	h.Set("Content-Security-Policy", "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data:; font-src 'self' data:; worker-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'")
	s.mux.ServeHTTP(w, r)
}

func (s *Server) routes() {
	s.auth.Register(s.mux)
	s.mux.HandleFunc("GET /health", func(w http.ResponseWriter, _ *http.Request) { _, _ = io.WriteString(w, "ok\n") })
	s.mux.HandleFunc("GET /{$}", s.landing)
	s.mux.HandleFunc("GET /icon.svg", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "public, max-age=86400")
		http.ServeFileFS(w, r, web.Landing, "icon.svg")
	})
	s.mux.HandleFunc("GET /app", func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "/app/", http.StatusMovedPermanently)
	})
	dist, _ := fs.Sub(web.Dist, "dist")
	files := http.StripPrefix("/app/", http.FileServer(http.FS(dist)))
	s.mux.HandleFunc("GET /app/", func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/app/assets/") {
			w.Header().Set("Cache-Control", "public, max-age=31536000, immutable") // hashed names
		} else {
			w.Header().Set("Cache-Control", "no-cache")
		}
		files.ServeHTTP(w, r)
	})

	rt := &httpx.Router{Mux: s.mux, Signed: s.auth.Signed}
	q := func(r *http.Request) string { return r.URL.Query().Get("path") }
	rt.Handle("GET /api/info", func(w http.ResponseWriter, _ *http.Request) error {
		httpx.JSON(w, http.StatusOK, map[string]string{"version": s.version, "notes_name": s.cfg.NotesName})
		return nil
	})
	rt.Handle("GET /api/list", func(w http.ResponseWriter, r *http.Request) error {
		l, err := s.vault.List(q(r))
		if err != nil {
			return err
		}
		httpx.JSON(w, http.StatusOK, l)
		return nil
	})
	rt.Handle("GET /api/stat", func(w http.ResponseWriter, r *http.Request) error {
		st, err := s.vault.Stat(q(r))
		if err != nil {
			return err
		}
		httpx.JSON(w, http.StatusOK, map[string]any{"stat": st})
		return nil
	})
	rt.Handle("GET /api/file", func(w http.ResponseWriter, r *http.Request) error {
		b, mtime, err := s.vault.Read(q(r))
		if err != nil {
			return err
		}
		w.Header().Set("Content-Type", "application/octet-stream")
		w.Header().Set("Content-Length", strconv.Itoa(len(b)))
		w.Header().Set("X-Mtime", strconv.FormatInt(mtime, 10))
		_, err = w.Write(b)
		return err
	})
	rt.Handle("PUT /api/file", func(w http.ResponseWriter, r *http.Request) error {
		expect := int64(-1)
		if e := r.URL.Query().Get("expect"); e != "" {
			n, err := strconv.ParseInt(e, 10, 64)
			if err != nil {
				return httpx.Errorf(http.StatusBadRequest, "INVALID", "bad expect")
			}
			expect = n
		}
		body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, s.vault.MaxSize+1))
		if err != nil {
			return httpx.Errorf(http.StatusRequestEntityTooLarge, "TOO_LARGE", "file too large")
		}
		m, err := s.vault.Write(q(r), body, expect)
		if err != nil {
			return err
		}
		httpx.JSON(w, http.StatusOK, map[string]int64{"mtime": m})
		return nil
	})
	rt.Handle("POST /api/mkdir", func(w http.ResponseWriter, r *http.Request) error {
		if err := s.vault.MkdirAll(q(r)); err != nil {
			return err
		}
		httpx.JSON(w, http.StatusOK, map[string]bool{"ok": true})
		return nil
	})
	rt.Handle("POST /api/create", func(w http.ResponseWriter, r *http.Request) error {
		m, err := s.vault.Create(q(r))
		if err != nil {
			return err
		}
		httpx.JSON(w, http.StatusOK, map[string]int64{"mtime": m})
		return nil
	})
	rt.Handle("POST /api/rename", func(w http.ResponseWriter, r *http.Request) error {
		var in struct{ From, To string }
		if err := httpx.Decode(w, r, 16<<10, &in); err != nil {
			return err
		}
		if err := s.vault.Rename(in.From, in.To); err != nil {
			return err
		}
		httpx.JSON(w, http.StatusOK, map[string]bool{"ok": true})
		return nil
	})
	rt.Handle("POST /api/trash", func(w http.ResponseWriter, r *http.Request) error {
		if err := s.vault.Trash(q(r)); err != nil {
			return err
		}
		httpx.JSON(w, http.StatusOK, map[string]bool{"ok": true})
		return nil
	})
	rt.Handle("POST /api/trash/purge", func(w http.ResponseWriter, r *http.Request) error {
		days, err := strconv.Atoi(r.URL.Query().Get("days"))
		if err != nil || days < 1 || days > 3650 {
			return httpx.Errorf(http.StatusBadRequest, "INVALID", "days must be 1 to 3650")
		}
		res, err := s.vault.Purge(days, time.Now())
		if err != nil {
			return err
		}
		log.Printf("trash: removed %d items (%d bytes) older than %d days", res.Removed, res.Bytes, days)
		httpx.JSON(w, http.StatusOK, res)
		return nil
	})
	rt.Handle("GET /api/search", func(w http.ResponseWriter, r *http.Request) error {
		qs := r.URL.Query()
		max, _ := strconv.Atoi(qs.Get("max"))
		if max < 1 || max > 10000 {
			max = 2000
		}
		hits, err := s.vault.Search(qs.Get("q"), qs.Get("regex") == "1", qs.Get("case") == "1", max)
		if err != nil {
			return err
		}
		httpx.JSON(w, http.StatusOK, hits)
		return nil
	})
}

var landingTmpl = template.Must(template.ParseFS(web.Landing, "landing.html"))

func (s *Server) landing(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	_ = landingTmpl.Execute(w, map[string]any{
		"Version": s.version, "Signed": s.auth.Signed(r),
		"APK": s.cfg.DownloadAPK, "EXE": s.cfg.DownloadEXE, "DEB": s.cfg.DownloadDEB, "Source": s.cfg.Source,
	})
}
