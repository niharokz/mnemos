// Package search answers the one search box: file names (fuzzy), full text,
// #tags and front-matter values (key: value). It keeps a small in-memory cache of
// each text file, refreshed by size and mtime, so nothing is written anywhere.
package search

import (
	"os"
	"path"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"
	"unicode"
	"unicode/utf8"

	"gitlab.com/niharokz/mnemos/internal/vault"
)

// MaxFileBytes is the largest file whose text is searched (bigger ones match by name only).
const MaxFileBytes = 2 << 20

// Hit is one search result.
type Hit struct {
	Path    string   `json:"path"`
	Name    string   `json:"name"`
	Score   int      `json:"score"`
	Snippet string   `json:"snippet,omitempty"` // text around the first match, for display
	Line    int      `json:"line,omitempty"`    // 1-based line of the first content match
	Tags    []string `json:"tags,omitempty"`
}

// Result is the answer to a query.
type Result struct {
	Hits  []Hit `json:"hits"`
	Total int   `json:"total"`
	Files int   `json:"files"` // text files searched
	Ms    int64 `json:"ms"`
}

type entry struct {
	size  int64
	mtime int64
	lower string            // lower-cased text
	text  string            // original text, for snippets
	tags  map[string]bool   // #tags in the body and tags: in front matter
	front map[string]string // front matter key → lower-cased value
}

// Index caches the vault's text files.
type Index struct {
	v       *vault.Vault
	mu      sync.Mutex
	files   map[string]*entry
	scanned time.Time
	tree    *vault.Node
}

// New makes an index for a vault. It fills lazily on the first query.
func New(v *vault.Vault) *Index { return &Index{v: v, files: map[string]*entry{}} }

var (
	tagRe   = regexp.MustCompile(`(?:^|[\s(\[])#([\p{L}\p{N}_/-]+)`)
	fmLine  = regexp.MustCompile(`^([A-Za-z0-9_-]+):\s*(.*)$`)
	codeFen = regexp.MustCompile("(?m)^(```|~~~)")
)

// refresh rescans the tree (at most every 3 s) and re-reads changed files.
func (ix *Index) refresh() error {
	if time.Since(ix.scanned) < 3*time.Second && ix.tree != nil {
		return nil
	}
	t, err := ix.v.Tree()
	if err != nil {
		return err
	}
	seen := map[string]bool{}
	var walk func(n *vault.Node)
	walk = func(n *vault.Node) {
		for _, c := range n.Children {
			if c.Dir {
				walk(c)
				continue
			}
			seen[c.Path] = true
			e := ix.files[c.Path]
			if e != nil && e.size == c.Size && e.mtime == c.Mtime {
				continue
			}
			ix.files[c.Path] = load(filepath.Join(ix.v.Root(), filepath.FromSlash(c.Path)), c.Size, c.Mtime)
		}
	}
	walk(t)
	for p := range ix.files {
		if !seen[p] {
			delete(ix.files, p)
		}
	}
	ix.tree, ix.scanned = t, time.Now()
	return nil
}

// binaryExt are skipped without reading: they are never text.
var binaryExt = map[string]bool{
	".png": true, ".jpg": true, ".jpeg": true, ".gif": true, ".webp": true, ".avif": true, ".bmp": true,
	".pdf": true, ".zip": true, ".apk": true, ".mp3": true, ".mp4": true, ".m4a": true, ".mov": true,
}

