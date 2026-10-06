// Package auth handles samanote's single user: password check, signed session
// cookies and login rate limiting (same scheme as Arthik).
//
// SAMANOTE_PASSWORD may be plain text or a bcrypt hash ($2a$/$2b$/$2y$…, made with
// `samanote hash-password`). The session is a cookie holding user|expiry|HMAC-SHA256
// signed with SAMANOTE_SECRET, so it cannot be forged and survives restarts when a
// secret is set. There is no session store.
package auth

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/bcrypt"

	"gitlab.com/niharokz/samanote/internal/httpx"
)

// CookieName is the session cookie.
const CookieName = "samanote_session"

// SessionTTL is how long a sign-in lasts.
const SessionTTL = 30 * 24 * time.Hour

// Limits for failed sign-ins per client IP.
const (
	MaxFails   = 5
	FailWindow = 15 * time.Minute
)

// Auth holds the user, the password and the signing key.
type Auth struct {
	user     string
	password string // plain text or bcrypt hash
	secret   []byte
	limiter  *limiter
	now      func() time.Time
}

// New builds an Auth. An empty secret gets a random one (sessions end on restart).
func New(user, password, secret string) *Auth {
	a := &Auth{user: strings.ToLower(strings.TrimSpace(user)), password: password,
		limiter: newLimiter(MaxFails, FailWindow), now: time.Now}
	if secret == "" {
		b := make([]byte, 32)
		_, _ = rand.Read(b)
		a.secret = b
	} else {
		a.secret = []byte(secret)
	}
	return a
}

// HashPassword returns a bcrypt hash for .env.
func HashPassword(pw string) (string, error) {
	h, err := bcrypt.GenerateFromPassword([]byte(pw), 12)
	return string(h), err
}

// IsHash reports whether s looks like a bcrypt hash.
func IsHash(s string) bool {
	return strings.HasPrefix(s, "$2a$") || strings.HasPrefix(s, "$2b$") || strings.HasPrefix(s, "$2y$")
}

// Check compares a user name and password with the configured ones.
func (a *Auth) Check(user, pw string) bool {
	userOK := subtle.ConstantTimeCompare([]byte(strings.ToLower(strings.TrimSpace(user))), []byte(a.user)) == 1
	var pwOK bool
	if IsHash(a.password) {
		pwOK = bcrypt.CompareHashAndPassword([]byte(a.password), []byte(pw)) == nil
	} else {
		pwOK = subtle.ConstantTimeCompare([]byte(a.password), []byte(pw)) == 1
	}
	return userOK && pwOK
}

func (a *Auth) sign(payload string) string {
	m := hmac.New(sha256.New, a.secret)
	m.Write([]byte(payload))
	return hex.EncodeToString(m.Sum(nil))
}

// Token is base64(user|expiry-unix|signature).
func (a *Auth) Token(exp time.Time) string {
	p := a.user + "|" + strconv.FormatInt(exp.Unix(), 10)
	return base64.RawURLEncoding.EncodeToString([]byte(p + "|" + a.sign(p)))
}

// Valid reports whether tok is an unexpired session for the configured user.
func (a *Auth) Valid(tok string) bool {
	raw, err := base64.RawURLEncoding.DecodeString(tok)
	if err != nil {
		return false
	}
	parts := strings.Split(string(raw), "|")
	if len(parts) != 3 {
		return false
	}
	p := parts[0] + "|" + parts[1]
	if !hmac.Equal([]byte(a.sign(p)), []byte(parts[2])) {
		return false
	}
	exp, err := strconv.ParseInt(parts[1], 10, 64)
	if err != nil || a.now().Unix() > exp {
		return false
	}
	return parts[0] == a.user
}

// SignedIn reports whether r carries a valid session cookie.
func (a *Auth) SignedIn(r *http.Request) bool {
	c, err := r.Cookie(CookieName)
	return err == nil && a.Valid(c.Value)
}

