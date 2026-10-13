// Package auth handles Mnemos's users — the owner and an optional read-only demo
// user — with signed session cookies, login rate limiting and the same-origin
// check for every change.
//
//   - Passwords may be plain text or a bcrypt hash ($2a$/$2b$/$2y$…, made with
//     `mnemos hash-password`). Credentials are compared in constant time.
//   - The session is a cookie holding user|expiry|HMAC-SHA256. Each user's HMAC key
//     is derived from MNEMOS_SECRET *and* that user's password, so changing either
//     signs that user out everywhere. There is no session store.
//   - Over HTTPS the cookie is named "__Host-mnemos": browsers then insist on
//     Secure, Path=/ and no Domain, so no other subdomain can set or read it.
//   - Behind a proxy the client IP comes from CF-Connecting-IP, X-Real-IP or the
//     *last* X-Forwarded-For entry (the one your proxy added), never the first one,
//     which the client controls.
package auth

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"log"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/bcrypt"

	"gitlab.com/niharokz/mnemos/internal/httpx"
)

// Cookie names: the __Host- one over HTTPS, the plain one for http://localhost.
const (
	CookieName       = "mnemos_session"
	SecureCookieName = "__Host-mnemos"
)

// SessionTTL is how long a sign-in lasts.
const SessionTTL = 30 * 24 * time.Hour

// Limits for failed sign-ins per client IP: MaxFails within FailWindow locks the
// IP out until the oldest failure is FailWindow old.
const (
	MaxFails   = 5
	FailWindow = 15 * time.Minute
	maxTracked = 10000 // IPs remembered at most (bounds memory under a spray attack)
)

// Account is a configured user.
type Account struct {
	User     httpx.User
	Password string // plain text or bcrypt hash
	ShowHint bool   // demo: user and password are shown on the sign-in card
	key      []byte // HMAC key = SHA-256(secret | user | password)
}

// Auth holds the accounts and the limiter.
type Auth struct {
	accounts map[string]*Account
	order    []*Account
	limiter  *limiter
	now      func() time.Time
	dummy    *Account // compared against for unknown users, so timing reveals nothing
}

// New builds an Auth. An empty secret gets a random one (sessions end on restart).
func New(secret string, accounts ...*Account) *Auth {
	a := &Auth{accounts: map[string]*Account{}, limiter: newLimiter(MaxFails, FailWindow), now: time.Now}
	if secret == "" {
		b := make([]byte, 32)
		_, _ = rand.Read(b)
		secret = hex.EncodeToString(b)
	}
	for _, ac := range accounts {
		if ac == nil {
			continue
		}
		ac.User.Name = strings.ToLower(strings.TrimSpace(ac.User.Name))
		k := sha256.Sum256([]byte("mnemos-session\x00" + secret + "\x00" + ac.User.Name + "\x00" + ac.Password))
		ac.key = k[:]
		a.accounts[ac.User.Name] = ac
		a.order = append(a.order, ac)
	}
	// unknown users are checked against a real stored password, so they take as long as a wrong one
	a.dummy = &Account{Password: secret}
	for _, ac := range a.order {
		if IsHash(ac.Password) {
			a.dummy = &Account{Password: ac.Password}
			break
		}
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

func checkPassword(stored, given string) bool {
	if IsHash(stored) {
		return bcrypt.CompareHashAndPassword([]byte(stored), []byte(given)) == nil
	}
	return subtle.ConstantTimeCompare([]byte(stored), []byte(given)) == 1
}

// Check returns the account for a user name and password, or nil. An unknown user
// still costs one password comparison, so timing does not reveal which part was wrong.
func (a *Auth) Check(user, pw string) *Account {
	ac := a.accounts[strings.ToLower(strings.TrimSpace(user))]
	if ac == nil {
		checkPassword(a.dummy.Password, pw)
		return nil
	}
	if !checkPassword(ac.Password, pw) {
		return nil
	}
	return ac
}

func sign(key []byte, payload string) string {
	m := hmac.New(sha256.New, key)
	m.Write([]byte(payload))
	return hex.EncodeToString(m.Sum(nil))
}

// Token is base64(user|expiry-unix|signature) for account ac.
func (a *Auth) Token(ac *Account, exp time.Time) string {
	p := ac.User.Name + "|" + strconv.FormatInt(exp.Unix(), 10)
	return base64.RawURLEncoding.EncodeToString([]byte(p + "|" + sign(ac.key, p)))
}

// Parse returns the account of an unexpired, correctly signed token, or nil.
func (a *Auth) Parse(tok string) *Account {
	raw, err := base64.RawURLEncoding.DecodeString(tok)
	if err != nil {
		return nil
	}
	parts := strings.Split(string(raw), "|")
	if len(parts) != 3 {
		return nil
	}
	ac := a.accounts[parts[0]]
	if ac == nil {
		return nil
	}
	p := parts[0] + "|" + parts[1]
	if !hmac.Equal([]byte(sign(ac.key, p)), []byte(parts[2])) {
		return nil
	}
	exp, err := strconv.ParseInt(parts[1], 10, 64)
	if err != nil || a.now().Unix() > exp {
		return nil
	}
	return ac
}

// Resolve returns the signed-in user of r, or nil.
func (a *Auth) Resolve(r *http.Request) *httpx.User {
	for _, name := range []string{SecureCookieName, CookieName} {
		if c, err := r.Cookie(name); err == nil {
			if ac := a.Parse(c.Value); ac != nil {
				return &ac.User
			}
		}
	}
	return nil
}

// DemoHint returns the demo user name and password when a demo user is configured.
func (a *Auth) DemoHint() (user, pass string, ok bool) {
	for _, ac := range a.order {
		if ac.ShowHint {
			return ac.User.Name, ac.Password, true
		}
	}
	return "", "", false
}

// Register adds the public auth routes: login, logout and me.
func (a *Auth) Register(mux *http.ServeMux) {
	mux.HandleFunc("POST /api/login", a.login)
	mux.HandleFunc("POST /api/logout", func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get(httpx.Header) == "" || !httpx.SameOrigin(r) {
			httpx.Error(w, http.StatusForbidden, "cross-site request refused")
			return
		}
		for _, name := range []string{SecureCookieName, CookieName} {
			c := &http.Cookie{Name: name, Value: "", Path: "/", MaxAge: -1, HttpOnly: true, SameSite: http.SameSiteLaxMode}
			c.Secure = name == SecureCookieName || Secure(r)
			http.SetCookie(w, c)
		}
		httpx.JSON(w, http.StatusOK, map[string]bool{"ok": true})
	})
	mux.HandleFunc("GET /api/me", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		out := map[string]any{"signed_in": false}
		if u := a.Resolve(r); u != nil {
			out = map[string]any{"signed_in": true, "user": u.Name, "read_only": u.ReadOnly}
		}
		if du, dp, ok := a.DemoHint(); ok {
			out["demo"] = map[string]string{"user": du, "password": dp}
		}
		httpx.JSON(w, http.StatusOK, out)
	})
}

