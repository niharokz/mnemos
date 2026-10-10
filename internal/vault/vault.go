// Package vault is samanote's only way to touch the notes folder. Every path from
// the browser goes through Resolve, which keeps it inside the vault, hides dot
// entries (.obsidian, .trash, .stfolder, .git …) and refuses symlinks that point
// outside. Writes are atomic (temp file → fsync → rename) and carry a version so a
// save never silently overwrites an edit made meanwhile by Obsidian, Syncthing,
// Omnimo or Kronos. Nothing is ever deleted: "delete" moves to the trash folder.
package vault

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io/fs"
	"log"
	"os"
	"path"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

// Errors returned by the vault. The web layer turns them into HTTP statuses.
var (
	ErrBadPath  = errors.New("invalid path")
	ErrHidden   = errors.New("hidden files and folders are not available here")
	ErrNotFound = errors.New("not found")
	ErrExists   = errors.New("a file or folder with that name already exists")
	ErrIsDir    = errors.New("that is a folder")
	ErrTooLarge = errors.New("file is too large to edit here")
)

// ConflictError means the file changed on disk since the browser loaded it.
type ConflictError struct {
	Current string // content now on disk ("" when Deleted)
	Version string // version of Current
	Deleted bool   // the file was removed or moved away
}

func (e *ConflictError) Error() string {
	if e.Deleted {
		return "the file was deleted or moved by another app"
	}
	return "the file was changed by another app"
}

// PermissionError means the operating system refused samanote (the container's
// PUID:PGID) access to a file or folder in the vault — usually a folder created by
// another app or container running as a different user.
type PermissionError struct {
	Op   string // "save", "create", "rename", "trash", "read"
	Path string // vault-relative
}

func (e *PermissionError) Error() string {
	where := e.Path
	if where == "" {
		where = "the vault"
	}
	return fmt.Sprintf("permission denied: samanote (uid %d, gid %d) may not %s in %q. "+
		"The file or folder belongs to another user — on the server, give the notes folder back to PUID:PGID (chown -R)",
		os.Getuid(), os.Getgid(), e.Op, where)
}

// perm turns an OS permission error into a PermissionError for rel; other errors pass through.
func perm(err error, op, rel string) error {
	if err != nil && errors.Is(err, fs.ErrPermission) {
		return &PermissionError{Op: op, Path: rel}
	}
	return err
}

// Vault is a notes folder.
type Vault struct {
	root     string          // as configured, absolute
	real     string          // root with symlinks resolved
	trash    string          // trash folder name, relative to root
	hidden   map[string]bool // extra names / relative paths to hide
	maxBytes int64           // files bigger than this open read-only
	mu       sync.Mutex      // serialises writes made by this process
	warned   map[string]bool // folders already logged for the in-place fallback
}

// New opens the vault at root.
func New(root, trash string, hidden []string, maxBytes int64) (*Vault, error) {
	abs, err := filepath.Abs(root)
	if err != nil {
		return nil, err
	}
	real, err := filepath.EvalSymlinks(abs)
	if err != nil {
		return nil, fmt.Errorf("notes folder: %w", err)
	}
	v := &Vault{root: abs, real: real, trash: strings.Trim(filepath.ToSlash(trash), "/"), hidden: map[string]bool{}, maxBytes: maxBytes, warned: map[string]bool{}}
	for _, h := range hidden {
		v.hidden[strings.Trim(filepath.ToSlash(h), "/")] = true
	}
	return v, nil
}

// Root is the vault folder.
func (v *Vault) Root() string { return v.root }

// MaxBytes is the largest file that can be edited.
func (v *Vault) MaxBytes() int64 { return v.maxBytes }

// Version identifies a file's content. Two reads with the same version saw the
// same bytes, whatever happened to the mtime.
func Version(b []byte) string {
	s := sha256.Sum256(b)
	return hex.EncodeToString(s[:12])
}

