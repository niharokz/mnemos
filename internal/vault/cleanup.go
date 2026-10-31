package vault

import (
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
)

// CleanupReport says what Cleanup moved and deleted (or would, in a dry run).
type CleanupReport struct {
	DryRun    bool     `json:"dry_run"`
	Days      int      `json:"days"`
	Moved     []string `json:"moved"`      // conflict copies moved into the trash (vault paths)
	Deleted   []string `json:"deleted"`    // trash items deleted for good (names in the trash)
	FreedSize int64    `json:"freed_size"` // bytes the deleted items took
}

// Cleanup is the server's cleanupSync.sh, built in:
//  1. every Syncthing conflict copy (*sync-conflict*) outside the trash and hidden
//     folders is moved into the trash, like any other trashed file;
//  2. trash items older than days are deleted for good. An item's age is the time
//     it was trashed (from the ledger), or its modification time when unknown.
//
// This is the only place mnemos deletes anything. dryRun reports without changing.
func (v *Vault) Cleanup(days int, dryRun bool, now time.Time) (*CleanupReport, error) {
	if days < 1 {
		return nil, errors.New("keep trash for at least 1 day")
	}
	rep := &CleanupReport{DryRun: dryRun, Days: days, Moved: []string{}, Deleted: []string{}}
	v.mu.Lock()
	defer v.mu.Unlock()

	// 1. conflict copies → trash
	var conflicts []string
	err := filepath.WalkDir(v.root, func(abs string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil // unreadable folder: skip it, keep going
		}
		rel, _ := filepath.Rel(v.root, abs)
		rel = filepath.ToSlash(rel)
		if rel == "." {
			return nil
		}
		if d.IsDir() {
			if strings.HasPrefix(d.Name(), ".") || rel == v.trash {
				return filepath.SkipDir // .trash, .obsidian, .stversions, .git …
			}
			return nil
		}
		if d.Type().IsRegular() && strings.Contains(d.Name(), "sync-conflict") {
			conflicts = append(conflicts, rel)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	sort.Strings(conflicts)
	for _, rel := range conflicts {
		if !dryRun {
			if _, err := v.trashLocked(rel, filepath.Join(v.root, filepath.FromSlash(rel))); err != nil {
				return rep, err
			}
		}
		rep.Moved = append(rep.Moved, rel)
	}

	// 2. old trash items → deleted
	dir := filepath.Join(v.root, filepath.FromSlash(v.trash))
	ents, err := os.ReadDir(dir)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return rep, nil
		}
		return rep, perm(err, "read", v.trash)
	}
	led := v.ledger()
	cutoff := now.Add(-time.Duration(days) * 24 * time.Hour)
	changed := false
	for _, e := range ents {
		if e.Name() == ledgerName {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		when := info.ModTime()
		if l, ok := led[e.Name()]; ok {
			if sec, err := strconv.ParseInt(l[1], 10, 64); err == nil {
				when = time.Unix(sec, 0)
			}
		}
		if !when.Before(cutoff) {
			continue
		}
		size := treeSize(filepath.Join(dir, e.Name()))
		if !dryRun {
			if err := os.RemoveAll(filepath.Join(dir, e.Name())); err != nil {
				return rep, perm(err, "delete", v.trash+"/"+e.Name())
			}
			delete(led, e.Name())
			changed = true
		}
		rep.Deleted = append(rep.Deleted, e.Name())
		rep.FreedSize += size
	}
	if changed {
		v.ledgerWrite(led)
	}
	sort.Strings(rep.Deleted)
	return rep, nil
}

// treeSize is the total size of a file or folder.
func treeSize(abs string) int64 {
	var n int64
	filepath.WalkDir(abs, func(_ string, d fs.DirEntry, err error) error {
		if err == nil && d.Type().IsRegular() {
			if info, err := d.Info(); err == nil {
				n += info.Size()
			}
		}
		return nil
	})
	return n
}

// Stats counts what the vault holds: notes (.md), all files and their size, and
// the trash. Hidden folders other than the trash are not counted.
type Stats struct {
	Notes      int   `json:"notes"`
	Files      int   `json:"files"`
	Folders    int   `json:"folders"`
	Bytes      int64 `json:"bytes"`
	TrashItems int   `json:"trash_items"`
	TrashBytes int64 `json:"trash_bytes"`
	Conflicts  int   `json:"conflicts"`
}

// Stats walks the vault once.
func (v *Vault) Stats() (*Stats, error) {
	st := &Stats{}
	err := filepath.WalkDir(v.root, func(abs string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		rel, _ := filepath.Rel(v.root, abs)
		rel = filepath.ToSlash(rel)
		if rel == "." {
			return nil
		}
		if d.IsDir() {
			if rel == v.trash {
				ents, _ := os.ReadDir(abs)
				for _, e := range ents {
					if e.Name() != ledgerName {
						st.TrashItems++
						st.TrashBytes += treeSize(filepath.Join(abs, e.Name()))
					}
				}
				return filepath.SkipDir
			}
			if strings.HasPrefix(d.Name(), ".") || v.hidden[rel] || v.hidden[d.Name()] {
				return filepath.SkipDir
			}
			st.Folders++
			return nil
		}
		if strings.HasPrefix(d.Name(), ".") || !d.Type().IsRegular() {
			return nil
		}
		info, err := d.Info()
		if err != nil {
			return nil
		}
		st.Files++
		st.Bytes += info.Size()
		if strings.EqualFold(filepath.Ext(d.Name()), ".md") {
			st.Notes++
		}
		if strings.Contains(d.Name(), "sync-conflict") {
			st.Conflicts++
		}
		return nil
	})
	return st, err
}
