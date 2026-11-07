package server

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"gitlab.com/niharokz/mnemostxt/server/internal/auth"
	"gitlab.com/niharokz/mnemostxt/server/internal/config"
	"gitlab.com/niharokz/mnemostxt/server/internal/vault"
)

func setup(t *testing.T) (*httptest.Server, *http.Client) {
	t.Helper()
	cfg, err := config.Load(func(k string) string {
		return map[string]string{"MNEMOSTXT_USER": "me", "MNEMOSTXT_PASSWORD": "pw", "MNEMOSTXT_SECRET": "s", "MNEMOSTXT_BAN_AFTER": "3"}[k]
	})
	if err != nil {
		t.Fatal(err)
	}
	v, _ := vault.New(t.TempDir(), 1<<20, cfg.Hidden)
	a := auth.New(auth.Options{User: cfg.User, Password: cfg.Password, Secret: cfg.Secret, BanAfter: cfg.BanAfter, BanFor: time.Hour, StateDir: t.TempDir()})
	ts := httptest.NewServer(New(cfg, a, v, "test"))
	t.Cleanup(ts.Close)
	jar, _ := cookiejar.New(nil)
	return ts, &http.Client{Jar: jar}
}

func do(t *testing.T, c *http.Client, method, url, body string, header bool) (int, string, http.Header) {
	t.Helper()
	req, _ := http.NewRequest(method, url, strings.NewReader(body))
	if header {
		req.Header.Set("X-Mnemostxt", "1")
	}
	res, err := c.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(res.Body)
	res.Body.Close()
	return res.StatusCode, string(b), res.Header
}

func TestFlow(t *testing.T) {
	ts, c := setup(t)
	if code, _, _ := do(t, c, "GET", ts.URL+"/api/list?path=", "", false); code != 401 {
		t.Fatalf("list before sign-in: %d", code)
	}
	if code, body, _ := do(t, c, "GET", ts.URL+"/", "", false); code != 200 || !strings.Contains(body, "mnemostxt test") {
		t.Fatalf("landing: %d", code)
	}
	if code, _, _ := do(t, c, "POST", ts.URL+"/api/login", `{"user":"me","password":"pw"}`, false); code != 403 {
		t.Fatalf("login without header must be refused: %d", code)
	}
	if code, _, _ := do(t, c, "POST", ts.URL+"/api/login", `{"user":"me","password":"pw"}`, true); code != 200 {
		t.Fatalf("login: %d", code)
	}
	_, me, _ := do(t, c, "GET", ts.URL+"/api/me", "", false)
	if !strings.Contains(me, `"signed_in":true`) {
		t.Fatalf("me: %s", me)
	}
	if code, _, _ := do(t, c, "PUT", ts.URL+"/api/file?path=a.md", "hello", false); code != 403 {
		t.Fatalf("write without header: %d", code)
	}
	code, body, _ := do(t, c, "PUT", ts.URL+"/api/file?path=a.md", "hello", true)
	if code != 200 {
		t.Fatalf("write: %d %s", code, body)
	}
	var w struct{ Mtime int64 }
	_ = json.Unmarshal([]byte(body), &w)
	code, body, h := do(t, c, "GET", ts.URL+"/api/file?path=a.md", "", false)
	if code != 200 || body != "hello" || h.Get("X-Mtime") == "" {
		t.Fatalf("read: %d %q", code, body)
	}
	if code, body, _ := do(t, c, "PUT", ts.URL+"/api/file?path=a.md&expect=1", "x", true); code != 409 || !strings.Contains(body, "CONFLICT") {
		t.Fatalf("conflict: %d %s", code, body)
	}
	if code, _, _ := do(t, c, "GET", ts.URL+"/api/file?path=../etc/passwd", "", false); code == 200 {
		t.Fatal("escaped the notes folder")
	}
	_, list, _ := do(t, c, "GET", ts.URL+"/api/list?path=", "", false)
	if !strings.Contains(list, `"name":"a.md"`) {
		t.Fatalf("list: %s", list)
	}
	_, hits, _ := do(t, c, "GET", ts.URL+"/api/search?q=HELL", "", false)
	if !strings.Contains(hits, `"line":1`) {
		t.Fatalf("search: %s", hits)
	}
	if code, _, _ := do(t, c, "POST", ts.URL+"/api/trash?path=a.md", "", true); code != 200 {
		t.Fatalf("trash: %d", code)
	}
	_, st, _ := do(t, c, "GET", ts.URL+"/api/stat?path=a.md", "", false)
	if st != "{\"stat\":null}\n" {
		t.Fatalf("stat after trash: %q", st)
	}
	if code, _, _ := do(t, c, "GET", ts.URL+"/app/", "", false); code != 200 {
		t.Fatalf("app: %d", code)
	}
}

func TestBan(t *testing.T) {
	ts, c := setup(t)
	for i := 0; i < 3; i++ {
		do(t, c, "POST", ts.URL+"/api/login", `{"user":"me","password":"bad"}`, true)
	}
	// 127.0.0.1 is private: not banned for long, but locked for the window
	if code, _, _ := do(t, c, "POST", ts.URL+"/api/login", `{"user":"me","password":"pw"}`, true); code != 429 {
		t.Fatalf("locked out: %d", code)
	}
}
