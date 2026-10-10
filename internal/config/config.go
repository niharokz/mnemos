// Package config reads samanote's settings from the environment. In Docker they come
// from the shared .env through docker-compose.yml; when run by hand, a .env file in
// the working directory is read too (real environment variables win).
//
// Only container-side values live here. Host paths (NOTE_PATH), PUID/PGID and the
// network are used by docker-compose.yml alone, so nothing host-specific is in code.
package config

import (
	"bufio"
	"fmt"
	"os"
	"strconv"
	"strings"
)

// Config is the runtime configuration.
type Config struct {
	Listen     string   // SAMANOTE_LISTEN, default :8080
	NotesDir   string   // SAMANOTE_NOTES, default /notes (the container side of NOTE_PATH)
	User       string   // SAMANOTE_USER, default "admin"
	Password   string   // SAMANOTE_PASSWORD (plain text or bcrypt hash) — required
	Secret     string   // SAMANOTE_SECRET — signs sessions; random per start when empty
	Hidden     []string // SAMANOTE_HIDDEN — extra names to hide (dot entries are always hidden)
	TrashDir   string   // SAMANOTE_TRASH, default .trash (inside the vault, like Obsidian)
	AutosaveMS int      // SAMANOTE_AUTOSAVE_MS, default 1000
	MaxFileMB  int      // SAMANOTE_MAX_FILE_MB, default 5 — bigger files open read-only
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

func env(k, def string) string {
	if v := strings.TrimSpace(os.Getenv(k)); v != "" {
		return v
	}
	return def
}

func envInt(k string, def, min, max int) (int, error) {
	s := strings.TrimSpace(os.Getenv(k))
	if s == "" {
		return def, nil
	}
	n, err := strconv.Atoi(s)
	if err != nil || n < min || n > max {
		return def, fmt.Errorf("%s=%q: want a whole number from %d to %d", k, s, min, max)
	}
	return n, nil
}

// Load builds the Config from the environment and validates it.
func Load() (Config, error) {
	c := Config{
		Listen:   env("SAMANOTE_LISTEN", ":8080"),
		NotesDir: env("SAMANOTE_NOTES", "/notes"),
		User:     env("SAMANOTE_USER", "admin"),
		Password: os.Getenv("SAMANOTE_PASSWORD"),
		Secret:   os.Getenv("SAMANOTE_SECRET"),
		TrashDir: env("SAMANOTE_TRASH", ".trash"),
	}
	for _, h := range strings.Split(os.Getenv("SAMANOTE_HIDDEN"), ",") {
		if h = strings.Trim(strings.TrimSpace(h), "/"); h != "" {
			c.Hidden = append(c.Hidden, h)
		}
	}
	var err error
	if c.AutosaveMS, err = envInt("SAMANOTE_AUTOSAVE_MS", 1000, 300, 60000); err != nil {
		return c, err
	}
	if c.MaxFileMB, err = envInt("SAMANOTE_MAX_FILE_MB", 5, 1, 100); err != nil {
		return c, err
	}
	if c.Password == "" {
		return c, fmt.Errorf("SAMANOTE_PASSWORD is not set (put it in .env — plain text or a hash from `samanote hash-password`)")
	}
	switch strings.ToLower(c.Password) {
	case "change-me", "changeme", "password", "admin", "secret":
		return c, fmt.Errorf("SAMANOTE_PASSWORD is still a placeholder (%q) — set a real one", c.Password)
	}
	if strings.Contains(c.TrashDir, "..") || strings.HasPrefix(c.TrashDir, "/") || c.TrashDir == "" {
		return c, fmt.Errorf("SAMANOTE_TRASH=%q must be a folder name inside the vault, e.g. .trash", c.TrashDir)
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
