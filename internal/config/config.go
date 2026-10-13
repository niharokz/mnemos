// Package config reads Mnemos's settings from the environment. In Docker they come
// from the shared .env through docker-compose.yml; when run by hand, a .env file in
// the working directory is read too (real environment variables win).
//
// Only container-side values live here. Host paths (NOTE_PATH), PUID/PGID and the
// network are used by docker-compose.yml alone, so nothing host-specific is in code.
//
// Every MNEMOS_X falls back to SAMANOTE_X, so a .env written for Samanote keeps
// working after the switch (a warning names each old key in use).
package config

import (
	"bufio"
	"fmt"
	"log"
	"os"
	"strconv"
	"strings"
)

// Config is the runtime configuration.
type Config struct {
	Listen       string   // MNEMOS_LISTEN, default :8080
	NotesDir     string   // MNEMOS_NOTES, default /notes (the container side of NOTE_PATH)
	User         string   // MNEMOS_USER, default "admin"
	Password     string   // MNEMOS_PASSWORD (plain text or bcrypt hash) — required
	Secret       string   // MNEMOS_SECRET — signs sessions; random per start when empty
	Hidden       []string // MNEMOS_HIDDEN — extra names to hide (dot entries are always hidden)
	TrashDir     string   // MNEMOS_TRASH, default .trash (inside the vault, like Obsidian)
	AutosaveMS   int      // MNEMOS_AUTOSAVE_MS, default 1000
	MaxFileMB    int      // MNEMOS_MAX_FILE_MB, default 5 — bigger files open read-only
	MaxUploadMB  int      // MNEMOS_MAX_UPLOAD_MB, default 20 — largest image that can be added
	CaptureNote  string   // MNEMOS_CAPTURE, default inbox/note.md — quick capture appends here
	TemplatesDir string   // MNEMOS_TEMPLATES, default templates — "New from template" lists it
	DemoEnabled  bool     // MNEMOS_DEMO, default true — read-only demo user with a sample vault
	DemoUser     string   // MNEMOS_DEMO_USER, default "demouser"
	DemoPassword string   // MNEMOS_DEMO_PASSWORD, default "demo" (shown on the sign-in card)
}

// MaxFileBytes is MaxFileMB in bytes.
func (c Config) MaxFileBytes() int64 { return int64(c.MaxFileMB) << 20 }

// LoadDotEnv sets variables from a KEY=VALUE file without overriding the real
// environment. A missing file is not an error.
func LoadDotEnv(path string) error {
	f, err := os.Open(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		k, v, ok := strings.Cut(strings.TrimPrefix(line, "export "), "=")
		if !ok {
			continue
		}
		k, v = strings.TrimSpace(k), strings.TrimSpace(v)
		if len(v) >= 2 && (v[0] == '"' && v[len(v)-1] == '"' || v[0] == '\'' && v[len(v)-1] == '\'') {
			v = v[1 : len(v)-1]
		}
		if _, set := os.LookupEnv(k); !set {
			os.Setenv(k, v)
		}
	}
	return sc.Err()
}

// raw reads MNEMOS_<key>, falling back to the old SAMANOTE_<key>.
func raw(key string) string {
	if v := strings.TrimSpace(os.Getenv("MNEMOS_" + key)); v != "" {
		return v
	}
	if v := strings.TrimSpace(os.Getenv("SAMANOTE_" + key)); v != "" {
		log.Printf("NOTE using SAMANOTE_%s — rename it to MNEMOS_%s in .env", key, key)
		return v
	}
	return ""
}

func env(key, def string) string {
	if v := raw(key); v != "" {
		return v
	}
	return def
}

func envInt(key string, def, min, max int) (int, error) {
	s := raw(key)
	if s == "" {
		return def, nil
	}
	n, err := strconv.Atoi(s)
	if err != nil || n < min || n > max {
		return def, fmt.Errorf("MNEMOS_%s=%q: want a whole number from %d to %d", key, s, min, max)
	}
	return n, nil
}

// vaultRel checks a setting that names a path inside the vault.
func vaultRel(key, v string) error {
	if v == "" || strings.HasPrefix(v, "/") || strings.Contains(v, "..") || strings.HasPrefix(v, ".") {
		return fmt.Errorf("MNEMOS_%s=%q must be a path inside the vault, e.g. inbox/note.md", key, v)
	}
	return nil
}

// Load builds the Config from the environment and validates it.
func Load() (Config, error) {
	c := Config{
		Listen:       env("LISTEN", ":8080"),
		NotesDir:     env("NOTES", "/notes"),
		User:         env("USER", "admin"),
		Password:     raw("PASSWORD"),
		Secret:       raw("SECRET"),
		TrashDir:     env("TRASH", ".trash"),
		CaptureNote:  strings.Trim(env("CAPTURE", "inbox/note.md"), "/"),
		TemplatesDir: strings.Trim(env("TEMPLATES", "templates"), "/"),
		DemoEnabled:  !strings.EqualFold(env("DEMO", "true"), "false"),
		DemoUser:     strings.ToLower(env("DEMO_USER", "demouser")),
		DemoPassword: env("DEMO_PASSWORD", "demo"),
	}
	for _, h := range strings.Split(raw("HIDDEN"), ",") {
		if h = strings.Trim(strings.TrimSpace(h), "/"); h != "" {
			c.Hidden = append(c.Hidden, h)
		}
	}
	var err error
	if c.AutosaveMS, err = envInt("AUTOSAVE_MS", 1000, 300, 60000); err != nil {
		return c, err
	}
	if c.MaxFileMB, err = envInt("MAX_FILE_MB", 5, 1, 100); err != nil {
		return c, err
	}
	if c.MaxUploadMB, err = envInt("MAX_UPLOAD_MB", 20, 1, 200); err != nil {
		return c, err
	}
	if c.Password == "" {
		return c, fmt.Errorf("MNEMOS_PASSWORD is not set (put it in .env — plain text or a hash from `mnemos hash-password`)")
	}
	switch strings.ToLower(c.Password) {
	case "change-me", "changeme", "password", "admin", "secret":
		return c, fmt.Errorf("MNEMOS_PASSWORD is still a placeholder (%q) — set a real one", c.Password)
	}
	if c.DemoEnabled && strings.EqualFold(c.User, c.DemoUser) {
		return c, fmt.Errorf("MNEMOS_USER and MNEMOS_DEMO_USER must differ")
	}
	if strings.Contains(c.TrashDir, "..") || strings.HasPrefix(c.TrashDir, "/") || c.TrashDir == "" {
		return c, fmt.Errorf("MNEMOS_TRASH=%q must be a folder name inside the vault, e.g. .trash", c.TrashDir)
	}
	if err := vaultRel("CAPTURE", c.CaptureNote); err != nil {
		return c, err
	}
	if err := vaultRel("TEMPLATES", c.TemplatesDir); err != nil {
		return c, err
	}
	st, err := os.Stat(c.NotesDir)
	if err != nil {
		return c, fmt.Errorf("notes folder %s: %w (is NOTE_PATH mounted at %s?)", c.NotesDir, err, c.NotesDir)
	}
	if !st.IsDir() {
		return c, fmt.Errorf("notes folder %s is not a directory", c.NotesDir)
	}
	return c, nil
}
