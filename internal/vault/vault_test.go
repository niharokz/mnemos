package vault

import (
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func newVault(t *testing.T) (*Vault, string) {
	t.Helper()
	dir := t.TempDir()
	v, err := New(dir, ".trash", []string{"private", "work/secret"}, 1<<20)
	if err != nil {
		t.Fatal(err)
	}
	return v, dir
}

func write(t *testing.T, dir, rel, s string) {
	t.Helper()
	p := filepath.Join(dir, filepath.FromSlash(rel))
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(p, []byte(s), 0o644); err != nil {
		t.Fatal(err)
	}
}

func TestCleanRejectsEscapesAndHidden(t *testing.T) {
	v, _ := newVault(t)
	ok := map[string]string{
		"a.md": "a.md", "/a//b/../c.md": "a/c.md", "": "", "notes/x y.md": "notes/x y.md",
		"../../etc/passwd": "etc/passwd", // cleaned against the root: cannot climb out
	}
	for in, want := range ok {
		got, err := v.Clean(in)
		if err != nil || got != want {
			t.Errorf("Clean(%q) = %q, %v; want %q", in, got, err, want)
		}
	}
	bad := map[string]error{
		".obsidian/app.json": ErrHidden, "a/.git/config": ErrHidden, ".trash/x.md": ErrHidden,
		"private/x.md": ErrHidden, "work/secret/x.md": ErrHidden, "a\x00b": ErrBadPath, `a\b.md`: ErrBadPath,
	}
	for in, want := range bad {
		if _, err := v.Clean(in); !errors.Is(err, want) {
			t.Errorf("Clean(%q) err = %v; want %v", in, err, want)
		}
	}
	if _, err := v.Clean("work/other.md"); err != nil {
		t.Errorf("work/other.md should be allowed: %v", err)
	}
}

func TestSymlinkOutsideIsRefused(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("symlinks need privileges on Windows")
	}
	v, dir := newVault(t)
	outside := t.TempDir()
	write(t, outside, "secret.md", "nope")
	if err := os.Symlink(outside, filepath.Join(dir, "link")); err != nil {
		t.Fatal(err)
	}
	if _, err := v.Read("link/secret.md"); !errors.Is(err, ErrBadPath) {
		t.Fatalf("read through symlink: err = %v, want ErrBadPath", err)
	}
	if _, err := v.Create("link/new.md", false); !errors.Is(err, ErrBadPath) {
		t.Fatalf("create through symlink: err = %v, want ErrBadPath", err)
	}
	tree, _ := v.Tree()
	for _, c := range tree.Children {
		if c.Name == "link" {
			t.Fatal("symlink to outside folder listed in tree")
		}
	}
}

func TestTreeOrderAndHidden(t *testing.T) {
	v, dir := newVault(t)
	for _, f := range []string{"b.md", "A.md", "note 10.md", "note 2.md", "zdir/x.md", "adir/y.md", ".obsidian/app.json", "private/p.md"} {
		write(t, dir, f, "x")
	}
	tree, err := v.Tree()
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, c := range tree.Children {
		names = append(names, c.Name)
	}
	want := "adir zdir A.md b.md note 2.md note 10.md"
	if got := strings.Join(names, " "); got != want {
		t.Fatalf("tree = %q, want %q", got, want)
	}
	if tree.Children[0].Children[0].Path != "adir/y.md" {
		t.Fatalf("child path = %q", tree.Children[0].Children[0].Path)
	}
}

func TestWriteConflictAndForce(t *testing.T) {
	v, dir := newVault(t)
	write(t, dir, "n.md", "one")
	f, err := v.Read("n.md")
	if err != nil {
		t.Fatal(err)
	}
	ver, err := v.Write("n.md", "two", f.Version, false)
	if err != nil {
		t.Fatal(err)
	}
	// another app edits the file
	write(t, dir, "n.md", "from obsidian")
	_, err = v.Write("n.md", "three", ver, false)
	var ce *ConflictError
	if !errors.As(err, &ce) || ce.Current != "from obsidian" || ce.Deleted {
		t.Fatalf("want conflict with disk content, got %v", err)
	}
	if _, err := v.Write("n.md", "three", ver, true); err != nil {
		t.Fatalf("force: %v", err)
	}
	if b, _ := os.ReadFile(filepath.Join(dir, "n.md")); string(b) != "three" {
		t.Fatalf("content = %q", b)
	}
	// deleted meanwhile
	os.Remove(filepath.Join(dir, "n.md"))
	if _, err := v.Write("n.md", "four", Version([]byte("three")), false); !errors.As(err, &ce) || !ce.Deleted {
		t.Fatalf("want deleted conflict, got %v", err)
	}
}

