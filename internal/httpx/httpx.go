// Package httpx holds the small HTTP helpers shared by the auth and API handlers:
// JSON responses, errors with a status, and a Router that checks the session and
// the anti-CSRF header before a handler runs.
package httpx

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"runtime/debug"
	"strings"

	"gitlab.com/niharokz/mnemos/internal/vault"
)

// User is a signed-in user and the vault they work in.
type User struct {
	Name     string
	ReadOnly bool // the demo user: every change is refused
	Vault    *vault.Vault
}

type ctxKey struct{}

// UserOf returns the signed-in user of a request that passed the Router.
func UserOf(r *http.Request) *User {
	u, _ := r.Context().Value(ctxKey{}).(*User)
	return u
}

// WithUser attaches u to r (used by the Router and by tests).
func WithUser(r *http.Request, u *User) *http.Request {
	return r.WithContext(context.WithValue(r.Context(), ctxKey{}, u))
}

// ErrReadOnly is what the demo user gets for any change.
const ErrReadOnly = "the demo is read-only — changes are not saved"

// Header must be present on every request that changes something. Browsers cannot
// add a custom header to a cross-site request without CORS (which mnemos never
// grants), so together with SameSite cookies this blocks cross-site forgery.
const Header = "X-Mnemos"

// SameOrigin is the CSRF check for every request that changes something: modern
// browsers send Sec-Fetch-Site, and all send Origin on cross-site POSTs. A
// request from another site is refused; one without either header (curl, old
// browsers) passes this check but still needs the X-Mnemos header and the
// SameSite session cookie.
func SameOrigin(r *http.Request) bool {
	if s := r.Header.Get("Sec-Fetch-Site"); s != "" && s != "same-origin" && s != "none" {
		return false
	}
	if o := r.Header.Get("Origin"); o != "" {
		u, err := url.Parse(o)
		if err != nil || !strings.EqualFold(u.Host, r.Host) {
			return false
		}
	}
	return true
}

// StatusError is an error with an HTTP status.
type StatusError struct {
	Code int
	Msg  string
	Body map[string]any // extra JSON fields (e.g. the content on disk for a 409)
}

func (e *StatusError) Error() string { return e.Msg }

// Errorf builds a StatusError.
func Errorf(code int, format string, a ...any) error {
	return &StatusError{Code: code, Msg: fmt.Sprintf(format, a...)}
}

// JSON writes v with status.
func JSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

// Error writes {"error": msg}.
func Error(w http.ResponseWriter, status int, msg string) {
	JSON(w, status, map[string]string{"error": msg})
}

// Decode reads a JSON body of at most limit bytes into v.
func Decode(w http.ResponseWriter, r *http.Request, limit int64, v any) error {
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, limit))
	if err := dec.Decode(v); err != nil {
		var mbe *http.MaxBytesError
		if errors.As(err, &mbe) {
			return Errorf(http.StatusRequestEntityTooLarge, "request too large")
		}
		return Errorf(http.StatusBadRequest, "invalid request: %v", err)
	}
	return nil
}

// HandlerFunc is an API handler; a returned error is written as JSON
// (*StatusError keeps its code, anything else is a 500).
type HandlerFunc func(w http.ResponseWriter, r *http.Request) error

// Router registers signed-in API routes.
type Router struct {
	Mux     *http.ServeMux
	Resolve func(r *http.Request) *User
}

// Handle registers a route (Go 1.22 syntax, e.g. "PUT /api/file"). Any method but
// GET/HEAD is a change: it needs the X-Mnemos header, must be same-origin, and is
// refused for read-only users.
func (rt *Router) Handle(pattern string, h HandlerFunc) { rt.handle(pattern, h, false) }

// HandleSafe registers a POST that changes nothing (e.g. rendering a preview), so
// read-only users may call it too. The CSRF checks still apply.
func (rt *Router) HandleSafe(pattern string, h HandlerFunc) { rt.handle(pattern, h, true) }

func (rt *Router) handle(pattern string, h HandlerFunc, safe bool) {
	rt.Mux.HandleFunc(pattern, func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if v := recover(); v != nil {
				log.Printf("PANIC %s %s: %v\n%s", r.Method, r.URL.Path, v, debug.Stack())
				Error(w, http.StatusInternalServerError, "internal error — see the server log")
			}
		}()
		u := rt.Resolve(r)
		if u == nil {
			Error(w, http.StatusUnauthorized, "please sign in")
			return
		}
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			if r.Header.Get(Header) == "" {
				Error(w, http.StatusForbidden, "missing "+Header+" header")
				return
			}
			if !SameOrigin(r) {
				Error(w, http.StatusForbidden, "cross-site request refused")
				return
			}
			if u.ReadOnly && !safe {
				JSON(w, http.StatusForbidden, map[string]any{"error": ErrReadOnly, "read_only": true})
				return
			}
		}
		w.Header().Set("Cache-Control", "no-store")
		if err := h(w, WithUser(r, u)); err != nil {
			var se *StatusError
			if errors.As(err, &se) {
				body := map[string]any{"error": se.Msg}
				for k, v := range se.Body {
					body[k] = v
				}
				JSON(w, se.Code, body)
				return
			}
			// details (which may include server paths) go to the log, not the browser
			log.Printf("ERROR %s %s: %v", r.Method, r.URL.Path, err)
			Error(w, http.StatusInternalServerError, "internal error — see the server log")
		}
	})
}
