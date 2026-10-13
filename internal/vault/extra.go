package vault

import (
	"bufio"
	"errors"
	"io/fs"
	"log"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

// ledgerName is the trash ledger inside the trash folder: one line per trashed
// item, "name<TAB>original path<TAB>unix time". It is a dotfile, so it is hidden.
const ledgerName = ".mnemos-trash.tsv"

func (v *Vault) ledgerPath() string {
	return filepath.Join(v.root, filepath.FromSlash(v.trash), ledgerName)
}

// ledger reads the trash ledger (missing file = empty).
func (v *Vault) ledger() map[string][2]string {
	out := map[string][2]string{}
	f, err := os.Open(v.ledgerPath())
	if err != nil {
		return out
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		parts := strings.Split(sc.Text(), "\t")
		if len(parts) == 3 {
			out[parts[0]] = [2]string{parts[1], parts[2]}
		}
	}
	return out
}

// ledgerWrite rewrites the ledger without the given names (best effort: the trash
// works without it, Restore then just asks where to put the file).
func (v *Vault) ledgerWrite(m map[string][2]string) {
	var b strings.Builder
	names := make([]string, 0, len(m))
	for n := range m {
		names = append(names, n)
	}
	sort.Strings(names)
	for _, n := range names {
		b.WriteString(n + "\t" + m[n][0] + "\t" + m[n][1] + "\n")
	}
	if err := atomicWrite(v.ledgerPath(), []byte(b.String()), 0o644); err != nil {
		log.Printf("WARN trash ledger: %v", err)
	}
}

func (v *Vault) ledgerAdd(name, original string) {
	m := v.ledger()
	m[name] = [2]string{original, strconv.FormatInt(time.Now().Unix(), 10)}
	v.ledgerWrite(m)
}

// TrashItem is one entry of the trash folder.
type TrashItem struct {
	Name     string `json:"name"`
	Dir      bool   `json:"dir,omitempty"`
	Size     int64  `json:"size"`
	Mtime    int64  `json:"mtime"`              // unix ms, when it was trashed (or last changed)
	Original string `json:"original,omitempty"` // where it came from, when known
}

// ListTrash lists the trash folder, newest first.
func (v *Vault) ListTrash() ([]TrashItem, error) {
	dir := filepath.Join(v.root, filepath.FromSlash(v.trash))
	ents, err := os.ReadDir(dir)
	if errors.Is(err, fs.ErrNotExist) {
		return []TrashItem{}, nil
	}
	if err != nil {
		return nil, err
	}
	led := v.ledger()
	out := []TrashItem{}
	for _, e := range ents {
		if e.Name() == ledgerName || strings.HasPrefix(e.Name(), ".samanote-") || strings.HasPrefix(e.Name(), ".mnemos-") {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		it := TrashItem{Name: e.Name(), Dir: info.IsDir(), Size: info.Size(), Mtime: info.ModTime().UnixMilli()}
		if l, ok := led[e.Name()]; ok {
			it.Original = l[0]
			if t, err := strconv.ParseInt(l[1], 10, 64); err == nil {
				it.Mtime = t * 1000
			}
		}
		out = append(out, it)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Mtime > out[j].Mtime })
	return out, nil
}

// Restore moves a trash entry back to the vault path to (never overwriting).
func (v *Vault) Restore(name, to string) (string, error) {
	if name == "" || strings.ContainsAny(name, "/\\\x00") || name == ledgerName || strings.HasPrefix(name, "..") {
		return "", ErrBadPath
	}
	src := filepath.Join(v.root, filepath.FromSlash(v.trash), name)
	if _, err := os.Lstat(src); err != nil {
		return "", notFound(err)
	}
	tc, tabs, err := v.Resolve(to)
	if err != nil {
		return "", err
	}
	if tc == "" {
		return "", ErrBadPath
	}
	v.mu.Lock()
	defer v.mu.Unlock()
	if _, err := os.Lstat(tabs); err == nil {
		return "", ErrExists
	}
	if err := os.MkdirAll(filepath.Dir(tabs), 0o755); err != nil {
		return "", perm(err, "restore", tc)
	}
	if err := os.Rename(src, tabs); err != nil {
		return "", perm(err, "restore", tc)
	}
	m := v.ledger()
	if _, ok := m[name]; ok {
		delete(m, name)
		v.ledgerWrite(m)
	}
	return tc, nil
}

// Append adds text to the end of a note, creating it (and its folders) if needed.
// A newline is put in between when the note does not end with one. Used by quick
// capture; like Write it is atomic and keeps the file's mode and line endings.
func (v *Vault) Append(rel, text string) (string, error) {
	clean, abs, err := v.Resolve(rel)
	if err != nil {
		return "", err
	}
	if clean == "" {
		return "", ErrBadPath
	}
	v.mu.Lock()
	defer v.mu.Unlock()
	mode := fs.FileMode(0o644)
	cur, err := os.ReadFile(abs)
	switch {
	case err == nil:
		if info, err := os.Stat(abs); err == nil {
			mode = info.Mode().Perm()
		}
	case errors.Is(err, fs.ErrNotExist):
		if err := os.MkdirAll(filepath.Dir(abs), 0o755); err != nil {
			return "", perm(err, "create", clean)
		}
	default:
		return "", perm(err, "read", clean)
	}
	if !utf8.Valid(cur) {
		return "", ErrBadPath
	}
	nl := "\n"
	if strings.Contains(string(cur), "\r\n") {
		nl = "\r\n"
		text = strings.ReplaceAll(text, "\n", "\r\n")
	}
	out := string(cur)
	if out != "" && !strings.HasSuffix(out, "\n") {
		out += nl
	}
	out += text + nl
	if int64(len(out)) > v.maxBytes {
		return "", ErrTooLarge
	}
	if err := atomicWrite(abs, []byte(out), mode); err != nil {
		if errors.Is(err, fs.ErrPermission) && cur != nil {
			err = inPlaceWrite(abs, []byte(out))
		}
		if err != nil {
			return "", perm(err, "save", clean)
		}
	}
	return clean, nil
}

// conflictRe matches Syncthing's conflict copies: name.sync-conflict-YYYYMMDD-HHMMSS-DEVICE.ext
var conflictRe = regexp.MustCompile(`^(.*)\.sync-conflict-(\d{8})-(\d{6})-([A-Z0-9]{7})(\.[^./]*)?$`)

// Conflict is a Syncthing conflict copy and the file it belongs to.
type Conflict struct {
	Copy     string `json:"copy"`     // vault path of the conflict copy
	Original string `json:"original"` // vault path of the file it conflicts with
	Exists   bool   `json:"exists"`   // whether the original is still there
	When     string `json:"when"`     // "2026-10-12 09:30:00"
	Device   string `json:"device"`   // Syncthing device id prefix
}

// OriginalOf returns the original's path for a conflict copy, or false.
func OriginalOf(p string) (string, bool) {
	dir, base := path.Split(p)
	m := conflictRe.FindStringSubmatch(base)
	if m == nil {
		return "", false
	}
	return dir + m[1] + m[5], true
}

// Conflicts lists every Syncthing conflict copy in the vault.
func (v *Vault) Conflicts() ([]Conflict, error) {
	t, err := v.Tree()
	if err != nil {
		return nil, err
	}
	out := []Conflict{}
	var walk func(n *Node)
	walk = func(n *Node) {
		for _, c := range n.Children {
			if c.Dir {
				walk(c)
				continue
			}
			m := conflictRe.FindStringSubmatch(c.Name)
			if m == nil {
				continue
			}
			orig, _ := OriginalOf(c.Path)
			_, abs, err := v.Resolve(orig)
			_, statErr := os.Stat(abs)
			d, tm := m[2], m[3]
			out = append(out, Conflict{Copy: c.Path, Original: orig, Exists: err == nil && statErr == nil, Device: m[4],
				When: d[:4] + "-" + d[4:6] + "-" + d[6:] + " " + tm[:2] + ":" + tm[2:4] + ":" + tm[4:]})
		}
	}
	walk(t)
	sort.Slice(out, func(i, j int) bool { return out[i].When > out[j].When })
	return out, nil
}

// ResolveConflict settles a conflict copy. keep is "original" (the copy goes to
// the trash), "copy" (the original goes to the trash and the copy takes its name)
// or "merged" (content is written over the original, the copy goes to the trash).
// Nothing is deleted: the losing side is always in the trash.
func (v *Vault) ResolveConflict(copyPath, keep, content string) (string, error) {
	orig, ok := OriginalOf(copyPath)
	if !ok {
		return "", ErrBadPath
	}
	cc, cabs, err := v.Resolve(copyPath)
	if err != nil {
		return "", err
	}
	oc, oabs, err := v.Resolve(orig)
	if err != nil {
		return "", err
	}
	if _, err := os.Stat(cabs); err != nil {
		return "", notFound(err)
	}
	switch keep {
	case "original":
		v.mu.Lock()
		defer v.mu.Unlock()
		_, err := v.trashLocked(cc, cabs)
		return oc, err
	case "copy":
		v.mu.Lock()
		defer v.mu.Unlock()
		if _, err := os.Stat(oabs); err == nil {
			if _, err := v.trashLocked(oc, oabs); err != nil {
				return "", err
			}
		}
		if err := os.Rename(cabs, oabs); err != nil {
			return "", perm(err, "rename", cc)
		}
		return oc, nil
	case "merged":
		if _, err := v.Write(oc, content, "", true); err != nil {
			return "", err
		}
		v.mu.Lock()
		defer v.mu.Unlock()
		_, err := v.trashLocked(cc, cabs)
		return oc, err
	}
	return "", ErrBadPath
}

// Card is one entry of a folder's box view.
type Card struct {
	Name    string `json:"name"`
	Path    string `json:"path"`
	Dir     bool   `json:"dir,omitempty"`
	Size    int64  `json:"size,omitempty"`
	Mtime   int64  `json:"mtime,omitempty"`
	Preview string `json:"preview,omitempty"` // first lines of a text file, without front matter
	Items   int    `json:"items,omitempty"`   // entries in a sub-folder
}

// Folder lists one folder with short previews of its text files (at most 60 previews).
func (v *Vault) Folder(rel string) ([]Card, error) {
	clean, abs, err := v.Resolve(rel)
	if err != nil {
		return nil, err
	}
	info, err := os.Stat(abs)
	if err != nil {
		return nil, notFound(err)
	}
	if !info.IsDir() {
		return nil, ErrBadPath
	}
	t := &Node{Path: clean, Dir: true}
	if err := v.walkOne(t, abs); err != nil {
		return nil, err
	}
	out := []Card{}
	previews := 0
	for _, c := range t.Children {
		card := Card{Name: c.Name, Path: c.Path, Dir: c.Dir, Size: c.Size, Mtime: c.Mtime}
		cabs := filepath.Join(v.root, filepath.FromSlash(c.Path))
		if c.Dir {
			if ents, err := os.ReadDir(cabs); err == nil {
				for _, e := range ents {
					if !strings.HasPrefix(e.Name(), ".") {
						card.Items++
					}
				}
			}
		} else if previews < 60 && c.Size > 0 {
			card.Preview = preview(cabs)
			if card.Preview != "" {
				previews++
			}
		}
		out = append(out, card)
	}
	return out, nil
}

// walkOne lists a single folder level (no recursion), with the same rules as Tree.
func (v *Vault) walkOne(n *Node, dir string) error {
	full := &Node{Path: n.Path, Dir: true}
	if err := v.walk(full, dir); err != nil {
		return err
	}
	for _, c := range full.Children {
		c.Children = nil
	}
	n.Children = full.Children
	return nil
}

// preview returns the first ~280 characters of a UTF-8 text file, front matter skipped.
func preview(abs string) string {
	f, err := os.Open(abs)
	if err != nil {
		return ""
	}
	defer f.Close()
	buf := make([]byte, 2048)
	n, _ := f.Read(buf)
	b := buf[:n]
	for len(b) > 0 && !utf8.Valid(b) { // cut a split multi-byte rune at the end
		b = b[:len(b)-1]
	}
	if len(b) == 0 || strings.IndexByte(string(b), 0) >= 0 {
		return ""
	}
	s := strings.ReplaceAll(string(b), "\r\n", "\n")
	if strings.HasPrefix(s, "---\n") {
		if i := strings.Index(s[4:], "\n---"); i >= 0 {
			s = strings.TrimLeft(s[4+i+4:], "\n")
		}
	}
	s = strings.TrimSpace(s)
	if r := []rune(s); len(r) > 280 {
		s = string(r[:280]) + "…"
	}
	return s
}
