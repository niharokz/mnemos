// Package guard bans client IPs that misbehave: failed sign-ins and requests for
// files a scanner looks for (.env, .git, wp-admin, *.php …) count as strikes, and
// MaxStrikes of them within StrikeWindow ban the IP for BanFor. A banned IP gets
// 403 for every request, the public pages included.
//
// Bans are kept in a small JSON file (MNEMOS_STATE/bans.json) so they survive a
// restart, and the file is re-read when it changes, so `mnemos unban <ip>` (run in
// the container) takes effect without a restart. Without a writable state folder
// bans still work, in memory only.
//
// Private, loopback and link-local addresses are never banned unless asked:
// behind a reverse proxy a missing client-IP header would otherwise make the
// proxy's own address the one that gets banned, which would lock everyone out.
package guard

import (
	"encoding/json"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

// StrikeWindow is how long a strike counts towards a ban.
const StrikeWindow = 24 * time.Hour

// maxTracked bounds memory under a spray of different addresses.
const maxTracked = 20000

// Options configure a Guard.
type Options struct {
	MaxStrikes   int           // strikes within StrikeWindow that ban an IP (default 5)
	BanFor       time.Duration // how long a ban lasts (default 7 days)
	StateFile    string        // JSON file the bans are kept in; "" = memory only
	BanPrivate   bool          // also ban private/loopback addresses (off by default)
	Now          func() time.Time
	ReloadPeriod time.Duration // how often the state file is checked for outside edits
}

// Ban is one banned address.
type Ban struct {
	IP     string    `json:"ip"`
	Until  time.Time `json:"until"`
	Reason string    `json:"reason"`
}

// Guard tracks strikes and bans.
type Guard struct {
	opt     Options
	mu      sync.Mutex
	strikes map[string][]time.Time
	bans    map[string]Ban
	loaded  time.Time // mtime of the state file when last read
	checked time.Time // when the state file was last checked
}

// New builds a Guard and loads saved bans.
func New(opt Options) *Guard {
	if opt.MaxStrikes <= 0 {
		opt.MaxStrikes = 5
	}
	if opt.BanFor <= 0 {
		opt.BanFor = 7 * 24 * time.Hour
	}
	if opt.Now == nil {
		opt.Now = time.Now
	}
	if opt.ReloadPeriod <= 0 {
		opt.ReloadPeriod = 10 * time.Second
	}
	g := &Guard{opt: opt, strikes: map[string][]time.Time{}, bans: map[string]Ban{}}
	g.mu.Lock()
	g.reloadLocked(true)
	g.mu.Unlock()
	return g
}

// Exempt reports whether ip is never banned (private/loopback, unless BanPrivate).
func (g *Guard) Exempt(ip string) bool {
	p := net.ParseIP(ip)
	if p == nil {
		return true // not an address we can ban reliably
	}
	if g.opt.BanPrivate {
		return false
	}
	return p.IsLoopback() || p.IsPrivate() || p.IsLinkLocalUnicast() || p.IsUnspecified()
}

// Banned reports whether ip is banned right now.
func (g *Guard) Banned(ip string) bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.reloadLocked(false)
	b, ok := g.bans[ip]
	if !ok {
		return false
	}
	if !g.opt.Now().Before(b.Until) {
		delete(g.bans, ip)
		g.saveLocked()
		return false
	}
	return true
}