// load reads one file; binary or big files get an entry without text.
func load(abs string, size, mtime int64) *entry {
	e := &entry{size: size, mtime: mtime}
	if size > MaxFileBytes || binaryExt[strings.ToLower(filepath.Ext(abs))] {
		return e
	}
	b, err := os.ReadFile(abs)
	if err != nil || !utf8.Valid(b) || strings.IndexByte(string(b[:min(len(b), 8192)]), 0) >= 0 {
		return e
	}
	e.text = string(b)
	e.lower = strings.ToLower(e.text)
	e.tags, e.front = map[string]bool{}, map[string]string{}
	body := e.text
	if strings.HasPrefix(body, "---\n") || strings.HasPrefix(body, "---\r\n") {
		rest := body[strings.Index(body, "\n")+1:]
		if end := strings.Index(rest, "\n---"); end >= 0 {
			for _, ln := range strings.Split(rest[:end], "\n") {
				m := fmLine.FindStringSubmatch(strings.TrimRight(ln, "\r"))
				if m == nil {
					continue
				}
				k, val := strings.ToLower(m[1]), strings.Trim(strings.TrimSpace(m[2]), `"'`)
				e.front[k] = strings.ToLower(val)
				if k == "tags" || k == "tag" {
					for _, t := range strings.FieldsFunc(strings.Trim(val, "[]"), func(r rune) bool { return r == ',' || r == ' ' }) {
						if t = strings.Trim(t, `#"' `); t != "" {
							e.tags[strings.ToLower(t)] = true
						}
					}
				}
			}
			body = rest[end:]
		}
	}
	// #tags outside fenced code blocks
	parts := codeFen.Split(body, -1)
	for i, part := range parts {
		if i%2 == 1 {
			continue
		}
		for _, m := range tagRe.FindAllStringSubmatch(part, -1) {
			if !isNumber(m[1]) {
				e.tags[strings.ToLower(m[1])] = true
			}
		}
	}
	return e
}

func isNumber(s string) bool {
	for _, r := range s {
		if !unicode.IsDigit(r) {
			return false
		}
	}
	return true
}

type query struct {
	words []string          // plain words: all must match (name or text)
	tags  []string          // #tag
	front map[string]string // key: value
}

func parse(q string) query {
	out := query{front: map[string]string{}}
	toks := strings.Fields(strings.ToLower(q))
	for i := 0; i < len(toks); i++ {
		t := toks[i]
		switch {
		case strings.HasPrefix(t, "#") && len(t) > 1:
			out.tags = append(out.tags, t[1:])
		case strings.HasSuffix(t, ":") && len(t) > 1 && i+1 < len(toks):
			out.front[strings.TrimSuffix(t, ":")] = toks[i+1] // "status: open"
			i++
		case strings.Contains(t, ":") && !strings.Contains(t, "://") && !strings.HasPrefix(t, ":") && !strings.HasSuffix(t, ":"):
			k, v, _ := strings.Cut(t, ":") // "status:open"
			out.front[k] = v
		default:
			out.words = append(out.words, t)
		}
	}
	return out
}

// Search runs a query and returns at most limit hits, best first.
func (ix *Index) Search(q string, limit int) (*Result, error) {
	start := time.Now()
	ix.mu.Lock()
	defer ix.mu.Unlock()
	if err := ix.refresh(); err != nil {
		return nil, err
	}
	pq := parse(q)
	res := &Result{Hits: []Hit{}}
	if len(pq.words)+len(pq.tags)+len(pq.front) == 0 {
		return res, nil
	}
	for p, e := range ix.files {
		if e.lower != "" {
			res.Files++
		}
		h, ok := match(p, e, pq)
		if ok {
			res.Hits = append(res.Hits, h)
		}
	}
	res.Total = len(res.Hits)
	sort.Slice(res.Hits, func(i, j int) bool {
		if res.Hits[i].Score != res.Hits[j].Score {
			return res.Hits[i].Score > res.Hits[j].Score
		}
		return res.Hits[i].Path < res.Hits[j].Path
	})
	if len(res.Hits) > limit {
		res.Hits = res.Hits[:limit]
	}
	res.Ms = time.Since(start).Milliseconds()
	return res, nil
}

