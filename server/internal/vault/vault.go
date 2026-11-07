// Package vault is the notes folder as the web app sees it. Every path is
// relative to the root and is checked twice: lexically (no "..", no absolute
// paths) and after resolving symlinks (it must still be inside the root).
// Behaviour matches the desktop app (app/src/files.rs): atomic writes with a
// modification-time check, and the same trash (logs/mnemostxt/trash) and ledger.
package vault

import (
	"bufio"
	"errors"
	"fmt"
	"io/fs"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"gitlab.com/niharokz/mnemostxt/server/internal/httpx"
)

const (
	AppDir   = "logs/mnemostxt" // the app's folder inside the notes folder (settings.json too)
	TrashDir = AppDir + "/trash"
	Ledger   = ".mnemostxt-trash.tsv"
)

type Vault struct {
	Root    string // absolute, symlinks resolved
	MaxSize int64
	Hidden  map[string]bool // skipped by search
}

func New(root string, maxSize int64, hidden []string) (*Vault, error) {
	abs, err := filepath.Abs(root)
	if err != nil {
		return nil, err
	}
	real, err := filepath.EvalSymlinks(abs)
	if err != nil {
		return nil, fmt.Errorf("notes folder %s: %w", root, err)
	}
	st, err := os.Stat(real)
	if err != nil || !st.IsDir() {
		return nil, fmt.Errorf("notes folder %s is not a folder", root)
	}
	h := map[string]bool{}
	for _, x := range hidden {
		h[x] = true
	}
	return &Vault{Root: real, MaxSize: maxSize, Hidden: h}, nil
}

type Entry struct {
	Name  string `json:"name"`
	Dir   bool   `json:"dir"`
	Size  int64  `json:"size"`
	Mtime int64  `json:"mtime"`
}

type Stat struct {
	Dir   bool  `json:"dir"`
	Size  int64 `json:"size"`
	Mtime int64 `json:"mtime"`
}

func ms(t time.Time) int64 { return t.UnixMilli() }

func errNotFound(rel string) error {
	return httpx.Errorf(http.StatusNotFound, "NOT_FOUND", "%s not found", rel)
}

func ioErr(err error, rel string) error {
	switch {
	case errors.Is(err, fs.ErrNotExist):
		return errNotFound(rel)
	case errors.Is(err, fs.ErrExist):
		return httpx.Errorf(http.StatusConflict, "EXISTS", "%s already exists", rel)
	case errors.Is(err, fs.ErrPermission):
		return httpx.Errorf(http.StatusForbidden, "DENIED", "no permission for %s", rel)
	}
	return err
}

// Clean turns a client path into a clean relative one ("" is the root).
func Clean(rel string) (string, error) {
	if strings.ContainsRune(rel, 0) || strings.Contains(rel, "\\") {
		return "", httpx.Errorf(http.StatusBadRequest, "INVALID", "invalid path")
	}
	c := path.Clean("/" + rel)
	if c == "/" {
		return "", nil
	}
	return strings.TrimPrefix(c, "/"), nil
}

// Abs maps a client path to a real path inside the root. The deepest existing
// part is resolved through symlinks and must stay inside the root.
func (v *Vault) Abs(rel string) (string, string, error) {
	c, err := Clean(rel)
	if err != nil {
		return "", "", err
	}
	full := filepath.Join(v.Root, filepath.FromSlash(c))
	probe, rest := full, ""
	for {
		if real, err := filepath.EvalSymlinks(probe); err == nil {
			resolved := filepath.Join(real, rest)
			if resolved != v.Root && !strings.HasPrefix(resolved, v.Root+string(filepath.Separator)) {
				return "", "", httpx.Errorf(http.StatusForbidden, "DENIED", "%s points outside the notes folder", c)
			}
			return full, c, nil
		}
		parent := filepath.Dir(probe)
		if parent == probe {
			return "", "", httpx.Errorf(http.StatusBadRequest, "INVALID", "invalid path")
		}
		rest = filepath.Join(filepath.Base(probe), rest)
		probe = parent
	}
}

