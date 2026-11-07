// Command mnemostxt is the web server for the mnemostxt editor.
//
//	mnemostxt serve            run the server (default)
//	mnemostxt health           exit 0 when the local server answers (Docker HEALTHCHECK)
//	mnemostxt hash-password    read a password on stdin, print a bcrypt hash for .env
//	mnemostxt version
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

	"gitlab.com/niharokz/mnemostxt/server/internal/auth"
	"gitlab.com/niharokz/mnemostxt/server/internal/config"
	"gitlab.com/niharokz/mnemostxt/server/internal/server"
	"gitlab.com/niharokz/mnemostxt/server/internal/vault"
)

// version is set at build time: -ldflags "-X main.version=$(cat VERSION)".
var version = "dev"

func main() {
	cmd := "serve"
	if len(os.Args) > 1 {
		cmd = os.Args[1]
	}
	switch cmd {
	case "serve":
		serve()
	case "health":
		health()
	case "hash-password":
		hashPassword()
	case "version", "--version", "-v":
		fmt.Println(version)
	default:
		fmt.Fprintf(os.Stderr, "usage: mnemostxt [serve|health|hash-password|version]\n")
		os.Exit(2)
	}
}

func serve() {
	log.SetFlags(log.LstdFlags | log.LUTC)
	cfg, err := config.Load(config.Getenv)
	if err != nil {
		log.Fatalf("config: %v", err)
	}
	v, err := vault.New(cfg.Notes, int64(cfg.MaxFileMB)<<20, cfg.Hidden)
	if err != nil {
		log.Fatalf("notes: %v", err)
	}
	state := cfg.State
	if f, err := os.CreateTemp(state, ".probe"); err == nil {
		f.Close()
		os.Remove(f.Name())
	} else {
		log.Printf("state: %s is not writable (%v) — bans are kept in memory only", state, err)
		state = ""
	}
	a := auth.New(auth.Options{
		User: cfg.User, Password: cfg.Password, Secret: cfg.Secret,
		BanAfter: cfg.BanAfter, BanFor: time.Duration(cfg.BanHours) * time.Hour, BanPrivate: cfg.BanPrivate, StateDir: state,
	})
	srv := &http.Server{
		Addr:              cfg.Listen,
		Handler:           server.New(cfg, a, v, version),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       60 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       120 * time.Second,
		MaxHeaderBytes:    64 << 10,
	}
	go func() {
		log.Printf("mnemostxt %s listening on %s — user %s, notes folder %s", version, cfg.Listen, a.Describe(), cfg.NotesName)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("listen: %v", err)
		}
	}()
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	<-stop
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	_ = srv.Shutdown(ctx)
	log.Printf("stopped")
}

func health() {
	addr := os.Getenv("MNEMOSTXT_LISTEN")
	if addr == "" {
		addr = ":8080"
	}
	if strings.HasPrefix(addr, ":") {
		addr = "127.0.0.1" + addr
	}
	c := &http.Client{Timeout: 4 * time.Second}
	res, err := c.Get("http://" + addr + "/health")
	if err != nil || res.StatusCode != http.StatusOK {
		os.Exit(1)
	}
}

func hashPassword() {
	fmt.Fprint(os.Stderr, "password: ")
	line, err := bufio.NewReader(os.Stdin).ReadString('\n')
	if err != nil && line == "" {
		log.Fatal("no password given")
	}
	h, err := auth.HashPassword(strings.TrimRight(line, "\r\n"))
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println(h)
}