// Clean normalises a browser path ("/a//b/../c.md" → "a/c.md") and checks it is
// allowed. "" means the vault root.
func (v *Vault) Clean(rel string) (string, error) {
	if strings.ContainsRune(rel, 0) || strings.Contains(rel, `\`) {
		return "", ErrBadPath
	}
	c := strings.TrimPrefix(path.Clean("/"+rel), "/")
	if c == "" {
		return "", nil
	}
	parts := strings.Split(c, "/")
	for i, p := range parts {
		if strings.HasPrefix(p, ".") || v.hidden[p] || v.hidden[strings.Join(parts[:i+1], "/")] {
			return "", ErrHidden
		}
	}
	return c, nil
}

// Resolve returns the absolute path for a browser path, after checking that it
// (or, when it does not exist yet, its nearest existing parent) is really inside
// the vault once symlinks are followed.
func (v *Vault) Resolve(rel string) (clean, abs string, err error) {
	clean, err = v.Clean(rel)
	if err != nil {
		return "", "", err
	}
	abs = filepath.Join(v.root, filepath.FromSlash(clean))
	probe := abs
	for {
		real, err := filepath.EvalSymlinks(probe)
		if err == nil {
			if real != v.real && !strings.HasPrefix(real, v.real+string(filepath.Separator)) {
				return "", "", ErrBadPath
			}
			break
		}
		if !errors.Is(err, fs.ErrNotExist) {
			return "", "", err
		}
		parent := filepath.Dir(probe)
		if parent == probe || len(parent) < len(v.root) {
			return "", "", ErrBadPath
		}
		probe = parent
	}
	return clean, abs, nil
}

// Node is one entry of the folder tree.
type Node struct {
	Name     string  `json:"name"`
	Path     string  `json:"path"`
	Dir      bool    `json:"dir,omitempty"`
	Size     int64   `json:"size,omitempty"`
	Mtime    int64   `json:"mtime,omitempty"` // unix ms
	Children []*Node `json:"children,omitempty"`
}

// Tree lists the whole vault: folders first, then files, A–Z ignoring case.
// Hidden entries are left out; symlinked folders are not followed (no loops).
func (v *Vault) Tree() (*Node, error) {
	root := &Node{Name: "", Path: "", Dir: true}
	return root, v.walk(root, v.root)
}

func (v *Vault) walk(n *Node, dir string) error {
	ents, err := os.ReadDir(dir)
	if err != nil {
		return err
	}
	for _, e := range ents {
		rel := e.Name()
		if n.Path != "" {
			rel = n.Path + "/" + e.Name()
		}
		if _, err := v.Clean(rel); err != nil {
			continue
		}
		abs := filepath.Join(dir, e.Name())
		info, err := e.Info()
		if err != nil {
			continue
		}
		if info.Mode()&fs.ModeSymlink != 0 {
			if _, _, err := v.Resolve(rel); err != nil {
				continue // points outside the vault
			}
			if info, err = os.Stat(abs); err != nil || info.IsDir() {
				continue // broken, or a symlinked folder
			}
		}
		c := &Node{Name: e.Name(), Path: rel}
		switch {
		case info.IsDir():
			c.Dir = true
			if err := v.walk(c, abs); err != nil {
				continue
			}
		case info.Mode().IsRegular():
			c.Size, c.Mtime = info.Size(), info.ModTime().UnixMilli()
		default:
			continue // sockets, devices …
		}
		n.Children = append(n.Children, c)
	}
	sort.SliceStable(n.Children, func(i, j int) bool {
		a, b := n.Children[i], n.Children[j]
		if a.Dir != b.Dir {
			return a.Dir
		}
		return naturalLess(strings.ToLower(a.Name), strings.ToLower(b.Name))
	})
	return nil
}

// naturalLess orders "note 2" before "note 10".
func naturalLess(a, b string) bool {
	for a != "" && b != "" {
		ad, bd := isDigit(a[0]), isDigit(b[0])
		if ad && bd {
			i, j := digits(a), digits(b)
			x, _ := strconv.ParseUint(strings.TrimLeft(a[:i], "0")+"0", 10, 64)
			y, _ := strconv.ParseUint(strings.TrimLeft(b[:j], "0")+"0", 10, 64)
			if x != y {
				return x < y
			}
			a, b = a[i:], b[j:]
			continue
		}
		if a[0] != b[0] {
			return a[0] < b[0]
		}
		a, b = a[1:], b[1:]
	}
	return len(a) < len(b)
}

func isDigit(c byte) bool { return c >= '0' && c <= '9' }

func digits(s string) int {
	i := 0
	for i < len(s) && i < 18 && isDigit(s[i]) {
		i++
	}
	return i
}

// File is a file as sent to the editor.
type File struct {
	Path     string `json:"path"`
	Content  string `json:"content"`
	Version  string `json:"version"`
	Size     int64  `json:"size"`
	Mtime    int64  `json:"mtime"`
	Binary   bool   `json:"binary,omitempty"`   // not UTF-8 text: shown, not edited
	ReadOnly bool   `json:"readonly,omitempty"` // bigger than the edit limit
	TooLarge bool   `json:"too_large,omitempty"`
}

// Read loads a file. Text up to the edit limit is editable; up to 4× the limit it
// is shown read-only; anything bigger, or anything that is not UTF-8 text, comes
// back without content (the browser offers the raw file instead).
func (v *Vault) Read(rel string) (*File, error) {
	clean, abs, err := v.Resolve(rel)
	if err != nil {
		return nil, err
	}
	if clean == "" {
		return nil, ErrIsDir
	}
	info, err := os.Stat(abs)
	if err != nil {
		return nil, notFound(err)
	}
	if info.IsDir() {
		return nil, ErrIsDir
	}
	f := &File{Path: clean, Size: info.Size(), Mtime: info.ModTime().UnixMilli()}
	if info.Size() > 4*v.maxBytes {
		f.TooLarge, f.ReadOnly = true, true
		return f, nil
	}
	b, err := os.ReadFile(abs)
	if err != nil {
		return nil, perm(notFound(err), "read", clean)
	}
	f.Version = Version(b)
	if !utf8.Valid(b) || strings.IndexByte(string(b[:min(len(b), 8192)]), 0) >= 0 {
		f.Binary, f.ReadOnly = true, true
		return f, nil
	}
	f.Content = string(b)
	f.ReadOnly = int64(len(b)) > v.maxBytes
	return f, nil
}

// Stat returns the current version of a file without sending its content.
func (v *Vault) Stat(rel string) (string, error) {
	_, abs, err := v.Resolve(rel)
	if err != nil {
		return "", err
	}
	b, err := os.ReadFile(abs)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return "", ErrNotFound
		}
		return "", ErrIsDir
	}
	return Version(b), nil
}

// Write saves content if the file on disk still has version base. force skips the
// check (the user chose "keep mine"). It returns the new version.
func (v *Vault) Write(rel, content, base string, force bool) (string, error) {
	clean, abs, err := v.Resolve(rel)
	if err != nil {
		return "", err
	}
	if clean == "" {
		return "", ErrIsDir
	}
	if int64(len(content)) > v.maxBytes {
		return "", ErrTooLarge
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
		if !force && Version(cur) != base {
			return "", &ConflictError{Current: string(cur), Version: Version(cur)}
		}
		if string(cur) == content {
			return Version(cur), nil // nothing changed: leave the mtime alone
		}
	case errors.Is(err, fs.ErrNotExist):
		if !force {
			return "", &ConflictError{Deleted: true}
		}
		if err := os.MkdirAll(filepath.Dir(abs), 0o755); err != nil {
			return "", err
		}
	case errors.Is(err, fs.ErrPermission):
		return "", &PermissionError{Op: "read", Path: clean}
	default:
		return "", ErrIsDir
	}
	err = atomicWrite(abs, []byte(content), mode)
	if errors.Is(err, fs.ErrPermission) && cur != nil {
		// The folder is not writable for us (no temp file allowed) but the file may
		// be: overwrite it in place, as the old samanote did. Not atomic, still safe
		// enough for a note, and the version check above already ran.
		if dir := path.Dir(clean); !v.warned[dir] {
			v.warned[dir] = true
			log.Printf("WARN folder %q is not writable for uid %d — saving in place instead of atomically; fix its ownership", dir, os.Getuid())
		}
		err = inPlaceWrite(abs, []byte(content))
	}
	if err != nil {
		return "", perm(err, "save", clean)
	}
	return Version([]byte(content)), nil
}

// inPlaceWrite truncates and rewrites an existing file (needs write permission on
// the file only, not on its folder).
func inPlaceWrite(abs string, b []byte) error {
	f, err := os.OpenFile(abs, os.O_WRONLY|os.O_TRUNC, 0)
	if err != nil {
		return err
	}
	if _, err := f.Write(b); err != nil {
		f.Close()
		return err
	}
	if err := f.Sync(); err != nil {
		f.Close()
		return err
	}
	return f.Close()
}

// atomicWrite writes next to the target and renames over it, so readers (Obsidian,
// Syncthing, Kronos) only ever see the old or the new file, never half of one.
func atomicWrite(abs string, b []byte, mode fs.FileMode) error {
	tmp, err := os.CreateTemp(filepath.Dir(abs), ".samanote-*.tmp")
	if err != nil {
		return err
	}
	name := tmp.Name()
	defer os.Remove(name) // no-op after a successful rename
	if _, err := tmp.Write(b); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	if err := os.Chmod(name, mode); err != nil {
		return err
	}
	return os.Rename(name, abs)
}

// Create makes an empty note (".md" is added when the name has no extension) or a
// folder. Missing parent folders are created. It returns the clean path.
func (v *Vault) Create(rel string, folder bool) (string, error) {
	clean, _, err := v.Resolve(rel)
	if err != nil {
		return "", err
	}
	if clean == "" {
		return "", ErrBadPath
	}
	if !folder && path.Ext(clean) == "" {
		clean += ".md"
	}
	clean, abs, err := v.Resolve(clean)
	if err != nil {
		return "", err
	}
	v.mu.Lock()
	defer v.mu.Unlock()
	if _, err := os.Lstat(abs); err == nil {
		return "", ErrExists
	}
	if folder {
		return clean, perm(os.MkdirAll(abs, 0o755), "create", clean)
	}
	if err := os.MkdirAll(filepath.Dir(abs), 0o755); err != nil {
		return "", perm(err, "create", clean)
	}
	f, err := os.OpenFile(abs, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o644)
	if err != nil {
		if errors.Is(err, fs.ErrExist) {
			return "", ErrExists
		}
		return "", perm(err, "create", clean)
	}
	return clean, f.Close()
}

// Move renames a file or folder. It never overwrites: the target must not exist.
// A file renamed without an extension keeps its old one ("todo" → "todo.md").
func (v *Vault) Move(from, to string) (string, error) {
	fc, fabs, err := v.Resolve(from)
	if err != nil {
		return "", err
	}
	if fc == "" {
		return "", ErrBadPath
	}
	info, err := os.Lstat(fabs)
	if err != nil {
		return "", notFound(err)
	}
	tc, err := v.Clean(to)
	if err != nil {
		return "", err
	}
	if !info.IsDir() && path.Ext(tc) == "" && path.Ext(fc) != "" {
		to = tc + path.Ext(fc)
	}
	tc, tabs, err := v.Resolve(to)
	if err != nil {
		return "", err
	}
	if tc == "" || tc == fc {
		return "", ErrBadPath
	}
	if info.IsDir() && strings.HasPrefix(tc+"/", fc+"/") {
		return "", fmt.Errorf("%w: a folder cannot be moved into itself", ErrBadPath)
	}
	v.mu.Lock()
	defer v.mu.Unlock()
	if tinfo, err := os.Lstat(tabs); err == nil && !os.SameFile(info, tinfo) {
		return "", ErrExists // (SameFile allows "Note.md" → "note.md" on case-insensitive disks)
	}
	if err := os.MkdirAll(filepath.Dir(tabs), 0o755); err != nil {
		return "", perm(err, "rename", tc)
	}
	return tc, perm(os.Rename(fabs, tabs), "rename", fc)
}

// Trash moves a file or folder into the vault's trash folder (the one Obsidian
// uses), adding " 2", " 3" … when the name is taken. It returns the trash path.
func (v *Vault) Trash(rel string) (string, error) {
	clean, abs, err := v.Resolve(rel)
	if err != nil {
		return "", err
	}
	if clean == "" {
		return "", ErrBadPath
	}
	if _, err := os.Lstat(abs); err != nil {
		return "", notFound(err)
	}
	v.mu.Lock()
	defer v.mu.Unlock()
	dir := filepath.Join(v.root, filepath.FromSlash(v.trash))
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", perm(err, "trash", v.trash)
	}
	base := path.Base(clean)
	ext := path.Ext(base)
	stem := strings.TrimSuffix(base, ext)
	if stem == "" { // a name like ".md" cannot reach here, but be safe
		stem, ext = base, ""
	}
	name := base
	for i := 2; ; i++ {
		if _, err := os.Lstat(filepath.Join(dir, name)); errors.Is(err, fs.ErrNotExist) {
			break
		}
		if i > 9999 {
			name = stem + " " + strconv.FormatInt(time.Now().UnixNano(), 10) + ext
			break
		}
		name = stem + " " + strconv.Itoa(i) + ext
	}
	if err := os.Rename(abs, filepath.Join(dir, name)); err != nil {
		return "", perm(err, "trash", clean)
	}
	return v.trash + "/" + name, nil
}

// RawPath returns the absolute path of a regular file for serving it as-is
// (images in the preview, "open raw" for binaries).
func (v *Vault) RawPath(rel string) (string, error) {
	clean, abs, err := v.Resolve(rel)
	if err != nil {
		return "", err
	}
	if clean == "" {
		return "", ErrIsDir
	}
	info, err := os.Stat(abs)
	if err != nil {
		return "", notFound(err)
	}
	if !info.Mode().IsRegular() {
		return "", ErrIsDir
	}
	return abs, nil
}

func notFound(err error) error {
	if errors.Is(err, fs.ErrNotExist) {
		return ErrNotFound
	}
	return err
}

// Report is the result of Check.
type Report struct {
	Folders, Files int
	ReadOnlyDirs   []string // folders samanote cannot create, rename or trash in
	ReadOnlyFiles  []string // files samanote cannot save
}

// Check walks the vault (skipping hidden entries) and lists every folder and file
// this process may not write — usually ones created by another app or container
// running as a different user. At most limit paths of each kind are kept.
func (v *Vault) Check(limit int) (*Report, error) {
	rep := &Report{}
	if !writable(v.root) {
		rep.ReadOnlyDirs = append(rep.ReadOnlyDirs, "(vault root)")
	}
	t, err := v.Tree()
	if err != nil {
		return nil, err
	}
	var walk func(n *Node)
	walk = func(n *Node) {
		for _, c := range n.Children {
			abs := filepath.Join(v.root, filepath.FromSlash(c.Path))
			if c.Dir {
				rep.Folders++
				if !writable(abs) && len(rep.ReadOnlyDirs) < limit {
					rep.ReadOnlyDirs = append(rep.ReadOnlyDirs, c.Path)
				}
				walk(c)
				continue
			}
			rep.Files++
			if !writable(abs) && len(rep.ReadOnlyFiles) < limit {
				rep.ReadOnlyFiles = append(rep.ReadOnlyFiles, c.Path)
			}
		}
	}
	walk(t)
	return rep, nil
}