func TestWriteKeepsModeAndLeavesNoTemp(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("unix permissions")
	}
	v, dir := newVault(t)
	write(t, dir, "m.md", "a")
	os.Chmod(filepath.Join(dir, "m.md"), 0o600)
	if _, err := v.Write("m.md", "b", Version([]byte("a")), false); err != nil {
		t.Fatal(err)
	}
	info, _ := os.Stat(filepath.Join(dir, "m.md"))
	if info.Mode().Perm() != 0o600 {
		t.Fatalf("mode = %v", info.Mode().Perm())
	}
	ents, _ := os.ReadDir(dir)
	for _, e := range ents {
		if strings.HasPrefix(e.Name(), ".samanote-") {
			t.Fatalf("temp file left behind: %s", e.Name())
		}
	}
}

func TestWriteTooLarge(t *testing.T) {
	v, dir := newVault(t)
	write(t, dir, "big.md", "")
	if _, err := v.Write("big.md", strings.Repeat("x", 2<<20), Version(nil), false); !errors.Is(err, ErrTooLarge) {
		t.Fatalf("err = %v", err)
	}
}

func TestReadBinaryAndReadOnly(t *testing.T) {
	v, dir := newVault(t)
	write(t, dir, "img.png", "\x89PNG\x00\x01")
	f, err := v.Read("img.png")
	if err != nil || !f.Binary || f.Content != "" {
		t.Fatalf("binary: %+v %v", f, err)
	}
	write(t, dir, "large.md", strings.Repeat("a", 1<<20+10))
	f, err = v.Read("large.md")
	if err != nil || !f.ReadOnly || f.Content == "" {
		t.Fatalf("large read-only: readonly=%v len=%d err=%v", f.ReadOnly, len(f.Content), err)
	}
	if _, err := v.Read("nope.md"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("missing: %v", err)
	}
}

func TestCreate(t *testing.T) {
	v, dir := newVault(t)
	p, err := v.Create("journal/2026/today", false)
	if err != nil || p != "journal/2026/today.md" {
		t.Fatalf("create = %q, %v", p, err)
	}
	if _, err := os.Stat(filepath.Join(dir, "journal", "2026", "today.md")); err != nil {
		t.Fatal(err)
	}
	if _, err := v.Create("journal/2026/today.md", false); !errors.Is(err, ErrExists) {
		t.Fatalf("duplicate: %v", err)
	}
	if p, err := v.Create("list.txt", false); err != nil || p != "list.txt" {
		t.Fatalf("keeps extension: %q %v", p, err)
	}
	if p, err := v.Create("projects", true); err != nil || p != "projects" {
		t.Fatalf("folder: %q %v", p, err)
	}
	if _, err := v.Create(".obsidian/x.md", false); !errors.Is(err, ErrHidden) {
		t.Fatalf("hidden: %v", err)
	}
}

func TestMove(t *testing.T) {
	v, dir := newVault(t)
	write(t, dir, "a.md", "A")
	write(t, dir, "b.md", "B")
	if _, err := v.Move("a.md", "b.md"); !errors.Is(err, ErrExists) {
		t.Fatalf("overwrite: %v", err)
	}
	p, err := v.Move("a.md", "sub/renamed")
	if err != nil || p != "sub/renamed.md" {
		t.Fatalf("move = %q, %v", p, err)
	}
	if b, _ := os.ReadFile(filepath.Join(dir, "sub", "renamed.md")); string(b) != "A" {
		t.Fatal("content lost")
	}
	if _, err := v.Move("sub", "sub/inner"); !errors.Is(err, ErrBadPath) {
		t.Fatalf("into itself: %v", err)
	}
	if p, err := v.Move("sub", "folder"); err != nil || p != "folder" {
		t.Fatalf("folder move: %q %v", p, err)
	}
}

func TestTrash(t *testing.T) {
	v, dir := newVault(t)
	write(t, dir, "x/n.md", "1")
	write(t, dir, "y/n.md", "2")
	p1, err := v.Trash("x/n.md")
	if err != nil || p1 != ".trash/n.md" {
		t.Fatalf("trash 1 = %q %v", p1, err)
	}
	p2, err := v.Trash("y/n.md")
	if err != nil || p2 != ".trash/n 2.md" {
		t.Fatalf("trash 2 = %q %v", p2, err)
	}
	if b, _ := os.ReadFile(filepath.Join(dir, ".trash", "n 2.md")); string(b) != "2" {
		t.Fatal("trashed content lost")
	}
	if _, err := os.Stat(filepath.Join(dir, "y", "n.md")); !os.IsNotExist(err) {
		t.Fatal("original still there")
	}
	if p, err := v.Trash("x"); err != nil || p != ".trash/x" {
		t.Fatalf("trash folder = %q %v", p, err)
	}
	if _, err := v.Trash(""); !errors.Is(err, ErrBadPath) {
		t.Fatalf("trash root: %v", err)
	}
}

func TestNaturalLess(t *testing.T) {
	cases := [][2]string{{"a2", "a10"}, {"2026-01", "2026-02"}, {"a", "b"}, {"x", "x1"}}
	for _, c := range cases {
		if !naturalLess(c[0], c[1]) || naturalLess(c[1], c[0]) {
			t.Errorf("order %q < %q failed", c[0], c[1])
		}
	}
}
