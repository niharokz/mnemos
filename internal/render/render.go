// Package render turns a note into the HTML shown in the preview pane.
//
// It is plain CommonMark + GitHub extras (tables, task lists, strikethrough,
// autolinks, footnotes) — no Obsidian-only syntax. Raw HTML in notes is not
// rendered (goldmark's safe default) and javascript: links are dropped, so a note
// can never run code in the app. Single line breaks are kept, as in Obsidian.
//
// Relative links are made to work inside samanote: images load from /raw/<path>,
// links to other notes open them in the editor (#/n/<path>), links to other files
// open the raw file.
package render

import (
	"bytes"
	"html"
	"net/url"
	"path"
	"strings"

	"github.com/yuin/goldmark"
	"github.com/yuin/goldmark/ast"
	"github.com/yuin/goldmark/extension"
	"github.com/yuin/goldmark/parser"
	ghtml "github.com/yuin/goldmark/renderer/html"
	"github.com/yuin/goldmark/text"
	"github.com/yuin/goldmark/util"
)

var dirKey = parser.NewContextKey()

var md = goldmark.New(
	goldmark.WithExtensions(extension.GFM, extension.Footnote),
	goldmark.WithParserOptions(
		parser.WithAutoHeadingID(),
		parser.WithASTTransformers(util.Prioritized(linkRewriter{}, 100)),
	),
	goldmark.WithRendererOptions(ghtml.WithHardWraps()),
)

// Render converts the note at notePath (vault-relative, used to resolve relative
// links) to HTML.
func Render(notePath, src string) (string, error) {
	front, body := SplitFrontmatter(src)
	var buf bytes.Buffer
	if front != "" {
		buf.WriteString(`<pre class="frontmatter"><code>`)
		buf.WriteString(html.EscapeString(front))
		buf.WriteString("</code></pre>\n")
	}
	ctx := parser.NewContext()
	ctx.Set(dirKey, path.Dir(notePath))
	if err := md.Convert([]byte(body), &buf, parser.WithContext(ctx)); err != nil {
		return "", err
	}
	return buf.String(), nil
}

// SplitFrontmatter separates a leading YAML block ("---" … "---") from the note.
// The block is returned without its fences; a note without one returns "", src.
func SplitFrontmatter(src string) (front, body string) {
	s := strings.TrimPrefix(src, "\ufeff")
	nl := "\n"
	switch {
	case strings.HasPrefix(s, "---\r\n"):
		nl = "\r\n"
	case strings.HasPrefix(s, "---\n"):
	default:
		return "", src
	}
	rest := s[3+len(nl):]
	if strings.HasPrefix(rest, "---"+nl) || rest == "---" { // empty block: nothing to show
		return "", strings.TrimPrefix(strings.TrimPrefix(rest, "---"), nl)
	}
	end := strings.Index(rest, nl+"---"+nl)
	if end < 0 {
		if strings.HasSuffix(rest, nl+"---") {
			return rest[:len(rest)-3-len(nl)], ""
		}
		return "", src // unclosed: just markdown with a rule at the top
	}
	return rest[:end], rest[end+len(nl)+3+len(nl):]
}

// linkRewriter points relative links and images at the vault.
type linkRewriter struct{}

func (linkRewriter) Transform(doc *ast.Document, _ text.Reader, pc parser.Context) {
	dir, _ := pc.Get(dirKey).(string)
	_ = ast.Walk(doc, func(n ast.Node, entering bool) (ast.WalkStatus, error) {
		if !entering {
			return ast.WalkContinue, nil
		}
		switch n := n.(type) {
		case *ast.Image:
			if p, ok := vaultPath(dir, string(n.Destination)); ok {
				n.Destination = []byte("/raw/" + escapePath(p))
			}
		case *ast.Link:
			if p, ok := vaultPath(dir, string(n.Destination)); ok {
				ext := strings.ToLower(path.Ext(p))
				if ext == ".md" || ext == ".markdown" || ext == ".txt" || ext == "" {
					if ext == "" {
						p += ".md"
					}
					n.Destination = []byte("#/n/" + escapePath(p))
				} else {
					n.Destination = []byte("/raw/" + escapePath(p))
				}
			}
		}
		return ast.WalkContinue, nil
	})
}

// vaultPath resolves a relative link against the note's folder. It reports false
// for external URLs, absolute paths, in-page anchors and links leaving the vault.
func vaultPath(dir, dest string) (string, bool) {
	if dest == "" || strings.HasPrefix(dest, "#") || strings.HasPrefix(dest, "/") || strings.HasPrefix(dest, "//") {
		return "", false
	}
	if u, err := url.Parse(dest); err != nil || u.Scheme != "" || u.Host != "" {
		return "", false
	}
	if i := strings.IndexAny(dest, "#?"); i >= 0 {
		dest = dest[:i]
	}
	if un, err := url.PathUnescape(dest); err == nil {
		dest = un
	}
	p := path.Clean(path.Join(dir, dest))
	if p == "." || p == ".." || strings.HasPrefix(p, "../") {
		return "", false
	}
	return p, true
}

func escapePath(p string) string {
	parts := strings.Split(p, "/")
	for i, s := range parts {
		parts[i] = url.PathEscape(s)
	}
	return strings.Join(parts, "/")
}
