// Package httpx holds the small HTTP helpers shared by the auth and API
// handlers: JSON replies, coded errors, and a Router that checks the session
// and the anti-CSRF header before a handler runs.
package httpx

import (
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"runtime/debug"
	"strings"
)

// Header must be on every request that changes something. Browsers cannot add
// a custom header to a cross-site request without CORS (never granted), so
// together with SameSite cookies this blocks cross-site forgery.
const Header = "X-Mnemostxt"

// SameOrigin refuses requests a browser marks as cross-site.
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

// Error is an error with an HTTP status and a code the frontend understands
// (CONFLICT, NOT_FOUND, EXISTS, DENIED, TOO_LARGE, INVALID, AUTH, IO).
type Error struct {
	Status int
	Code   string
	Msg    string
}

func (e *Error) Error() string { return e.Msg }

func Errorf(status int, code, format string, a ...any) error {
	return &Error{Status: status, Code: code, Msg: fmt.Sprintf(format, a...)}
}

func JSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func Fail(w http.ResponseWriter, status int, code, msg string) {
	JSON(w, status, map[string]string{"error": msg, "code": code})
}

// Decode reads a JSON body of at most limit bytes into v.
func Decode(w http.ResponseWriter, r *http.Request, limit int64, v any) error {
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, limit)).Decode(v); err != nil {
		var mbe *http.MaxBytesError
		if errors.As(err, &mbe) {
			return Errorf(http.StatusRequestEntityTooLarge, "TOO_LARGE", "request too large")
		}
		return Errorf(http.StatusBadRequest, "INVALID", "invalid request")
	}
	return nil
}

// HandlerFunc is an API handler; a returned *Error keeps its status, anything
// else is logged and answered with a 500 (server paths never reach the browser).
type HandlerFunc func(w http.ResponseWriter, r *http.Request) error

// Router registers signed-in API routes.
type Router struct {
	Mux    *http.ServeMux
	Signed func(r *http.Request) bool
}

// Handle registers a route (Go 1.22 pattern). Anything but GET/HEAD needs the
// X-Mnemostxt header and must be same-origin.
func (rt *Router) Handle(pattern string, h HandlerFunc) {
	rt.Mux.HandleFunc(pattern, func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if v := recover(); v != nil {
				log.Printf("PANIC %s %s: %v\n%s", r.Method, r.URL.Path, v, debug.Stack())
				Fail(w, http.StatusInternalServerError, "IO", "internal error — see the server log")
			}
		}()
		w.Header().Set("Cache-Control", "no-store")
		if !rt.Signed(r) {
			Fail(w, http.StatusUnauthorized, "AUTH", "please sign in")
			return
		}
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			if r.Header.Get(Header) == "" || !SameOrigin(r) {
				Fail(w, http.StatusForbidden, "DENIED", "cross-site request refused")
				return
			}
		}
		if err := h(w, r); err != nil {
			var he *Error
			if errors.As(err, &he) {
				Fail(w, he.Status, he.Code, he.Msg)
				return
			}
			log.Printf("ERROR %s %s: %v", r.Method, r.URL.Path, err)
			Fail(w, http.StatusInternalServerError, "IO", "internal error — see the server log")
		}
	})
}