func (v *Vault) List(rel string) ([]Entry, error) {
	p, c, err := v.Abs(rel)
	if err != nil {
		return nil, err
	}
	des, err := os.ReadDir(p)
	if err != nil {
		return nil, ioErr(err, c)
	}
	out := make([]Entry, 0, len(des))
	for _, d := range des {
		st, err := os.Stat(filepath.Join(p, d.Name())) // follow links
		if err != nil {
			if st, err = os.Lstat(filepath.Join(p, d.Name())); err != nil {
				continue
			}
		}
		e := Entry{Name: d.Name(), Dir: st.IsDir(), Mtime: ms(st.ModTime())}
		if !e.Dir {
			e.Size = st.Size()
		}
		out = append(out, e)
	}
	return out, nil
}

func (v *Vault) Stat(rel string) (*Stat, error) {
	p, c, err := v.Abs(rel)
	if err != nil {
		return nil, err
	}
	st, err := os.Stat(p)
	if errors.Is(err, fs.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, ioErr(err, c)
	}
	return &Stat{Dir: st.IsDir(), Size: st.Size(), Mtime: ms(st.ModTime())}, nil
}

func (v *Vault) Read(rel string) ([]byte, int64, error) {
	p, c, err := v.Abs(rel)
	if err != nil {
		return nil, 0, err
	}
	st, err := os.Stat(p)
	if err != nil {
		return nil, 0, ioErr(err, c)
	}
	if st.IsDir() {
		return nil, 0, httpx.Errorf(http.StatusBadRequest, "INVALID", "%s is a folder", c)
	}
	if st.Size() > v.MaxSize {
		return nil, 0, httpx.Errorf(http.StatusRequestEntityTooLarge, "TOO_LARGE", "%s is too large", c)
	}
	b, err := os.ReadFile(p)
	if err != nil {
		return nil, 0, ioErr(err, c)
	}
	return b, ms(st.ModTime()), nil
}

// Write replaces the file atomically. With expect >= 0 it refuses (CONFLICT)
// when the file on disk has another modification time.
func (v *Vault) Write(rel string, data []byte, expect int64) (int64, error) {
	p, c, err := v.Abs(rel)
	if err != nil {
		return 0, err
	}
	if c == "" {
		return 0, httpx.Errorf(http.StatusBadRequest, "INVALID", "invalid path")
	}
	if int64(len(data)) > v.MaxSize {
		return 0, httpx.Errorf(http.StatusRequestEntityTooLarge, "TOO_LARGE", "file too large")
	}
	target := p
	if real, err := filepath.EvalSymlinks(p); err == nil {
		target = real // write through links
	}
	st, statErr := os.Stat(target)
	if statErr == nil {
		if st.IsDir() {
			return 0, httpx.Errorf(http.StatusBadRequest, "INVALID", "%s is a folder", c)
		}
		if expect >= 0 && ms(st.ModTime()) != expect {
			return 0, httpx.Errorf(http.StatusConflict, "CONFLICT", "%s changed on disk", c)
		}
	}
	dir := filepath.Dir(target)
	if _, err := os.Stat(dir); err != nil {
		return 0, ioErr(err, path.Dir(c))
	}
	tmp, err := os.CreateTemp(dir, ".~"+filepath.Base(target)+".*.mnemostxt-tmp")
	if err != nil {
		return 0, ioErr(err, c)
	}
	_, werr := tmp.Write(data)
	if werr == nil {
		werr = tmp.Sync()
	}
	if cerr := tmp.Close(); werr == nil {
		werr = cerr
	}
	if werr == nil && statErr == nil {
		_ = os.Chmod(tmp.Name(), st.Mode().Perm())
	} else if werr == nil {
		_ = os.Chmod(tmp.Name(), 0o644)
	}
	if werr == nil {
		werr = os.Rename(tmp.Name(), target)
	}
	if werr != nil {
		_ = os.Remove(tmp.Name())
		return 0, ioErr(werr, c)
	}
	st, err = os.Stat(target)
	if err != nil {
		return 0, ioErr(err, c)
	}
	return ms(st.ModTime()), nil
}

