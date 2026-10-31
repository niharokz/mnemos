// Command mnemos serves the Mnemos web app for a folder of text notes (an
// Obsidian vault): markdown with live preview, CSV tables, search, capture,
// Syncthing conflict merge, trash with restore, and a read-only demo user.
//
//	mnemos serve            run the web app (default)
//	mnemos hash-password    print a bcrypt hash for MNEMOS_PASSWORD
//	mnemos check            validate the settings and find files mnemos cannot write
//	mnemos health           exit 0 if the running server answers (Docker healthcheck)
//	mnemos bans             list the banned client addresses
//	mnemos unban <ip>       lift a ban (the running server picks it up within seconds)
//	mnemos version
package main

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"
	_ "time/tzdata" // zone data compiled in, so TZ works on minimal images

	"gitlab.com/niharokz/mnemos"
	"gitlab.com/niharokz/mnemos/internal/auth"
	"gitlab.com/niharokz/mnemos/internal/config"
	"gitlab.com/niharokz/mnemos/internal/demo"
	"gitlab.com/niharokz/mnemos/internal/guard"
	"gitlab.com/niharokz/mnemos/internal/httpx"
	"gitlab.com/niharokz/mnemos/internal/server"
	"gitlab.com/niharokz/mnemos/internal/vault"
)

// version comes from the VERSION file at the repository root.
var version = mnemos.Version()

const usage = `
usage: mnemos <command>

  serve            run the web app (default)
  hash-password    print a bcrypt hash to use as MNEMOS_PASSWORD
  check            validate the settings and list notes/folders mnemos cannot write
  health           check that the running server answers
  bans             list the banned client addresses
  unban <ip>       lift a ban (takes effect in the running server within seconds)
  version          print the version
`

func main() {
	log.SetFlags(log.Ldate | log.Ltime)
	_ = config.LoadDotEnv(".env")
	cmd := "serve"
	if len(os.Args) > 1 {
		cmd = os.Args[1]
	}
	var err error
	switch cmd {
	case "serve":
		err = serve()
	case "hash-password":
		err = hashPassword()
	case "check":
		err = check()
	case "health":
		err = health()
	case "bans":
		err = listBans()
	case "unban":
		if len(os.Args) < 3 {
			err = fmt.Errorf("usage: mnemos unban <ip>")
		} else {
			err = unban(os.Args[2])
		}
	case "version", "--version", "-v":
		fmt.Println("mnemos", version)
	case "help", "--help", "-h":
		fmt.Println(strings.TrimSpace(usage))
	default:
		err = fmt.Errorf("unknown command %q\n\n%s", cmd, strings.TrimSpace(usage))
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "mnemos:", err)
		os.Exit(1)
	}
}