func match(p string, e *entry, q query) (Hit, bool) {
	name := strings.ToLower(filepath.Base(p))
	lp := strings.ToLower(p)
	h := Hit{Path: p, Name: filepath.Base(p)}
	for _, t := range q.tags {
		if !e.tags[t] {
			return h, false
		}
		h.Score += 30
	}
	for k, v := range q.front {
		fv, ok := e.front[k]
		if !ok || !strings.Contains(fv, v) {
			return h, false
		}
		h.Score += 30
	}
	first := -1
	for _, w := range q.words {
		switch {
		case strings.Contains(name, w):
			h.Score += 60
			if strings.HasPrefix(name, w) {
				h.Score += 20
			}
		case strings.Contains(lp, w):
			h.Score += 25
		case fuzzy(name, w):
			h.Score += 15
		case e.lower != "" && strings.Contains(e.lower, w):
			h.Score += 10 + min(strings.Count(e.lower, w), 10)
			if i := strings.Index(e.lower, w); first < 0 || i < first {
				first = i
			}
		default:
			return h, false
		}
	}
	if first < 0 && len(q.words) > 0 && e.lower != "" {
		first = strings.Index(e.lower, q.words[0]) // name matched; still show where the text mentions it
	}
	if first >= 0 {
		h.Snippet, h.Line = snippet(e.text, first)
	}
	for t := range e.tags {
		h.Tags = append(h.Tags, t)
	}
	sort.Strings(h.Tags)
	if len(h.Tags) > 6 {
		h.Tags = h.Tags[:6]
	}
	return h, true
}

// fuzzy reports whether all runes of w appear in s in order (e.g. "mtour" → "markdown tour").
func fuzzy(s, w string) bool {
	if len(w) < 3 {
		return false
	}
	rs := []rune(s)
	i := 0
	for _, c := range w {
		for i < len(rs) && rs[i] != c {
			i++
		}
		if i == len(rs) {
			return false
		}
		i++
	}
	return true
}

// snippet returns the line around byte offset at (trimmed to ~160 chars) and its number.
func snippet(text string, at int) (string, int) {
	if at > len(text) {
		return "", 0
	}
	line := strings.Count(text[:at], "\n") + 1
	ls := strings.LastIndexByte(text[:at], '\n') + 1
	le := strings.IndexByte(text[at:], '\n')
	if le < 0 {
		le = len(text)
	} else {
		le += at
	}
	s := strings.TrimSpace(strings.TrimRight(text[ls:le], "\r"))
	if r := []rune(s); len(r) > 160 {
		off := len([]rune(text[ls:at]))
		from := max(0, off-60)
		to := min(len(r), from+160)
		s = string(r[from:to])
		if from > 0 {
			s = "…" + s
		}
		if to < len(r) {
			s += "…"
		}
	}
	return s, line
}

// Resolve finds the file an Obsidian embed (![[target]]) means, the way Obsidian
// does: the path from the note's folder (or its attachments/ folder, where Mnemos
// saves images), then an exact vault path, then any file with that name — the
// one closest to the note's folder wins.
func (ix *Index) Resolve(target, fromDir string) (string, bool) {
	ix.mu.Lock()
	defer ix.mu.Unlock()
	if err := ix.refresh(); err != nil {
		return "", false
	}
	if fromDir == "." {
		fromDir = ""
	}
	// relative to the note first (may use ../), then as a vault path
	candidates := []string{path.Join(fromDir, target), path.Join(fromDir, "attachments", target)}
	target = strings.TrimPrefix(path.Clean("/"+target), "/") // can never climb out of the vault
	candidates = append(candidates, target)
	for _, p := range candidates {
		if _, ok := ix.files[p]; ok && !strings.HasPrefix(p, "../") {
			return p, true
		}
	}
	best, bestScore := "", 0
	for p := range ix.files {
		if !strings.HasSuffix(p, "/"+target) {
			continue
		}
		score := shared(path.Dir(p), fromDir)*1000 - len(p) // same folder first, then shortest
		if best == "" || score > bestScore || score == bestScore && p < best {
			best, bestScore = p, score
		}
	}
	return best, best != ""
}

// shared counts the leading folders two paths have in common.
func shared(a, b string) int {
	if a == "." {
		a = ""
	}
	as, bs := strings.Split(a, "/"), strings.Split(b, "/")
	n := 0
	for n < len(as) && n < len(bs) && as[n] == bs[n] && as[n] != "" {
		n++
	}
	return n
}