// Register adds the public auth routes: login, logout and me.
func (a *Auth) Register(mux *http.ServeMux) {
	mux.HandleFunc("POST /api/login", a.login)
	mux.HandleFunc("POST /api/logout", func(w http.ResponseWriter, r *http.Request) {
		http.SetCookie(w, &http.Cookie{Name: CookieName, Value: "", Path: "/", MaxAge: -1,
			HttpOnly: true, SameSite: http.SameSiteLaxMode, Secure: secure(r)})
		httpx.JSON(w, http.StatusOK, map[string]bool{"ok": true})
	})
	mux.HandleFunc("GET /api/me", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		httpx.JSON(w, http.StatusOK, map[string]bool{"signed_in": a.SignedIn(r)})
	})
}

func (a *Auth) login(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get(httpx.Header) == "" {
		httpx.Error(w, http.StatusForbidden, "missing "+httpx.Header+" header")
		return
	}
	ip := clientIP(r)
	if !a.limiter.allow(ip) {
		httpx.Error(w, http.StatusTooManyRequests, "too many failed sign-ins — try again in 15 minutes")
		return
	}
	var in struct{ User, Password string }
	if err := httpx.Decode(w, r, 16<<10, &in); err != nil {
		httpx.Error(w, http.StatusBadRequest, "invalid request")
		return
	}
	if !a.Check(in.User, in.Password) {
		a.limiter.fail(ip)
		time.Sleep(400 * time.Millisecond) // blunt brute force a little more
		httpx.Error(w, http.StatusUnauthorized, "wrong user name or password")
		return
	}
	a.limiter.reset(ip)
	http.SetCookie(w, &http.Cookie{
		Name: CookieName, Value: a.Token(a.now().Add(SessionTTL)), Path: "/",
		MaxAge: int(SessionTTL.Seconds()), HttpOnly: true, SameSite: http.SameSiteLaxMode, Secure: secure(r),
	})
	httpx.JSON(w, http.StatusOK, map[string]bool{"ok": true})
}

// secure reports whether the browser reached us over HTTPS (directly or via Caddy/Cloudflare).
func secure(r *http.Request) bool {
	return r.TLS != nil || strings.EqualFold(r.Header.Get("X-Forwarded-Proto"), "https")
}

// clientIP prefers the address set by Cloudflare, then the one Caddy adds. The
// container publishes no port, so only Caddy can reach it and set these headers.
func clientIP(r *http.Request) string {
	if v := r.Header.Get("CF-Connecting-IP"); v != "" {
		return v
	}
	if v := r.Header.Get("X-Forwarded-For"); v != "" {
		return strings.TrimSpace(strings.Split(v, ",")[0])
	}
	h, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return h
}

// limiter counts failed sign-ins per IP in a sliding window.
type limiter struct {
	mu     sync.Mutex
	max    int
	window time.Duration
	fails  map[string][]time.Time
}

func newLimiter(max int, window time.Duration) *limiter {
	return &limiter{max: max, window: window, fails: map[string][]time.Time{}}
}

func (l *limiter) prune(ip string, now time.Time) []time.Time {
	var keep []time.Time
	for _, t := range l.fails[ip] {
		if now.Sub(t) < l.window {
			keep = append(keep, t)
		}
	}
	if len(keep) == 0 {
		delete(l.fails, ip)
	} else {
		l.fails[ip] = keep
	}
	return keep
}

func (l *limiter) allow(ip string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	return len(l.prune(ip, time.Now())) < l.max
}

func (l *limiter) fail(ip string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.fails[ip] = append(l.prune(ip, time.Now()), time.Now())
}

func (l *limiter) reset(ip string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	delete(l.fails, ip)
}

// Describe is for the startup log (never the password itself).
func (a *Auth) Describe() string {
	kind := "plain password"
	if IsHash(a.password) {
		kind = "bcrypt hash"
	}
	return a.user + " (" + kind + ")"
}
