package vault

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestCleanupMovesConflictsAndDeletesOldTrash(t *testing.T) {
	v, dir := newVault(t)
	write(t, dir, "a.md", "a")
	write(t, dir, "notes/b.sync-conflict-20261001-120000-ABCDEFG.md", "b")
	write(t, dir, ".stversions/c.sync-conflict-20261001-120000-ABCDEFG.md", "kept: hidden folder")
	// one old trash item (from the ledger) and one fresh one
	if _, err := v.Trash("a.md"); err != nil {
		t.Fatal(err)
	}
	write(t, dir, ".trash/old.md", "old")
	led := v.ledger()
	led["old.md"] = [2]string{"old.md", "1000"} // 1970: very old
	v.ledgerWrite(led)
	now := time.Now()

	rep, err := v.Cleanup(30, true, now)
	if err != nil || len(rep.Moved) != 1 || len(rep.Deleted) != 1 || rep.Deleted[0] != "old.md" {
		t.Fatalf("dry run: %+v %v", rep, err)
	}
	if _, err := os.Stat(filepath.Join(dir, "notes/b.sync-conflict-20261001-120000-ABCDEFG.md")); err != nil {
		t.Fatal("dry run changed files")
	}
	rep, err = v.Cleanup(30, false, now)
	if err != nil || len(rep.Moved) != 1 || len(rep.Deleted) != 1 || rep.FreedSize != 3 {
		t.Fatalf("run: %+v %v", rep, err)
	}
	if _, err := os.Stat(filepath.Join(dir, ".trash/old.md")); !os.IsNotExist(err) {
		t.Fatal("old trash item not deleted")
	}
	for _, p := range []string{".trash/a.md", ".trash/b.sync-conflict-20261001-120000-ABCDEFG.md", ".stversions/c.sync-conflict-20261001-120000-ABCDEFG.md"} {
		if _, err := os.Stat(filepath.Join(dir, p)); err != nil {
			t.Errorf("%s should exist: %v", p, err)
		}
	}
	if _, ok := v.ledger()["old.md"]; ok {
		t.Fatal("ledger still lists the deleted item")
	}
	if _, err := v.Cleanup(0, true, now); err == nil {
		t.Fatal("0 days must be refused")
	}
}

func TestStats(t *testing.T) {
	v, dir := newVault(t)
	write(t, dir, "a.md", "12345")
	write(t, dir, "d/b.md", "123")
	write(t, dir, "d/c.png", "xx")
	write(t, dir, ".obsidian/x.json", "hidden")
	write(t, dir, ".trash/t.md", "trash")
	write(t, dir, "e.sync-conflict-1.md", "c")
	st, err := v.Stats()
	if err != nil {
		t.Fatal(err)
	}
	if st.Notes != 3 || st.Files != 4 || st.Folders != 1 || st.Bytes != 11 || st.TrashItems != 1 || st.TrashBytes != 5 || st.Conflicts != 1 {
		t.Fatalf("%+v", st)
	}
}
