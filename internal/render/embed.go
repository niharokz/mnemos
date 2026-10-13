package render

import (
	"path"
	"strconv"
	"strings"

	"github.com/yuin/goldmark/ast"
	"github.com/yuin/goldmark/parser"
	"github.com/yuin/goldmark/text"
)

// Resolver finds the vault path of an Obsidian embed target ("photo.png",
// "attachments/photo.png"), searching from the note's folder. ok is false when the
// file does not exist.
type Resolver func(target, fromDir string) (vaultPath string, ok bool)

var resolverKey = parser.NewContextKey()

// IsImage reports whether a file name has an image extension the browser can show.
func IsImage(name string) bool {
	switch strings.ToLower(path.Ext(name)) {
	case ".png", ".jpg", ".jpeg", ".gif", ".webp", ".avif", ".bmp", ".svg":
		return true
	}
	return false
}

// embedParser reads Obsidian's embed syntax, ![[target]] and ![[target|alt or 300 or 300x200]],
// so images pasted in Obsidian show here too. Code spans are parsed first, so an
// embed inside `backticks` stays text.
type embedParser struct{}

func (embedParser) Trigger() []byte { return []byte{'!'} }

func (embedParser) Parse(parent ast.Node, block text.Reader, pc parser.Context) ast.Node {
	line, seg := block.PeekLine()
	if len(line) < 6 || line[1] != '[' || line[2] != '[' {
		return nil
	}
	end := strings.Index(string(line[3:]), "]]")
	if end <= 0 {
		return nil
	}
	inner := string(line[3 : 3+end])
	if strings.ContainsAny(inner, "[]\n") {
		return nil
	}
	block.Advance(3 + end + 2)

	target, opt, _ := strings.Cut(inner, "|")
	target, _, _ = strings.Cut(strings.TrimSpace(target), "#") // "#heading" anchors are not used here
	label := strings.TrimSpace(opt)
	dir, _ := pc.Get(dirKey).(string)
	resolve, _ := pc.Get(resolverKey).(Resolver)

	vp, ok := "", false
	if resolve != nil && target != "" {
		vp, ok = resolve(target, dir)
	}
	if !ok {
		return ast.NewTextSegment(text.NewSegment(seg.Start, seg.Start+3+end+2)) // shown as typed
	}
	if !IsImage(vp) { // an embedded note: a link to it is the honest equivalent here
		l := ast.NewLink()
		l.Destination = []byte("#/n/" + escapePath(vp))
		l.AppendChild(l, ast.NewString([]byte(path.Base(vp))))
		return l
	}
	img := ast.NewImage(ast.NewLink())
	img.Destination = []byte("/raw/" + escapePath(vp))
	if w, h, sized := size(label); sized {
		img.SetAttributeString("width", []byte(w))
		if h != "" {
			img.SetAttributeString("height", []byte(h))
		}
		label = ""
	}
	if label == "" {
		label = strings.TrimSuffix(path.Base(vp), path.Ext(vp))
	}
	img.AppendChild(img, ast.NewString([]byte(label)))
	return img
}

// size reads Obsidian's "300" or "300x200".
func size(s string) (w, h string, ok bool) {
	w, h, _ = strings.Cut(s, "x")
	if _, err := strconv.Atoi(w); err != nil || w == "" {
		return "", "", false
	}
	if _, err := strconv.Atoi(h); h != "" && err != nil {
		return "", "", false
	}
	return w, h, true
}
