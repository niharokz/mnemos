package server

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"gitlab.com/niharokz/samanote/internal/auth"
	"gitlab.com/niharokz/samanote/internal/vault"
)

type env struct {
	t      *testing.T
	h      http.Handler
	dir    string
	cookie *http.Cookie
}

func setup(t *testing.T) *env {
	t.Helper()
	dir := t.TempDir()
	v, err := vault.New(dir, ".trash", nil, 1<<20)
	if err != nil {
		t.Fatal(err)
	}
	a := auth.New("me", "pw", "secret")
	e := &env{t: t, h: New(a, v, Options{Version: "test", AutosaveMS: 1000}), dir: dir}
	e.cookie = &http.Cookie{Name: auth.CookieName, Value: a.Token(time.Now().Add(time.Hour))}
	return e
}

func (e *env) do(method, url, body string, signed, header bool) (int, map[string]any, http.Header) {
	e.t.Helper()
	r := httptest.NewRequest(method, url, strings.NewReader(body))
	if signed {
		r.AddCookie(e.cookie)
	}
	if header {
		r.Header.Set("X-Samanote", "1")
	}
	w := httptest.NewRecorder()
	e.h.ServeHTTP(w, r)
	var out map[string]any
	b, _ := io.ReadAll(w.Body)
	_ = json.Unmarshal(b, &out)
	return w.Code, out, w.Header()
}

func TestAuthRequired(t *testing.T) {
	e := setup(t)
	for _, u := range []string{"/api/tree", "/api/file?path=a.md", "/raw/a.png"} {
		if code, _, _ := e.do("GET", u, "", false, false); code != http.StatusUnauthorized {
			t.Errorf("%s without session: %d", u, code)
		}
	}
	// the old app's forgeable cookie must not work
	r := httptest.NewRequest("GET", "/api/tree", nil)
	r.AddCookie(&http.Cookie{Name: auth.CookieName, Value: "1"})
	w := httptest.NewRecorder()
	e.h.ServeHTTP(w, r)
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("cookie=1: %d", w.Code)
	}
}

func TestChangesNeedHeader(t *testing.T) {
	e := setup(t)
	for _, c := range [][2]string{{"PUT", "/api/file?path=a.md"}, {"POST", "/api/new"}, {"POST", "/api/move"}, {"POST", "/api/trash"}} {
		if code, _, _ := e.do(c[0], c[1], `{}`, true, false); code != http.StatusForbidden {
			t.Errorf("%s %s without header: %d", c[0], c[1], code)
		}
	}
}

func TestEditFlowWithConflict(t *testing.T) {
	e := setup(t)
	code, out, _ := e.do("POST", "/api/new", `{"path":"ideas/first"}`, true, true)
	if code != 200 || out["path"] != "ideas/first.md" {
		t.Fatalf("new: %d %v", code, out)
	}
	code, out, _ = e.do("GET", "/api/file?path=ideas/first.md", "", true, false)
	if code != 200 {
		t.Fatalf("read: %d %v", code, out)
	}
	ver := out["version"].(string)
	code, out, _ = e.do("PUT", "/api/file?path=ideas/first.md", `{"content":"# hi","version":"`+ver+`"}`, true, true)
	if code != 200 {
		t.Fatalf("save: %d %v", code, out)
	}
	ver = out["version"].(string)
	os.WriteFile(filepath.Join(e.dir, "ideas", "first.md"), []byte("edited in obsidian"), 0o644)
	code, out, _ = e.do("PUT", "/api/file?path=ideas/first.md", `{"content":"# mine","version":"`+ver+`"}`, true, true)
	if code != http.StatusConflict || out["conflict"] != true || out["current"] != "edited in obsidian" {
		t.Fatalf("conflict: %d %v", code, out)
	}
	code, out, _ = e.do("GET", "/api/version?path=ideas/first.md", "", true, false)
	if code != 200 || out["version"] == ver {
		t.Fatalf("version check: %d %v", code, out)
	}
	code, out, _ = e.do("POST", "/api/trash", `{"path":"ideas/first.md"}`, true, true)
	if code != 200 || out["trash"] != ".trash/first.md" {
		t.Fatalf("trash: %d %v", code, out)
	}
	if code, _, _ := e.do("GET", "/api/file?path=.trash/first.md", "", true, false); code != http.StatusForbidden {
		t.Fatalf("trash is hidden: %d", code)
	}
}