func (v *Vault) MkdirAll(rel string) error {
	p, c, err := v.Abs(rel)
	if err != nil {
		return err
	}
	return ioErr(os.MkdirAll(p, 0o755), c)
}

func (v *Vault) Create(rel string) (int64, error) {
	p, c, err := v.Abs(rel)
	if err != nil {
		return 0, err
	}
	f, err := os.OpenFile(p, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o644)
	if err != nil {
		return 0, ioErr(err, c)
	}
	st, _ := f.Stat()
	_ = f.Close()
	return ms(st.ModTime()), nil
}

func (v *Vault) Rename(from, to string) error {
	pf, cf, err := v.Abs(from)
	if err != nil {
		return err
	}
	pt, ct, err := v.Abs(to)
	if err != nil {
		return err
	}
	if cf == "" || ct == "" {
		return httpx.Errorf(http.StatusBadRequest, "INVALID", "cannot rename the notes folder")
	}
	if _, err := os.Lstat(pf); err != nil {
		return ioErr(err, cf)
	}
	if _, err := os.Lstat(pt); err == nil && !strings.EqualFold(pf, pt) {
		return httpx.Errorf(http.StatusConflict, "EXISTS", "%s already exists", ct)
	}
	if err := os.MkdirAll(filepath.Dir(pt), 0o755); err != nil {
		return ioErr(err, ct)
	}
	return ioErr(os.Rename(pf, pt), cf)
}