func (a *Auth) login(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	if r.Header.Get(httpx.Header) == "" {
		httpx.Error(w, http.StatusForbidden, "missing "+httpx.Header+" header")
		return
	}
	if !httpx.SameOrigin(r) {
		httpx.Error(w, http.StatusForbidden, "cross-site request refused")
		return
	}
	ip := ClientIP(r)
	if !a.limiter.allow(ip) {
		log.Printf("login: %s is locked out (too many failed sign-ins)", ip)
		httpx.Error(w, http.StatusTooManyRequests, "too many failed sign-ins — try again in 15 minutes")
		return
	}
	var in struct{ User, Password string }
	if err := httpx.Decode(w, r, 16<<10, &in); err != nil {
		httpx.Error(w, http.StatusBadRequest, "invalid request")
		return
	}
	ac := a.Check(in.User, in.Password)
	if ac == nil {
		n := a.limiter.fail(ip)
		log.Printf("login: failed sign-in from %s (%d of %d)", ip, n, MaxFails)
		time.Sleep(time.Duration(300+100*n) * time.Millisecond) // blunt brute force a little more
		httpx.Error(w, http.StatusUnauthorized, "wrong user name or password")
		return
	}
	a.limiter.reset(ip)
	log.Printf("login: %s signed in from %s", ac.User.Name, ip)
	name := CookieName
	if Secure(r) {
		name = SecureCookieName
	}
	http.SetCookie(w, &http.Cookie{
		Name: name, Value: a.Token(ac, a.now().Add(SessionTTL)), Path: "/",
		MaxAge: int(SessionTTL.Seconds()), HttpOnly: true, SameSite: http.SameSiteLaxMode, Secure: Secure(r),
	})
	httpx.JSON(w, http.StatusOK, map[string]any{"ok": true, "user": ac.User.Name, "read_only": ac.User.ReadOnly})
}

// Secure reports whether the browser reached us over HTTPS (directly or via the proxy).
func Secure(r *http.Request) bool {
	return r.TLS != nil || strings.EqualFold(r.Header.Get("X-Forwarded-Proto"), "https")
}

// ClientIP returns the visitor's address: CF-Connecting-IP (Cloudflare), then
// X-Real-IP, then the last X-Forwarded-For entry (added by your own proxy; the
// earlier entries come from the client and can be forged), then the socket.
func ClientIP(r *http.Request) string {
	for _, h := range []string{"CF-Connecting-IP", "X-Real-IP"} {
		if v := strings.TrimSpace(r.Header.Get(h)); v != "" && net.ParseIP(v) != nil {
			return v
		}
	}
	if xff := r.Header.Values("X-Forwarded-For"); len(xff) > 0 {
		parts := strings.Split(xff[len(xff)-1], ",")
		if v := strings.TrimSpace(parts[len(parts)-1]); net.ParseIP(v) != nil {
			return v
		}
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

// fail records a failure and returns how many the IP now has in the window.
func (l *limiter) fail(ip string) int {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := time.Now()
	if len(l.fails) >= maxTracked {
		for k := range l.fails {
			l.prune(k, now)
		}
		if len(l.fails) >= maxTracked {
			l.fails = map[string][]time.Time{} // still full: start over rather than grow
		}
	}
	l.fails[ip] = append(l.prune(ip, now), now)
	return len(l.fails[ip])
}

func (l *limiter) reset(ip string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	delete(l.fails, ip)
}

// Describe is for the startup log (never a password).
func (a *Auth) Describe() string {
	var out []string
	for _, ac := range a.order {
		kind := "plain password"
		if IsHash(ac.Password) {
			kind = "bcrypt hash"
		}
		mode := "read-write"
		if ac.User.ReadOnly {
			mode = "read-only demo"
		}
		out = append(out, ac.User.Name+" ("+mode+", "+kind+")")
	}
	return strings.Join(out, ", ")
}
