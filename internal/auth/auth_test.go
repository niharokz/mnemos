package auth

import (
	"encoding/base64"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestTokenRoundTripAndTamper(t *testing.T) {
	a := New("Nihar", "pw", "secret")
	tok := a.Token(time.Now().Add(time.Hour))
	if !a.Valid(tok) {
		t.Fatal("fresh token rejected")
	}
	if a.Valid("1") || a.Valid("") {
		t.Fatal("old-style cookie accepted")
	}
	raw, _ := base64.RawURLEncoding.DecodeString(tok)
	forged := base64.RawURLEncoding.EncodeToString([]byte(strings.Replace(string(raw), "|", "x|", 1)))
	if a.Valid(forged) {
		t.Fatal("tampered token accepted")
	}
	if New("nihar", "pw", "other").Valid(tok) {
		t.Fatal("token valid under another secret")
	}
	if a.Valid(a.Token(time.Now().Add(-time.Minute))) {
		t.Fatal("expired token accepted")
	}
}

func TestCheckPlainAndHash(t *testing.T) {
	if a := New("me", "plain-pass", ""); !a.Check("ME ", "plain-pass") || a.Check("me", "nope") || a.Check("you", "plain-pass") {
		t.Fatal("plain password check")
	}
	h, err := HashPassword("hashed-pass")
	if err != nil {
		t.Fatal(err)
	}
	if a := New("me", h, ""); !a.Check("me", "hashed-pass") || a.Check("me", h) {
		t.Fatal("bcrypt check")
	}
}

func login(a *Auth, mux *http.ServeMux, body string, header bool) *httptest.ResponseRecorder {
	r := httptest.NewRequest("POST", "/api/login", strings.NewReader(body))
	if header {
		r.Header.Set("X-Samanote", "1")
	}
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, r)
	return w
}

func TestLoginFlowAndRateLimit(t *testing.T) {
	a := New("me", "right", "s")
	mux := http.NewServeMux()
	a.Register(mux)
	if w := login(a, mux, `{"user":"me","password":"right"}`, false); w.Code != http.StatusForbidden {
		t.Fatalf("no header: %d", w.Code)
	}
	w := login(a, mux, `{"user":"me","password":"right"}`, true)
	if w.Code != 200 {
		t.Fatalf("login: %d %s", w.Code, w.Body)
	}
	c := w.Result().Cookies()[0]
	if !c.HttpOnly || c.SameSite != http.SameSiteLaxMode || !a.Valid(c.Value) {
		t.Fatalf("cookie: %+v", c)
	}
	for i := 0; i < MaxFails; i++ {
		if w := login(a, mux, `{"user":"me","password":"wrong"}`, true); w.Code != http.StatusUnauthorized {
			t.Fatalf("fail %d: %d", i, w.Code)
		}
	}
	if w := login(a, mux, `{"user":"me","password":"right"}`, true); w.Code != http.StatusTooManyRequests {
		t.Fatalf("after %d fails: %d", MaxFails, w.Code)
	}
}
