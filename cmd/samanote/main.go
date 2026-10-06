// Command samanote is a small web editor for a folder of markdown notes (an
// Obsidian vault): a file tree, a CodeMirror editor with live preview, autosave
// with conflict detection, and trash instead of delete.
//
//	samanote serve            run the web app (default)
//	samanote hash-password    print a bcrypt hash for SAMANOTE_PASSWORD
//	samanote health           exit 0 if the running server answers (Docker healthcheck)
//	samanote version
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

	"gitlab.com/niharokz/samanote/internal/auth"
	"gitlab.com/niharokz/samanote/internal/config"
	"gitlab.com/niharokz/samanote/internal/server"
	"gitlab.com/niharokz/samanote/internal/vault"
)

// version is set at build time: -ldflags "-X main.version=2.0.0".
var version = "2.0.0"

const usage = `
usage: samanote <command>

  serve            run the web app (default)
  hash-password    print a bcrypt hash to use as SAMANOTE_PASSWORD
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
	case "health":
		err = health()
	case "version", "--version", "-v":
		fmt.Println("samanote", version)
	case "help", "--help", "-h":
		fmt.Println(strings.TrimSpace(usage))
	default:
		err = fmt.Errorf("unknown command %q\n\n%s", cmd, strings.TrimSpace(usage))
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "samanote:", err)
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
	a := auth.New(cfg.User, cfg.Password, cfg.Secret)
	if cfg.Secret == "" {
		log.Printf("WARN SAMANOTE_SECRET is empty — sign-ins will not survive a restart (make one with: openssl rand -hex 32)")
	}

	h := server.New(a, v, server.Options{Version: version, AutosaveMS: cfg.AutosaveMS})
	srv := &http.Server{Addr: cfg.Listen, Handler: h,
		ReadTimeout: server.ReadTimeout, WriteTimeout: server.WriteTimeout, IdleTimeout: server.IdleTimeout}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	errc := make(chan error, 1)
	go func() { errc <- srv.ListenAndServe() }()
	log.Printf("samanote %s listening on %s — notes %s — user %s", version, cfg.Listen, cfg.NotesDir, a.Describe())

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
	fmt.Fprintln(os.Stderr, "Put it in .env in single quotes so $ is kept: SAMANOTE_PASSWORD='"+h+"'")
	return nil
}

func health() error {
	addr := os.Getenv("SAMANOTE_LISTEN")
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