func serve() error {
	cfg, err := config.Load()
	if err != nil {
		return err
	}
	v, err := vault.New(cfg.NotesDir, cfg.TrashDir, cfg.Hidden, cfg.MaxFileBytes())
	if err != nil {
		return err
	}
	accounts := []*auth.Account{{User: httpx.User{Name: cfg.User, Vault: v}, Password: cfg.Password}}
	if cfg.DemoEnabled {
		// The demo is optional: if its sample vault can't be unpacked (no writable temp
		// folder), log why and serve your notes without it instead of crash-looping.
		if dv, dir, err := buildDemo(cfg.MaxFileBytes()); err != nil {
			log.Printf("WARN demo user disabled: %v (mount a writable /tmp, e.g. tmpfs /tmp:size=32m,mode=1777, or set MNEMOS_DEMO=false)", err)
		} else {
			defer os.RemoveAll(dir)
			accounts = append(accounts, &auth.Account{User: httpx.User{Name: cfg.DemoUser, ReadOnly: true, Vault: dv},
				Password: cfg.DemoPassword, ShowHint: true})
		}
	}
	a := auth.New(cfg.Secret, accounts...)
	var g *guard.Guard
	if cfg.BanAfter > 0 {
		file := stateFile(cfg.StateDir)
		g = newGuard(file, cfg.BanAfter, cfg.BanDays, cfg.BanPrivate)
		a.Guard = g
		where := "in memory only"
		if file != "" {
			where = "kept in " + file
		}
		log.Printf("guard: %d failed sign-ins or scanner probes in 24 h ban an address for %d days (%d banned now, %s)",
			cfg.BanAfter, cfg.BanDays, len(g.List()), where)
	} else {
		log.Printf("WARN MNEMOS_BAN_AFTER=0 — repeated failures are only slowed down, never banned")
	}
	switch {
	case cfg.Secret == "":
		log.Printf("WARN MNEMOS_SECRET is empty — sign-ins will not survive a restart (make one with: openssl rand -hex 32)")
	case len(cfg.Secret) < 32:
		log.Printf("WARN MNEMOS_SECRET is short (%d characters) — use 32 or more: openssl rand -hex 32", len(cfg.Secret))
	}
	if !auth.IsHash(cfg.Password) {
		log.Printf("NOTE MNEMOS_PASSWORD is plain text — consider storing a bcrypt hash (mnemos hash-password)")
	}

	h := server.New(a, server.Options{Version: version, AutosaveMS: cfg.AutosaveMS,
		CaptureNote: cfg.CaptureNote, TemplatesDir: cfg.TemplatesDir, MaxUploadMB: cfg.MaxUploadMB, Guard: g,
		StateDir: stateDir(cfg.StateDir), ApkURL: cfg.ApkURL})
	srv := &http.Server{Addr: cfg.Listen, Handler: h, ReadHeaderTimeout: server.ReadHeaderTimeout,
		ReadTimeout: server.ReadTimeout, WriteTimeout: server.WriteTimeout, IdleTimeout: server.IdleTimeout,
		MaxHeaderBytes: server.MaxHeaderBytes, ErrorLog: log.New(os.Stderr, "http: ", log.LstdFlags)}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	errc := make(chan error, 1)
	go func() { errc <- srv.ListenAndServe() }()
	log.Printf("mnemos %s listening on %s — notes %s — users: %s", version, cfg.Listen, cfg.NotesDir, a.Describe())

	select {
	case err := <-errc:
		if !errors.Is(err, http.ErrServerClosed) {
			return err
		}
	case <-ctx.Done():
		log.Printf("shutting down")
		sctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		return srv.Shutdown(sctx)
	}
	return nil
}

// check validates the configuration and reports every folder and file in the
// vault that the current user (the container's PUID:PGID) may not write.
func check() error {
	cfg, err := config.Load()
	if err != nil {
		return err
	}
	v, err := vault.New(cfg.NotesDir, cfg.TrashDir, cfg.Hidden, cfg.MaxFileBytes())
	if err != nil {
		return err
	}
	fmt.Printf("mnemos %s — notes %s, running as uid %d gid %d\n", version, cfg.NotesDir, os.Getuid(), os.Getgid())
	fmt.Printf("user %s, password %s, session secret %s\n", cfg.User,
		map[bool]string{true: "bcrypt hash", false: "plain text"}[auth.IsHash(cfg.Password)],
		map[bool]string{true: "set", false: "NOT set (sign-ins end on restart)"}[cfg.Secret != ""])
	rep, err := v.Check(50)
	if err != nil {
		return err
	}
	fmt.Printf("%d folders, %d files\n", rep.Folders, rep.Files)
	if len(rep.ReadOnlyDirs)+len(rep.ReadOnlyFiles) == 0 {
		fmt.Println("✓ everything is writable")
		return nil
	}
	for _, d := range rep.ReadOnlyDirs {
		fmt.Println("  ✗ folder not writable:", d)
	}
	for _, f := range rep.ReadOnlyFiles {
		fmt.Println("  ✗ file not writable:  ", f)
	}
	return fmt.Errorf("some notes cannot be saved — on the host, give the vault to PUID:PGID: sudo chown -R %d:%d \"$NOTE_PATH\"", os.Getuid(), os.Getgid())
}