// Trash moves an item into logs/mnemostxt/trash/ and records it in the ledger.
func (v *Vault) Trash(rel string) error {
	p, c, err := v.Abs(rel)
	if err != nil {
		return err
	}
	if c == "" || c == TrashDir || strings.HasPrefix(c, TrashDir+"/") {
		return httpx.Errorf(http.StatusBadRequest, "INVALID", "cannot trash the notes folder or the trash itself")
	}
	if _, err := os.Lstat(p); err != nil {
		return ioErr(err, c)
	}
	trash := filepath.Join(v.Root, filepath.FromSlash(TrashDir))
	if err := os.MkdirAll(trash, 0o755); err != nil {
		return ioErr(err, TrashDir)
	}
	name := filepath.Base(p)
	dest := filepath.Join(trash, name)
	for n := 1; ; n++ {
		if _, err := os.Lstat(dest); errors.Is(err, fs.ErrNotExist) {
			break
		}
		ext := filepath.Ext(name)
		stem := strings.TrimSuffix(name, ext)
		if stem == "" {
			stem, ext = name, ""
		}
		dest = filepath.Join(trash, fmt.Sprintf("%s (%d)%s", stem, n, ext))
	}
	if err := os.Rename(p, dest); err != nil {
		return ioErr(err, c)
	}
	f, err := os.OpenFile(filepath.Join(trash, Ledger), os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
	if err == nil {
		fmt.Fprintf(f, "%s\t%s\t%d\n", filepath.Base(dest), c, time.Now().Unix())
		_ = f.Close()
	}
	return nil
}

type PurgeResult struct {
	Removed int   `json:"removed"`
	Bytes   int64 `json:"bytes"`
}

// Purge deletes trash items older than days (ledger time, else mtime).
// The only place the web app deletes anything.
func (v *Vault) Purge(days int, now time.Time) (PurgeResult, error) {
	trash := filepath.Join(v.Root, filepath.FromSlash(TrashDir))
	ledgerPath := filepath.Join(trash, Ledger)
	when := map[string]int64{}
	var lines []string
	if b, err := os.ReadFile(ledgerPath); err == nil {
		for _, l := range strings.Split(strings.TrimRight(string(b), "\n"), "\n") {
			cols := strings.Split(l, "\t")
			if len(cols) >= 3 {
				if t, err := strconv.ParseInt(cols[2], 10, 64); err == nil {
					when[cols[0]] = t
					lines = append(lines, l)
				}
			}
		}
	}
	cutoff := now.Add(-time.Duration(days) * 24 * time.Hour).Unix()
	var out PurgeResult
	des, err := os.ReadDir(trash)
	if err != nil {
		return out, nil
	}
	gone := map[string]bool{}
	for _, d := range des {
		name := d.Name()
		if strings.HasPrefix(name, ".") {
			continue
		}
		t, ok := when[name]
		if !ok {
			if info, err := d.Info(); err == nil {
				t = info.ModTime().Unix()
			} else {
				continue
			}
		}
		if t > cutoff {
			continue
		}
		p := filepath.Join(trash, name)
		size := dirSize(p)
		if err := os.RemoveAll(p); err == nil {
			out.Removed++
			out.Bytes += size
			gone[name] = true
		}
	}
	var keep strings.Builder
	for _, l := range lines {
		if !gone[strings.SplitN(l, "\t", 2)[0]] {
			keep.WriteString(l + "\n")
		}
	}
	_ = os.WriteFile(ledgerPath, []byte(keep.String()), 0o644)
	return out, nil
}

func dirSize(p string) int64 {
	var n int64
	_ = filepath.WalkDir(p, func(_ string, d fs.DirEntry, err error) error {
		if err == nil && !d.IsDir() {
			if info, err := d.Info(); err == nil {
				n += info.Size()
			}
		}
		return nil
	})
	return n
}

type Hit struct {
	Path string `json:"path"`
	Line int    `json:"line"`
	Col  int    `json:"col"`
	Text string `json:"text"`
}

// Search finds lines matching query in every text file under the root.
func (v *Vault) Search(query string, isRegex, caseSensitive bool, max int) ([]Hit, error) {
	if query == "" {
		return []Hit{}, nil
	}
	pat := query
	if !isRegex {
		pat = regexp.QuoteMeta(query)
	}
	if !caseSensitive {
		pat = "(?i)" + pat
	}
	re, err := regexp.Compile(pat)
	if err != nil {
		return nil, httpx.Errorf(http.StatusBadRequest, "INVALID", "bad pattern: %v", err)
	}
	out := []Hit{}
	errStop := errors.New("enough")
	walkErr := filepath.WalkDir(v.Root, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if d.IsDir() && p == filepath.Join(v.Root, filepath.FromSlash(AppDir)) {
			return filepath.SkipDir // settings and trash are not searched
		}
		if p != v.Root && v.Hidden[d.Name()] {
			if d.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		if d.IsDir() || !d.Type().IsRegular() {
			return nil
		}
		info, err := d.Info()
		if err != nil || info.Size() > 4<<20 {
			return nil
		}
		f, err := os.Open(p)
		if err != nil {
			return nil
		}
		defer f.Close()
		rel, _ := filepath.Rel(v.Root, p)
		rel = filepath.ToSlash(rel)
		sc := bufio.NewScanner(f)
		sc.Buffer(make([]byte, 64<<10), 4<<20)
		for n := 1; sc.Scan(); n++ {
			line := sc.Bytes()
			if n == 1 && containsNUL(line) {
				return nil
			}
			if loc := re.FindIndex(line); loc != nil {
				text := string(line)
				if utf8.RuneCountInString(text) > 300 {
					text = string([]rune(text)[:300])
				}
				out = append(out, Hit{Path: rel, Line: n, Col: utf8.RuneCount(line[:loc[0]]) + 1, Text: text})
				if len(out) >= max {
					return errStop
				}
			}
		}
		return nil
	})
	if walkErr != nil && walkErr != errStop {
		return nil, walkErr
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Path < out[j].Path })
	return out, nil
}

func containsNUL(b []byte) bool {
	for _, c := range b {
		if c == 0 {
			return true
		}
	}
	return false
}
