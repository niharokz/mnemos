package demo

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestBuild(t *testing.T) {
	dir, err := Build()
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(dir)
	for _, p := range []string{"Welcome.md", "notes/todo.md", "templates/Daily.md", "logs/syncthing.log", "tables/expenses.csv"} {
		if _, err := os.Stat(filepath.Join(dir, p)); err != nil {
			t.Errorf("missing %s: %v", p, err)
		}
	}
	w, _ := os.ReadFile(filepath.Join(dir, "Welcome.md"))
	if !strings.Contains(string(w), "read-only") {
		t.Error("welcome note is not the web one")
	}
	csv, _ := os.ReadFile(filepath.Join(dir, "tables", "expenses.csv"))
	if n := strings.Count(string(csv), "\n"); n != 501 {
		t.Errorf("expenses.csv has %d lines, want 501", n)
	}
	log, _ := os.ReadFile(filepath.Join(dir, "logs", "syncthing.log"))
	if len(log) < 2_000_000 {
		t.Errorf("log is only %d bytes", len(log))
	}
	again, _ := Build() // deterministic: same bytes every start
	defer os.RemoveAll(again)
	csv2, _ := os.ReadFile(filepath.Join(again, "tables", "expenses.csv"))
	if string(csv) != string(csv2) {
		t.Error("demo data differs between builds")
	}
}