// Strike records one strike for ip and reports whether it is now banned.
func (g *Guard) Strike(ip, reason string) bool {
	if g.Exempt(ip) {
		return false
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	now := g.opt.Now()
	if b, ok := g.bans[ip]; ok && now.Before(b.Until) {
		return true
	}
	if len(g.strikes) >= maxTracked {
		for k := range g.strikes {
			g.pruneLocked(k, now)
		}
		if len(g.strikes) >= maxTracked {
			g.strikes = map[string][]time.Time{}
		}
	}
	list := append(g.pruneLocked(ip, now), now)
	g.strikes[ip] = list
	log.Printf("guard: strike %d of %d for %s — %s", len(list), g.opt.MaxStrikes, ip, reason)
	if len(list) < g.opt.MaxStrikes {
		return false
	}
	delete(g.strikes, ip)
	g.bans[ip] = Ban{IP: ip, Until: now.Add(g.opt.BanFor).UTC().Truncate(time.Second), Reason: reason}
	g.saveLocked()
	log.Printf("guard: BANNED %s until %s (%d strikes, last: %s)", ip, g.bans[ip].Until.Local().Format("2006-01-02 15:04"), len(list), reason)
	return true
}

// Forget clears the strikes of ip (after a successful sign-in).
func (g *Guard) Forget(ip string) {
	g.mu.Lock()
	defer g.mu.Unlock()
	delete(g.strikes, ip)
}

// List returns the current bans, soonest to expire first.
func (g *Guard) List() []Ban {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.reloadLocked(true)
	now := g.opt.Now()
	var out []Ban
	for _, b := range g.bans {
		if now.Before(b.Until) {
			out = append(out, b)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Until.Before(out[j].Until) })
	return out
}

// Unban lifts the ban on ip; it reports whether there was one.
func (g *Guard) Unban(ip string) (bool, error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.reloadLocked(true)
	_, ok := g.bans[ip]
	delete(g.bans, ip)
	delete(g.strikes, ip)
	if !ok {
		return false, nil
	}
	return true, g.saveLocked()
}

func (g *Guard) pruneLocked(ip string, now time.Time) []time.Time {
	var keep []time.Time
	for _, t := range g.strikes[ip] {
		if now.Sub(t) < StrikeWindow {
			keep = append(keep, t)
		}
	}
	if len(keep) == 0 {
		delete(g.strikes, ip)
	}
	return keep
}

// reloadLocked re-reads the state file when it changed on disk (or always when force).
func (g *Guard) reloadLocked(force bool) {
	if g.opt.StateFile == "" {
		return
	}
	now := g.opt.Now()
	if !force && now.Sub(g.checked) < g.opt.ReloadPeriod {
		return
	}
	g.checked = now
	st, err := os.Stat(g.opt.StateFile)
	if err != nil {
		return
	}
	if !force && st.ModTime().Equal(g.loaded) {
		return
	}
	b, err := os.ReadFile(g.opt.StateFile)
	if err != nil {
		log.Printf("guard: reading %s: %v", g.opt.StateFile, err)
		return
	}
	var list []Ban
	if err := json.Unmarshal(b, &list); err != nil {
		log.Printf("guard: %s is not valid JSON, ignoring it: %v", g.opt.StateFile, err)
		return
	}
	bans := map[string]Ban{}
	for _, x := range list {
		if net.ParseIP(x.IP) != nil && now.Before(x.Until) {
			bans[x.IP] = x
		}
	}
	g.bans = bans
	g.loaded = st.ModTime()
}

// saveLocked writes the bans atomically (temp file + rename).
func (g *Guard) saveLocked() error {
	if g.opt.StateFile == "" {
		return nil
	}
	now := g.opt.Now()
	list := make([]Ban, 0, len(g.bans))
	for _, b := range g.bans {
		if now.Before(b.Until) {
			list = append(list, b)
		}
	}
	sort.Slice(list, func(i, j int) bool { return list[i].IP < list[j].IP })
	b, _ := json.MarshalIndent(list, "", "  ")
	dir := filepath.Dir(g.opt.StateFile)
	tmp, err := os.CreateTemp(dir, ".bans-*.tmp")
	if err != nil {
		log.Printf("guard: can't save bans in %s (%v) — they last until restart", dir, err)
		return err
	}
	_, err = tmp.Write(append(b, '\n'))
	if cerr := tmp.Close(); err == nil {
		err = cerr
	}
	if err == nil {
		err = os.Chmod(tmp.Name(), 0o600)
	}
	if err == nil {
		err = os.Rename(tmp.Name(), g.opt.StateFile)
	}
	if err != nil {
		os.Remove(tmp.Name())
		log.Printf("guard: saving bans: %v", err)
		return err
	}
	if st, err := os.Stat(g.opt.StateFile); err == nil {
		g.loaded = st.ModTime()
	}
	return nil
}

// ---- HTTP -------------------------------------------------------------------

// Middleware refuses banned IPs with 403, and answers 404 to paths only a scanner
// asks for (.env, .git, *.php …), giving a strike when the request is signed out.
// signedIn says whether r carries a valid session; a signed-in user is never struck.
func (g *Guard) Middleware(clientIP func(*http.Request) string, signedIn func(*http.Request) bool, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ip := clientIP(r)
		if g.Banned(ip) {
			w.Header().Set("Content-Type", "text/plain; charset=utf-8")
			w.Header().Set("Cache-Control", "no-store")
			w.Header().Set("Connection", "close")
			w.WriteHeader(http.StatusForbidden)
			w.Write([]byte("forbidden\n"))
			return
		}
		if why := Probe(r.URL.Path); why != "" {
			if !signedIn(r) {
				g.Strike(ip, fmt.Sprintf("probe %s %s (%s)", r.Method, truncate(r.URL.Path, 120), why))
			}
			http.NotFound(w, r)
			return
		}
		next.ServeHTTP(w, r)
	})
}

// probeParts are path pieces only vulnerability scanners ask for.
var probeParts = []string{
	"wp-admin", "wp-login", "wp-content", "wp-includes", "wp-json", "xmlrpc", "wordpress",
	"phpmyadmin", "myadmin", "cgi-bin", "phpunit", "eval-stdin", "_ignition", "telescope",
	"server-status", "actuator", "boaform", "hnap1", "/owa/", "/ecp/", "autodiscover",
	"etc/passwd", "id_rsa", "web.config",
}

// probeExts are file types mnemos never serves at a public path.
var probeExts = map[string]bool{
	".php": true, ".php5": true, ".php7": true, ".phtml": true, ".asp": true, ".aspx": true,
	".jsp": true, ".jspx": true, ".cgi": true, ".pl": true, ".sh": true, ".env": true,
	".sql": true, ".bak": true, ".old": true, ".orig": true, ".swp": true, ".ini": true,
	".conf": true, ".cfg": true, ".yml": true, ".yaml": true, ".log": true, ".key": true,
	".pem": true, ".db": true, ".sqlite": true, ".tar": true, ".gz": true, ".zip": true, ".rar": true,
}

// Probe returns why urlPath looks like a scan for secrets or exploits, or "".
// It only looks at public URL paths: the app's own files live under /raw/ and /api/
// behind the sign-in, and those are never treated as probes.
func Probe(urlPath string) string {
	p := strings.ToLower(urlPath)
	if strings.HasPrefix(p, "/raw/") || strings.HasPrefix(p, "/api/") {
		return ""
	}
	for _, seg := range strings.Split(p, "/") {
		if strings.HasPrefix(seg, ".") && seg != ".well-known" && seg != "." && seg != ".." {
			return "hidden path " + seg // .env, .git, .aws, .ssh, .htaccess …
		}
	}
	if probeExts[path.Ext(p)] {
		return "file type " + path.Ext(p)
	}
	for _, s := range probeParts {
		if strings.Contains(p, s) {
			return "scanner path " + s
		}
	}
	return ""
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "…"
}
