package server

import (
	"encoding/json"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"time"

	"gitlab.com/niharokz/mnemos/internal/httpx"
)

// DefaultCleanupDays is how long trash items are kept unless you choose otherwise.
const DefaultCleanupDays = 30

// prefs are the instance settings changed from the web or Android app (not from
// .env): kept in MNEMOS_STATE/settings.json, or in memory when that is not writable.
type prefs struct {
	mu   sync.Mutex
	file string
	v    prefValues
}

type prefValues struct {
	CleanupDays int `json:"cleanup_days"`
}

func newPrefs(stateDir string) *prefs {
	p := &prefs{v: prefValues{CleanupDays: DefaultCleanupDays}}
	if stateDir != "" {
		p.file = filepath.Join(stateDir, "settings.json")
		if b, err := os.ReadFile(p.file); err == nil {
			if err := json.Unmarshal(b, &p.v); err != nil {
				log.Printf("WARN %s: %v — using defaults", p.file, err)
			}
		}
	}
	if p.v.CleanupDays < 1 {
		p.v.CleanupDays = DefaultCleanupDays
	}
	return p
}

func (p *prefs) get() prefValues {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.v
}

// set saves the values (atomically, mode 600); a failed save keeps them in memory.
func (p *prefs) set(v prefValues) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.v = v
	if p.file == "" {
		return
	}
	b, _ := json.MarshalIndent(v, "", "  ")
	tmp := p.file + ".tmp"
	if err := os.WriteFile(tmp, append(b, '\n'), 0o600); err != nil {
		log.Printf("WARN saving %s: %v", p.file, err)
		return
	}
	if err := os.Rename(tmp, p.file); err != nil {
		log.Printf("WARN saving %s: %v", p.file, err)
	}
}

// ---- handlers ----------------------------------------------------------------

func (a *api) stats(w http.ResponseWriter, r *http.Request) error {
	st, err := vaultOf(r).Stats()
	if err != nil {
		return err
	}
	return ok(w, st)
}

func (a *api) settingsGet(w http.ResponseWriter, r *http.Request) error {
	return ok(w, a.prefs.get())
}

func (a *api) settingsPut(w http.ResponseWriter, r *http.Request) error {
	var in prefValues
	if err := httpx.Decode(w, r, 4<<10, &in); err != nil {
		return err
	}
	if in.CleanupDays < 1 || in.CleanupDays > 3650 {
		return httpx.Errorf(http.StatusBadRequest, "keep trash between 1 and 3650 days")
	}
	a.prefs.set(in)
	return ok(w, in)
}

// cleanupDays is ?days= when given, otherwise the saved setting.
func (a *api) cleanupDays(r *http.Request) (int, error) {
	if s := r.URL.Query().Get("days"); s != "" {
		d, err := strconv.Atoi(s)
		if err != nil || d < 1 || d > 3650 {
			return 0, httpx.Errorf(http.StatusBadRequest, "days must be 1–3650")
		}
		return d, nil
	}
	return a.prefs.get().CleanupDays, nil
}

// cleanupPreview (GET) lists what a cleanup would move and delete, changing nothing.
func (a *api) cleanupPreview(w http.ResponseWriter, r *http.Request) error {
	days, err := a.cleanupDays(r)
	if err != nil {
		return err
	}
	rep, err := vaultOf(r).Cleanup(days, true, time.Now())
	if err != nil {
		return vaultErr(err)
	}
	return ok(w, rep)
}

// cleanupRun (POST) moves conflict copies to the trash and deletes old trash items.
func (a *api) cleanupRun(w http.ResponseWriter, r *http.Request) error {
	days, err := a.cleanupDays(r)
	if err != nil {
		return err
	}
	rep, err := vaultOf(r).Cleanup(days, false, time.Now())
	if err != nil {
		return vaultErr(err)
	}
	log.Printf("cleanup by %s: %d conflict copies moved to the trash, %d trash items older than %d days deleted (%d bytes)",
		httpx.UserOf(r).Name, len(rep.Moved), len(rep.Deleted), days, rep.FreedSize)
	return ok(w, rep)
}
