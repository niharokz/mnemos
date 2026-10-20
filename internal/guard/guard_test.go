package guard

import (
	"io/fs"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"gitlab.com/niharokz/mnemos/web"
)

type clock struct{ t time.Time }

func (c *clock) now() time.Time { return c.t }

func TestStrikesBanAndExpire(t *testing.T) {
	c := &clock{time.Date(2026, 10, 20, 12, 0, 0, 0, time.UTC)}
	g := New(Options{MaxStrikes: 5, BanFor: 7 * 24 * time.Hour, Now: c.now})
	ip := "203.0.113.9"
	for i := 1; i < 5; i++ {
		if g.Strike(ip, "failed sign-in") {
			t.Fatalf("banned after %d strikes", i)
		}
	}
	if !g.Strike(ip, "failed sign-in") || !g.Banned(ip) {
		t.Fatal("5th strike should ban")
	}
	c.t = c.t.Add(6 * 24 * time.Hour)
	if !g.Banned(ip) {
		t.Fatal("ban ended early")
	}
	c.t = c.t.Add(2 * 24 * time.Hour)
	if g.Banned(ip) {
		t.Fatal("ban should be over after 7 days")
	}
}

func TestOldStrikesExpire(t *testing.T) {
	c := &clock{time.Now()}
	g := New(Options{MaxStrikes: 3, Now: c.now})
	g.Strike("203.0.113.1", "x")
	g.Strike("203.0.113.1", "x")
	c.t = c.t.Add(25 * time.Hour)
	if g.Strike("203.0.113.1", "x") {
		t.Fatal("strikes older than a day still counted")
	}
}

func TestPrivateAddressesExempt(t *testing.T) {
	g := New(Options{MaxStrikes: 1})
	for _, ip := range []string{"127.0.0.1", "172.18.0.5", "192.168.1.20", "10.0.0.1", "::1", "fe80::1", "not-an-ip"} {
		if g.Strike(ip, "x") || g.Banned(ip) {
			t.Errorf("%s was banned", ip)
		}
	}
	g2 := New(Options{MaxStrikes: 1, BanPrivate: true})
	if !g2.Strike("192.168.1.20", "x") {
		t.Error("BanPrivate should ban LAN addresses")
	}
}

func TestBansSurviveRestartAndUnban(t *testing.T) {
	file := filepath.Join(t.TempDir(), "bans.json")
	g := New(Options{MaxStrikes: 2, StateFile: file})
	g.Strike("198.51.100.7", "probe")
	g.Strike("198.51.100.7", "probe")
	st, err := os.Stat(file)
	if err != nil || st.Mode().Perm() != 0o600 {
		t.Fatalf("state file: %v %v", st, err)
	}
	g2 := New(Options{StateFile: file})
	if !g2.Banned("198.51.100.7") {
		t.Fatal("ban lost on restart")
	}
	// another process (mnemos unban) edits the file; the running guard notices
	cli := New(Options{StateFile: file})
	if ok, err := cli.Unban("198.51.100.7"); !ok || err != nil {
		t.Fatalf("unban: %v %v", ok, err)
	}
	g2.mu.Lock()
	g2.checked = time.Time{}
	g2.loaded = time.Time{}
	g2.mu.Unlock()
	if g2.Banned("198.51.100.7") {
		t.Fatal("running guard did not pick up the unban")
	}
}

func TestProbe(t *testing.T) {
	for _, p := range []string{"/.env", "/conf/.env", "/wp-admin/.env", "/.git/config", "/vendor/phpunit/phpunit/src/Util/PHP/eval-stdin.php",
		"/index.php", "/wp-login.php", "/cgi-bin/luci", "/.aws/credentials", "/backup.sql", "/config.yml", "/server-status", "/.htaccess"} {
		if Probe(p) == "" {
			t.Errorf("%s should be a probe", p)
		}
	}
	for _, p := range []string{"/", "/app", "/robots.txt", "/.well-known/security.txt", "/api/file", "/raw/notes/.env", "/raw/a.php", "/api/me"} {
		if why := Probe(p); why != "" {
			t.Errorf("%s is not a probe (%s)", p, why)
		}
	}
	// no file the app serves may ever count as a probe
	fs.WalkDir(web.Files, "static", func(p string, d fs.DirEntry, err error) error {
		if err == nil && !d.IsDir() {
			if why := Probe("/" + p[len("static/"):]); why != "" {
				t.Errorf("app file /%s counted as probe: %s", p[len("static/"):], why)
			}
		}
		return nil
	})
}

func TestMiddleware(t *testing.T) {
	g := New(Options{MaxStrikes: 3})
	signed := false
	h := g.Middleware(func(r *http.Request) string { return r.Header.Get("CF-Connecting-IP") },
		func(*http.Request) bool { return signed },
		http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("ok")) }))
	get := func(path string) int {
		r := httptest.NewRequest("GET", path, nil)
		r.Header.Set("CF-Connecting-IP", "203.0.113.50")
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		return w.Code
	}
	signed = true
	for i := 0; i < 5; i++ {
		if get("/.env") != http.StatusNotFound {
			t.Fatal("probe should 404")
		}
	}
	if get("/") != 200 {
		t.Fatal("a signed-in user is never struck for a path")
	}
	signed = false
	for i := 0; i < 3; i++ {
		if c := get("/wp-admin/.env"); c != http.StatusNotFound {
			t.Fatalf("probe %d: %d", i, c)
		}
	}
	if c := get("/"); c != http.StatusForbidden {
		t.Fatalf("banned IP got %d on the landing page", c)
	}
}