func TestRawIsSandboxedAndConfined(t *testing.T) {
	e := setup(t)
	os.WriteFile(filepath.Join(e.dir, "page.html"), []byte("<script>alert(1)</script>"), 0o644)
	os.WriteFile(filepath.Join(e.dir, "pic.png"), []byte("\x89PNG"), 0o644)
	code, _, h := e.do("GET", "/raw/page.html", "", true, false)
	if code != 200 || !strings.HasPrefix(h.Get("Content-Type"), "text/plain") || !strings.Contains(h.Get("Content-Security-Policy"), "sandbox") {
		t.Fatalf("raw html: %d %v", code, h)
	}
	if code, _, h := e.do("GET", "/raw/pic.png", "", true, false); code != 200 || h.Get("Content-Type") != "image/png" {
		t.Fatalf("raw png: %d %v", code, h)
	}
	for _, u := range []string{"/raw/../../etc/passwd", "/raw/%2e%2e/%2e%2e/etc/passwd", "/raw/.obsidian/app.json"} {
		if code, _, _ := e.do("GET", u, "", true, false); code == 200 {
			t.Errorf("%s served", u)
		}
	}
}

func TestRenderAndStatic(t *testing.T) {
	e := setup(t)
	code, out, _ := e.do("POST", "/api/render", `{"path":"a/b.md","content":"![i](x.png)"}`, true, true)
	if code != 200 || !strings.Contains(out["html"].(string), `/raw/a/x.png`) {
		t.Fatalf("render: %d %v", code, out)
	}
	r := httptest.NewRequest("GET", "/vendor/codemirror.js", nil)
	r.Header.Set("Accept-Encoding", "gzip")
	w := httptest.NewRecorder()
	e.h.ServeHTTP(w, r)
	if w.Code != 200 || w.Header().Get("Content-Encoding") != "gzip" || w.Header().Get("ETag") == "" {
		t.Fatalf("static: %d %v", w.Code, w.Header())
	}
	r = httptest.NewRequest("GET", "/vendor/codemirror.js", nil)
	r.Header.Set("If-None-Match", w.Header().Get("ETag"))
	w = httptest.NewRecorder()
	e.h.ServeHTTP(w, r)
	if w.Code != http.StatusNotModified {
		t.Fatalf("etag: %d", w.Code)
	}
	if code, _, h := e.do("GET", "/", "", false, false); code != 200 || !strings.Contains(h.Get("Content-Security-Policy"), "script-src 'self'") {
		t.Fatalf("landing: %d", code)
	}
	if code, _, _ := e.do("GET", "/app", "", false, false); code != 200 {
		t.Fatalf("app: %d", code)
	}
	if code, _, _ := e.do("GET", "/index.html", "", false, false); code != 404 {
		t.Fatalf("html pages only at their routes: %d", code)
	}
}

func TestLandingPage(t *testing.T) {
	e := setup(t)
	r := httptest.NewRequest("GET", "/", nil)
	w := httptest.NewRecorder()
	e.h.ServeHTTP(w, r)
	body := w.Body.String()
	if w.Code != 200 || !strings.Contains(body, `href="/app"`) || !strings.Contains(body, "samanote test") || strings.Contains(body, "{{VERSION}}") {
		t.Fatalf("landing page: %d\n%.300s", w.Code, body)
	}
	r = httptest.NewRequest("GET", "/", nil)
	r.Header.Set("X-Forwarded-Proto", "https")
	w = httptest.NewRecorder()
	e.h.ServeHTTP(w, r)
	if w.Header().Get("Strict-Transport-Security") == "" || w.Header().Get("Cross-Origin-Opener-Policy") != "same-origin" {
		t.Fatalf("https headers: %v", w.Header())
	}
}

func TestCrossSiteChangeRefused(t *testing.T) {
	e := setup(t)
	os.WriteFile(filepath.Join(e.dir, "a.md"), []byte("x"), 0o644)
	r := httptest.NewRequest("POST", "/api/trash", strings.NewReader(`{"path":"a.md"}`))
	r.AddCookie(e.cookie)
	r.Header.Set("X-Samanote", "1")
	r.Header.Set("Sec-Fetch-Site", "cross-site")
	w := httptest.NewRecorder()
	e.h.ServeHTTP(w, r)
	if w.Code != http.StatusForbidden {
		t.Fatalf("cross-site trash: %d", w.Code)
	}
	if _, err := os.Stat(filepath.Join(e.dir, "a.md")); err != nil {
		t.Fatal("file was trashed")
	}
}

func TestInternalErrorsHidePaths(t *testing.T) {
	e := setup(t)
	os.WriteFile(filepath.Join(e.dir, "a.md"), []byte("x"), 0o644)
	// make the vault vanish so the tree walk fails with an OS error
	os.RemoveAll(e.dir)
	code, out, _ := e.do("GET", "/api/tree", "", true, false)
	if code != 500 || strings.Contains(out["error"].(string), e.dir) {
		t.Fatalf("500 leaks path: %d %v", code, out)
	}
}
