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

	"gitlab.com/niharokz/mnemos/internal/auth"
	"gitlab.com/niharokz/mnemos/internal/httpx"
	"gitlab.com/niharokz/mnemos/internal/vault"
)

type env struct {
	t      *testing.T
	h      http.Handler
	dir    string
	demo   string
	cookie *http.Cookie
	demoC  *http.Cookie
}

func setup(t *testing.T) *env {
	t.Helper()
	dir := t.TempDir()
	v, err := vault.New(dir, ".trash", nil, 1<<20)
	if err != nil {
		t.Fatal(err)
	}
	ddir := t.TempDir()
	os.WriteFile(filepath.Join(ddir, "Welcome.md"), []byte("# demo"), 0o644)
	dv, _ := vault.New(ddir, ".trash", nil, 1<<20)
	owner := &auth.Account{User: httpx.User{Name: "me", Vault: v}, Password: "pw"}
	demo := &auth.Account{User: httpx.User{Name: "demouser", ReadOnly: true, Vault: dv}, Password: "demo", ShowHint: true}
	a := auth.New("secret", owner, demo)
	e := &env{t: t, h: New(a, Options{Version: "test", AutosaveMS: 1000, CaptureNote: "inbox/note.md", TemplatesDir: "templates", MaxUploadMB: 1}), dir: dir, demo: ddir}
	e.cookie = &http.Cookie{Name: auth.CookieName, Value: a.Token(owner, time.Now().Add(time.Hour))}
	e.demoC = &http.Cookie{Name: auth.CookieName, Value: a.Token(demo, time.Now().Add(time.Hour))}
	return e
}

