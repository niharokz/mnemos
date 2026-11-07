// Package config reads the server settings from MNEMOSTXT_* environment
// variables (in the homelab: the shared .env, passed through docker-compose).
// Editor settings are not here: they live in <notes>/logs/mnemostxt/settings.json.
package config

import (
	"fmt"
	"os"
	"strconv"
	"strings"
)

// Defaults for the download links on the home page (GitLab release permalinks).
const releaseBase = "https://gitlab.com/niharokz/mnemostxt/-/releases/permalink/latest/downloads/"

type Config struct {
	Listen      string // MNEMOSTXT_LISTEN, default :8080
	Notes       string // MNEMOSTXT_NOTES, the notes folder (container: /notes)
	NotesName   string // MNEMOSTXT_NOTES_NAME, shown in the sidebar, default "notes"
	State       string // MNEMOSTXT_STATE, writable folder for bans (container: /state)
	User        string // MNEMOSTXT_USER
	Password    string // MNEMOSTXT_PASSWORD, plain text or a bcrypt hash
	Secret      string // MNEMOSTXT_SECRET, signs sessions; empty = random (sign-in ends on restart)
	MaxFileMB   int    // MNEMOSTXT_MAX_FILE_MB, largest file read or written, default 20
	BanAfter    int    // MNEMOSTXT_BAN_AFTER failed sign-ins in 15 min, default 5
	BanHours    int    // MNEMOSTXT_BAN_HOURS, default 24
	BanPrivate  bool   // MNEMOSTXT_BAN_PRIVATE, also ban LAN/Tailscale addresses, default false
	Hidden      []string
	DownloadAPK string // MNEMOSTXT_URL_APK
	DownloadEXE string // MNEMOSTXT_URL_EXE
	DownloadDEB string // MNEMOSTXT_URL_DEB
	Source      string // MNEMOSTXT_URL_SOURCE
}

// Load reads the environment through getenv (os.Getenv in production).
func Load(getenv func(string) string) (*Config, error) {
	get := func(k, def string) string {
		if v := strings.TrimSpace(getenv(k)); v != "" {
			return v
		}
		return def
	}
	num := func(k string, def, lo, hi int) (int, error) {
		v := get(k, "")
		if v == "" {
			return def, nil
		}
		n, err := strconv.Atoi(v)
		if err != nil || n < lo || n > hi {
			return 0, fmt.Errorf("%s must be a number from %d to %d", k, lo, hi)
		}
		return n, nil
	}
	c := &Config{
		Listen:      get("MNEMOSTXT_LISTEN", ":8080"),
		Notes:       get("MNEMOSTXT_NOTES", "/notes"),
		NotesName:   get("MNEMOSTXT_NOTES_NAME", "notes"),
		State:       get("MNEMOSTXT_STATE", "/state"),
		User:        get("MNEMOSTXT_USER", ""),
		Password:    getenv("MNEMOSTXT_PASSWORD"), // not trimmed: spaces may be part of it
		Secret:      get("MNEMOSTXT_SECRET", ""),
		BanPrivate:  get("MNEMOSTXT_BAN_PRIVATE", "false") == "true",
		Hidden:      []string{".git", ".trash", ".stfolder", ".stversions", ".obsidian", "node_modules"},
		DownloadAPK: get("MNEMOSTXT_URL_APK", releaseBase+"mnemostxt.apk"),
		DownloadEXE: get("MNEMOSTXT_URL_EXE", releaseBase+"mnemostxt-setup.exe"),
		DownloadDEB: get("MNEMOSTXT_URL_DEB", releaseBase+"mnemostxt.deb"),
		Source:      get("MNEMOSTXT_URL_SOURCE", "https://gitlab.com/niharokz/mnemostxt"),
	}
	var err error
	if c.MaxFileMB, err = num("MNEMOSTXT_MAX_FILE_MB", 20, 1, 200); err != nil {
		return nil, err
	}
	if c.BanAfter, err = num("MNEMOSTXT_BAN_AFTER", 5, 1, 100); err != nil {
		return nil, err
	}
	if c.BanHours, err = num("MNEMOSTXT_BAN_HOURS", 24, 0, 24*365); err != nil {
		return nil, err
	}
	if h := get("MNEMOSTXT_HIDDEN", ""); h != "" {
		for _, x := range strings.Split(h, ",") {
			if x = strings.TrimSpace(x); x != "" {
				c.Hidden = append(c.Hidden, x)
			}
		}
	}
	if c.User == "" || c.Password == "" {
		return nil, fmt.Errorf("set MNEMOSTXT_USER and MNEMOSTXT_PASSWORD")
	}
	return c, nil
}

// Getenv is os.Getenv, for main.
var Getenv = os.Getenv