func hashPassword() error {
	fmt.Fprint(os.Stderr, "Password: ")
	pw, err := bufio.NewReader(os.Stdin).ReadString('\n')
	if err != nil && pw == "" {
		return fmt.Errorf("no password given")
	}
	pw = strings.TrimRight(pw, "\r\n")
	if len(pw) < 8 {
		return fmt.Errorf("use at least 8 characters")
	}
	h, err := auth.HashPassword(pw)
	if err != nil {
		return err
	}
	fmt.Println(h)
	fmt.Fprintln(os.Stderr, "Put it in .env in single quotes so $ is kept: MNEMOS_PASSWORD='"+h+"'")
	return nil
}

func health() error {
	addr := os.Getenv("MNEMOS_LISTEN")
	if addr == "" {
		addr = ":8080"
	}
	if strings.HasPrefix(addr, ":") {
		addr = "127.0.0.1" + addr
	}
	c := http.Client{Timeout: 5 * time.Second}
	resp, err := c.Get("http://" + addr + "/healthz")
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("health check returned %s", resp.Status)
	}
	return nil
}

// buildDemo unpacks the demo user's sample vault into a temporary folder.
func buildDemo(maxFileBytes int64) (*vault.Vault, string, error) {
	dir, err := demo.Build()
	if err != nil {
		return nil, "", fmt.Errorf("sample vault: %w", err)
	}
	dv, err := vault.New(dir, ".trash", nil, maxFileBytes)
	if err != nil {
		os.RemoveAll(dir)
		return nil, "", err
	}
	return dv, dir, nil
}

// stateFile is where bans are kept; "" when the folder can't be written (memory only).
func stateFile(dir string) string {
	if dir == "" {
		return ""
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		log.Printf("WARN state folder %s: %v — bans are kept in memory only", dir, err)
		return ""
	}
	f, err := os.CreateTemp(dir, ".probe-*")
	if err != nil {
		log.Printf("WARN state folder %s is not writable (%v) — bans are kept in memory only", dir, err)
		return ""
	}
	f.Close()
	os.Remove(f.Name())
	return filepath.Join(dir, "bans.json")
}

func newGuard(file string, after, days int, private bool) *guard.Guard {
	return guard.New(guard.Options{MaxStrikes: after, BanFor: time.Duration(days) * 24 * time.Hour,
		StateFile: file, BanPrivate: private})
}

func guardForCLI() (*guard.Guard, error) {
	dir := os.Getenv("MNEMOS_STATE")
	if dir == "" {
		dir = "/state"
	}
	file := filepath.Join(dir, "bans.json")
	if _, err := os.Stat(file); err != nil {
		return nil, fmt.Errorf("no bans recorded in %s yet", dir)
	}
	return newGuard(file, 5, 7, false), nil
}

func listBans() error {
	g, err := guardForCLI()
	if err != nil {
		return err
	}
	list := g.List()
	if len(list) == 0 {
		fmt.Println("no banned addresses")
		return nil
	}
	for _, b := range list {
		fmt.Printf("%-40s until %s  (%s)\n", b.IP, b.Until.Local().Format("2006-01-02 15:04"), b.Reason)
	}
	return nil
}

func unban(ip string) error {
	g, err := guardForCLI()
	if err != nil {
		return err
	}
	ok, err := g.Unban(strings.TrimSpace(ip))
	if err != nil {
		return err
	}
	if !ok {
		return fmt.Errorf("%s is not banned", ip)
	}
	fmt.Println("unbanned", ip)
	return nil
}

// stateDir is dir when it can be written, else "" (settings then last until restart).
func stateDir(dir string) string {
	if stateFile(dir) == "" {
		return ""
	}
	return dir
}