func (e *env) do(method, url, body string, signed, header bool) (int, map[string]any, http.Header) {
	e.t.Helper()
	r := httptest.NewRequest(method, url, strings.NewReader(body))
	if signed {
		r.AddCookie(e.cookie)
	}
	if header {
		r.Header.Set("X-Mnemos", "1")
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
	if w.Code != 200 || !strings.Contains(body, `href="/app"`) || !strings.Contains(body, "Mnemos test") || !strings.Contains(body, "mnemos.apk") || strings.Contains(body, "{{VERSION}}") {
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
	r.Header.Set("X-Mnemos", "1")
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

// doAs runs a request with a given cookie (the demo user's, say).
func (e *env) doAs(c *http.Cookie, method, url, body string) (int, map[string]any) {
	e.t.Helper()
	r := httptest.NewRequest(method, url, strings.NewReader(body))
	r.AddCookie(c)
	r.Header.Set("X-Mnemos", "1")
	w := httptest.NewRecorder()
	e.h.ServeHTTP(w, r)
	var out map[string]any
	_ = json.Unmarshal(w.Body.Bytes(), &out)
	return w.Code, out
}

func TestDemoIsReadOnlyAndSeparate(t *testing.T) {
	e := setup(t)
	os.WriteFile(filepath.Join(e.dir, "secret.md"), []byte("owner only"), 0o644)
	if code, _ := e.doAs(e.demoC, "GET", "/api/file?path=secret.md", ""); code != http.StatusNotFound {
		t.Fatalf("demo reads owner vault: %d", code)
	}
	if code, out := e.doAs(e.demoC, "GET", "/api/file?path=Welcome.md", ""); code != 200 || out["content"] != "# demo" {
		t.Fatalf("demo read: %d %v", code, out)
	}
	for _, c := range [][3]string{{"PUT", "/api/file?path=Welcome.md", `{"content":"x","force":true}`}, {"POST", "/api/new", `{"path":"x"}`},
		{"POST", "/api/trash", `{"path":"Welcome.md"}`}, {"POST", "/api/capture", `{"text":"hi"}`}} {
		if code, out := e.doAs(e.demoC, c[0], c[1], c[2]); code != http.StatusForbidden || out["read_only"] != true {
			t.Errorf("demo %s %s: %d %v", c[0], c[1], code, out)
		}
	}
	if code, out := e.doAs(e.demoC, "POST", "/api/render", `{"path":"a.md","content":"# hi"}`); code != 200 || !strings.Contains(out["html"].(string), "<h1") {
		t.Fatalf("demo preview must work: %d %v", code, out)
	}
	if b, _ := os.ReadFile(filepath.Join(e.demo, "Welcome.md")); string(b) != "# demo" {
		t.Fatal("demo vault changed")
	}
	_, me, _ := e.do("GET", "/api/me", "", false, false)
	if d, ok := me["demo"].(map[string]any); !ok || d["user"] != "demouser" {
		t.Fatalf("demo hint missing: %v", me)
	}
}

func TestSearchCaptureTemplates(t *testing.T) {
	e := setup(t)
	os.MkdirAll(filepath.Join(e.dir, "notes"), 0o755)
	os.WriteFile(filepath.Join(e.dir, "notes", "garden.md"), []byte("---\nstatus: open\ntags: [plants]\n---\n# Garden\nWater the tomatoes. #garden\n"), 0o644)
	os.WriteFile(filepath.Join(e.dir, "notes", "run.md"), []byte("10K race in november"), 0o644)
	cases := map[string]string{"tomatoes": "notes/garden.md", "#garden": "notes/garden.md", "#plants": "notes/garden.md",
		"status: open": "notes/garden.md", "status:open": "notes/garden.md", "run": "notes/run.md", "november": "notes/run.md"}
	for q, want := range cases {
		code, out := e.doAs(e.cookie, "GET", "/api/search?q="+strings.ReplaceAll(strings.ReplaceAll(q, " ", "+"), "#", "%23"), "")
		hits, _ := out["hits"].([]any)
		if code != 200 || len(hits) == 0 || hits[0].(map[string]any)["path"] != want {
			t.Errorf("search %q: %d %v", q, code, out)
		}
	}
	if code, out := e.doAs(e.cookie, "POST", "/api/capture", `{"text":"buy milk\nand eggs"}`); code != 200 || out["path"] != "inbox/note.md" {
		t.Fatalf("capture: %d %v", code, out)
	}
	b, _ := os.ReadFile(filepath.Join(e.dir, "inbox", "note.md"))
	if !strings.HasPrefix(string(b), "- ") || !strings.HasSuffix(string(b), " buy milk\n  and eggs\n") {
		t.Fatalf("capture text: %q", b)
	}
	os.MkdirAll(filepath.Join(e.dir, "templates"), 0o755)
	os.WriteFile(filepath.Join(e.dir, "templates", "Daily.md"), []byte("# {{title}}\n{{date}}"), 0o644)
	if _, out := e.doAs(e.cookie, "GET", "/api/templates", ""); len(out["templates"].([]any)) != 1 {
		t.Fatalf("templates: %v", out)
	}
	if code, out := e.doAs(e.cookie, "POST", "/api/new", `{"path":"journal/today","template":"templates/Daily.md"}`); code != 200 || out["path"] != "journal/today.md" {
		t.Fatalf("new from template: %d %v", code, out)
	}
	b, _ = os.ReadFile(filepath.Join(e.dir, "journal", "today.md"))
	if !strings.HasPrefix(string(b), "# today\n20") {
		t.Fatalf("template fill: %q", b)
	}
}

func TestConflictsAndRestore(t *testing.T) {
	e := setup(t)
	os.WriteFile(filepath.Join(e.dir, "todo.md"), []byte("mine"), 0o644)
	cp := "todo.sync-conflict-20261012-093000-ABCDEFG.md"
	os.WriteFile(filepath.Join(e.dir, cp), []byte("theirs"), 0o644)
	r := httptest.NewRequest("GET", "/api/conflicts", nil)
	r.AddCookie(e.cookie)
	w := httptest.NewRecorder()
	e.h.ServeHTTP(w, r)
	var list []map[string]any
	json.Unmarshal(w.Body.Bytes(), &list)
	if len(list) != 1 || list[0]["original"] != "todo.md" || list[0]["when"] != "2026-10-12 09:30:00" {
		t.Fatalf("conflicts: %s", w.Body)
	}
	if code, out := e.doAs(e.cookie, "POST", "/api/conflicts/resolve", `{"copy":"`+cp+`","keep":"copy"}`); code != 200 {
		t.Fatalf("resolve: %d %v", code, out)
	}
	if b, _ := os.ReadFile(filepath.Join(e.dir, "todo.md")); string(b) != "theirs" {
		t.Fatalf("kept copy: %q", b)
	}
	// the losing original is in the trash and can be restored
	r = httptest.NewRequest("GET", "/api/trash", nil)
	r.AddCookie(e.cookie)
	w = httptest.NewRecorder()
	e.h.ServeHTTP(w, r)
	var items []map[string]any
	json.Unmarshal(w.Body.Bytes(), &items)
	if len(items) != 1 || items[0]["original"] != "todo.md" {
		t.Fatalf("trash list: %s", w.Body)
	}
	if code, _ := e.doAs(e.cookie, "POST", "/api/restore", `{"name":"todo.md","to":"todo.md"}`); code != http.StatusConflict {
		t.Fatalf("restore over existing must fail: %d", code)
	}
	if code, out := e.doAs(e.cookie, "POST", "/api/restore", `{"name":"todo.md","to":"old/todo.md"}`); code != 200 || out["path"] != "old/todo.md" {
		t.Fatalf("restore: %d %v", code, out)
	}
	if b, _ := os.ReadFile(filepath.Join(e.dir, "old", "todo.md")); string(b) != "mine" {
		t.Fatalf("restored content: %q", b)
	}
	if code, _ := e.doAs(e.cookie, "POST", "/api/restore", `{"name":"../etc/passwd","to":"x.md"}`); code != http.StatusBadRequest {
		t.Fatalf("restore path escape: %d", code)
	}
}

func TestFolderCards(t *testing.T) {
	e := setup(t)
	os.MkdirAll(filepath.Join(e.dir, "n", "sub"), 0o755)
	os.WriteFile(filepath.Join(e.dir, "n", "a.md"), []byte("---\ntitle: x\n---\nHello preview"), 0o644)
	r := httptest.NewRequest("GET", "/api/folder?path=n", nil)
	r.AddCookie(e.cookie)
	w := httptest.NewRecorder()
	e.h.ServeHTTP(w, r)
	var cards []map[string]any
	json.Unmarshal(w.Body.Bytes(), &cards)
	if len(cards) != 2 || cards[0]["name"] != "sub" || cards[1]["preview"] != "Hello preview" {
		t.Fatalf("folder: %s", w.Body)
	}
}

// png is the smallest valid PNG header the sniffer accepts.
var png = "\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x06\x00\x00\x00"

func TestAttachImages(t *testing.T) {
	e := setup(t)
	os.MkdirAll(filepath.Join(e.dir, "journal"), 0o755)

	code, out, _ := e.do("POST", "/api/attach?note=journal/day.md&name=My%20photo.JPEG", png, true, true)
	if code != 200 || out["path"] != "journal/attachments/My-photo.png" || out["markdown"] != "![My-photo](attachments/My-photo.png)" {
		t.Fatalf("attach: %d %v", code, out)
	}
	if b, _ := os.ReadFile(filepath.Join(e.dir, "journal", "attachments", "My-photo.png")); string(b) != png {
		t.Fatal("image bytes not saved as sent")
	}
	// never overwrites; spaces in the new name are escaped in the link
	code, out, _ = e.do("POST", "/api/attach?note=journal/day.md&name=My%20photo.png", png, true, true)
	if code != 200 || out["markdown"] != "![My-photo 2](attachments/My-photo%202.png)" {
		t.Fatalf("second attach: %d %v", code, out)
	}
	if code, _, _ := e.do("POST", "/api/attach?note=a.md&name=x.png", "<svg onload=alert(1)>", true, true); code != http.StatusUnsupportedMediaType {
		t.Errorf("not an image: %d", code)
	}
	if code, _, _ := e.do("POST", "/api/attach?note=a.md", png+strings.Repeat("x", 1<<20), true, true); code != http.StatusRequestEntityTooLarge {
		t.Errorf("too large: %d", code)
	}
	if code, _, _ := e.do("POST", "/api/attach?note=.obsidian/x.md", png, true, true); code == 200 {
		t.Error("attached into a hidden folder")
	}
	if code, _, _ := e.do("POST", "/api/attach?note=a.md", png, true, false); code != http.StatusForbidden {
		t.Errorf("without X-Mnemos: %d", code)
	}
	if code, out := e.doAs(e.demoC, "POST", "/api/attach?note=Welcome.md", png); code != http.StatusForbidden || out["read_only"] != true {
		t.Errorf("demo attach: %d %v", code, out)
	}

	// the preview shows both markdown images and Obsidian embeds
	body := `{"path":"journal/day.md","content":"![[My-photo.png]] ![x](attachments/My-photo%202.png)"}`
	code, out, _ = e.do("POST", "/api/render", body, true, true)
	html, _ := out["html"].(string)
	if code != 200 || !strings.Contains(html, `src="/raw/journal/attachments/My-photo.png"`) || !strings.Contains(html, `src="/raw/journal/attachments/My-photo%202.png"`) {
		t.Fatalf("render: %d %s", code, html)
	}
}

func TestFavicon(t *testing.T) {
	e := setup(t)
	code, _, hdr := e.do("GET", "/favicon.ico", "", false, false)
	if code != 200 || hdr.Get("Content-Type") != "image/x-icon" {
		t.Fatalf("favicon: %d %q", code, hdr.Get("Content-Type"))
	}
}
