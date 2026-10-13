// Package demo builds the demo user's vault: the Android app's sample vault plus
// the same generated big files (a ~2 MB log and a 500-row CSV), written to a fresh
// temporary folder on every start. The demo user is read-only, so it never changes.
package demo

import (
	"fmt"
	"io/fs"
	"math/rand"
	"os"
	"path/filepath"
	"strings"

	"gitlab.com/niharokz/mnemos"
)

const root = "android/demo-vault"

// welcome replaces the Android welcome note, which talks about phone-only settings.
const welcome = `---
title: Welcome
tags: [mnemos, demo]
---
# Welcome to Mnemos

This is the **demo user** on the web. It is **read-only**: type, tick and preview as much as you like, but nothing is saved. Sign in with your own account to edit your notes.

- Notes are plain files. Open the **Markdown tour** to see every feature.
- Switch **Edit / Split / Preview** at the top of a note.
- ` + "`tables/expenses.csv`" + ` opens as a real table: sort, filter, column stats.
- ` + "`logs/syncthing.log`" + ` is about 2 MB and opens at its end.
- ` + "`notes/todo.md`" + ` has a Syncthing **conflict** copy next to it — see **Conflicts** in the sidebar.

## Try these
- [ ] Search for ` + "`#garden`" + ` or ` + "`status: open`" + ` (Ctrl+K)
- [ ] Open **Cards** to see a folder as boxes with previews
- [ ] Compare the conflict copy and try **Merge by hand**
- [ ] Look at the Android app on the [about page](/)
- [x] Open the demo
`

// Build writes a fresh demo vault into a new temporary folder and returns its path.
func Build() (string, error) {
	dir, err := os.MkdirTemp("", "mnemos-demo-")
	if err != nil {
		return "", err
	}
	err = fs.WalkDir(mnemos.DemoVault, root, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		rel := strings.TrimPrefix(strings.TrimPrefix(p, root), "/")
		dst := filepath.Join(dir, filepath.FromSlash(rel))
		if d.IsDir() {
			return os.MkdirAll(dst, 0o755)
		}
		b, err := fs.ReadFile(mnemos.DemoVault, p)
		if err != nil {
			return err
		}
		if rel == "Welcome.md" {
			b = []byte(welcome)
		}
		return os.WriteFile(dst, b, 0o644)
	})
	if err != nil {
		return "", err
	}
	if err := generate(dir); err != nil {
		return "", err
	}
	return dir, nil
}

// generate makes the same deterministic big files as the Android build.
func generate(dir string) error {
	rnd := rand.New(rand.NewSource(42))
	levels := []string{"INFO", "INFO", "INFO", "DEBUG", "WARN", "ERROR"}
	var log strings.Builder
	for i := 0; log.Len() < 2_100_000; i++ {
		lvl := levels[rnd.Intn(len(levels))]
		fmt.Fprintf(&log, "2026-10-%02d %02d:%02d:%02d %-5s sync[%d] ", 1+i/40000, (i/1500)%24, (i/25)%60, i%60, lvl, 1000+rnd.Intn(9000))
		if lvl == "ERROR" {
			fmt.Fprintf(&log, "failed to sync folder notes: connection reset (attempt %d)\n", i%7)
		} else {
			fmt.Fprintf(&log, "scanned %d files in %d ms\n", rnd.Intn(5000), rnd.Intn(900))
		}
	}
	if err := os.MkdirAll(filepath.Join(dir, "logs"), 0o755); err != nil {
		return err
	}
	if err := os.WriteFile(filepath.Join(dir, "logs", "syncthing.log"), []byte(log.String()), 0o644); err != nil {
		return err
	}
	var csv strings.Builder
	csv.WriteString("date,item,category,amount,paid_by,note\n")
	items := []string{"Tea", "Rice, basmati", "Bus pass", "Running shoes", "Coffee beans", "Books", "Vegetables", "Internet"}
	cats := []string{"Food", "Travel", "Fitness", "Home", "Learning"}
	for r := 1; r <= 500; r++ {
		item := items[rnd.Intn(len(items))]
		if strings.Contains(item, ",") {
			item = `"` + item + `"`
		}
		paid, note := "cash", ""
		if r%3 == 0 {
			paid = "card"
		}
		if r%50 == 0 {
			note = `"bulk ""deal"""`
		}
		fmt.Fprintf(&csv, "2026-%02d-%02d,%s,%s,%d.%02d,%s,%s\n", 1+r%12, 1+r%28, item, cats[rnd.Intn(len(cats))], rnd.Intn(2000), rnd.Intn(100), paid, note)
	}
	return os.WriteFile(filepath.Join(dir, "tables", "expenses.csv"), []byte(csv.String()), 0o644)
}
