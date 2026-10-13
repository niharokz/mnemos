// Command mnemos serves the Mnemos web app for a folder of text notes (an
// Obsidian vault): markdown with live preview, CSV tables, search, capture,
// Syncthing conflict merge, trash with restore, and a read-only demo user.
//
//	mnemos serve            run the web app (default)
//	mnemos hash-password    print a bcrypt hash for MNEMOS_PASSWORD
//	mnemos check            validate the settings and find files mnemos cannot write
//	mnemos health           exit 0 if the running server answers (Docker healthcheck)
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
	"strings"
	"syscall"
	"time"
	_ "time/tzdata" // zone data compiled in, so TZ works on minimal images

	"gitlab.com/niharokz/mnemos"
	"gitlab.com/niharokz/mnemos/internal/auth"
	"gitlab.com/niharokz/mnemos/internal/config"
	"gitlab.com/niharokz/mnemos/internal/demo"
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
		dir, err := demo.Build()
		if err != nil {
			return fmt.Errorf("demo vault: %w", err)
		}
		defer os.RemoveAll(dir)
		dv, err := vault.New(dir, ".trash", nil, cfg.MaxFileBytes())
		if err != nil {
			return err
		}
		accounts = append(accounts, &auth.Account{User: httpx.User{Name: cfg.DemoUser, ReadOnly: true, Vault: dv},
			Password: cfg.DemoPassword, ShowHint: true})
	}
	a := auth.New(cfg.Secret, accounts...)
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
		CaptureNote: cfg.CaptureNote, TemplatesDir: cfg.TemplatesDir, MaxUploadMB: cfg.MaxUploadMB})
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
