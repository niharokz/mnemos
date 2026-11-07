package vault

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"

	"gitlab.com/niharokz/mnemostxt/server/internal/httpx"
)

func newVault(t *testing.T) *Vault {
	t.Helper()
	dir := t.TempDir()
	v, err := New(dir, 1<<20, []string{".git", ".trash"})
	if err != nil {
		t.Fatal(err)
	}
	return v
}

func code(err error) string {
	var he *httpx.Error
	if errors.As(err, &he) {
		return he.Code
	}
	return ""
}

func TestPathsStayInside(t *testing.T) {
	v := newVault(t)
	for _, bad := range []string{"../x", "a/../../x", "/etc/passwd/../../.."} {
		p, c, err := v.Abs(bad)
		if err != nil {
			continue
		}
		if c != "" && filepath.Dir(p) != v.Root && !isUnder(p, v.Root) {
			t.Fatalf("%q escaped to %s", bad, p)
		}
	}
	if _, _, err := v.Abs("a\\b"); code(err) != "INVALID" {
		t.Fatalf("backslash path accepted: %v", err)
	}
	outside := t.TempDir()
	if err := os.Symlink(outside, filepath.Join(v.Root, "link")); err != nil {
		t.Skip("symlinks not available")
	}
	if _, _, err := v.Abs("link/secret.txt"); code(err) != "DENIED" {
		t.Fatalf("symlink escape not refused: %v", err)
	}
	if _, err := v.Write("link/new.txt", []byte("x"), -1); code(err) != "DENIED" {
		t.Fatalf("write through escaping link: %v", err)
	}
}

func isUnder(p, root string) bool {
	rel, err := filepath.Rel(root, p)
	return err == nil && rel != ".." && !filepath.IsAbs(rel) && (len(rel) < 3 || rel[:3] != "../")
}

func TestWriteReadConflict(t *testing.T) {
	v := newVault(t)
	m1, err := v.Write("a.md", []byte("one"), -1)
	if err != nil {
		t.Fatal(err)
	}
	b, m, err := v.Read("a.md")
	if err != nil || string(b) != "one" || m != m1 {
		t.Fatalf("read %q %d %v", b, m, err)
	}
	if _, err := v.Write("a.md", []byte("two"), m1-5000); code(err) != "CONFLICT" {
		t.Fatalf("want CONFLICT, got %v", err)
	}
	if _, err := v.Write("a.md", []byte("two"), m1); err != nil {
		t.Fatal(err)
	}
	if _, err := v.Write("missing/a.md", []byte("x"), -1); code(err) != "NOT_FOUND" {
		t.Fatalf("write into a missing folder: %v", err)
	}
	if _, err := v.Write("big.md", make([]byte, 2<<20), -1); code(err) != "TOO_LARGE" {
		t.Fatalf("size limit: %v", err)
	}
	des, _ := os.ReadDir(v.Root)
	if len(des) != 1 {
		t.Fatalf("temp files left: %v", des)
	}
}

func TestCreateRenameTrashPurge(t *testing.T) {
	v := newVault(t)
	if err := v.MkdirAll("sub/deep"); err != nil {
		t.Fatal(err)
	}
	if _, err := v.Create("sub/a.md"); err != nil {
		t.Fatal(err)
	}
	if _, err := v.Create("sub/a.md"); code(err) != "EXISTS" {
		t.Fatalf("create twice: %v", err)
	}
	if err := v.Rename("sub/a.md", "b.md"); err != nil {
		t.Fatal(err)
	}
	if err := v.Trash("b.md"); err != nil {
		t.Fatal(err)
	}
	_, _ = v.Write("b.md", []byte("again"), -1)
	if err := v.Trash("b.md"); err != nil {
		t.Fatal(err)
	}
	for _, n := range []string{"b.md", "b (1).md"} {
		if _, err := os.Stat(filepath.Join(v.Root, TrashDir, n)); err != nil {
			t.Fatalf("%s not in trash", n)
		}
	}
	if err := v.Trash(""); code(err) != "INVALID" {
		t.Fatal("trashing the root must fail")
	}
	r, _ := v.Purge(30, time.Now())
	if r.Removed != 0 {
		t.Fatalf("purged fresh items: %+v", r)
	}
	r, _ = v.Purge(30, time.Now().Add(31*24*time.Hour))
	if r.Removed != 2 {
		t.Fatalf("purge: %+v", r)
	}
	st, _ := v.Stat("nope.md")
	if st != nil {
		t.Fatal("stat of missing file")
	}
}

func TestSearch(t *testing.T) {
	v := newVault(t)
	_ = v.MkdirAll(".git")
	_, _ = v.Write("a.md", []byte("hello world\nHello again"), -1)
	_, _ = v.Write(".git/x", []byte("hello"), -1)
	_, _ = v.Write("bin", []byte("hello\x00"), -1)
	hits, err := v.Search("hello", false, false, 100)
	if err != nil || len(hits) != 2 {
		t.Fatalf("hits %v %v", hits, err)
	}
	hits, _ = v.Search("Hello", false, true, 100)
	if len(hits) != 1 || hits[0].Line != 2 {
		t.Fatalf("case-sensitive %v", hits)
	}
	if _, err := v.Search("(", true, false, 10); code(err) != "INVALID" {
		t.Fatal("bad regex accepted")
	}
}
