package render

import (
	"strings"
	"testing"
)

func TestGFM(t *testing.T) {
	out, err := Render("n.md", "# Title\n\n- [x] done\n- [ ] todo\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n~~old~~ https://nih.ar\n\nline one\nline two\n\nnote[^1]\n\n[^1]: foot\n")
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{
		`<h1 id="title">Title</h1>`, `type="checkbox"`, "<table>", "<del>old</del>",
		`<a href="https://nih.ar">`, "line one<br>", `class="footnotes"`,
	} {
		if !strings.Contains(out, want) {
			t.Errorf("missing %q in:\n%s", want, out)
		}
	}
}

func TestRawHTMLAndScriptLinksAreDropped(t *testing.T) {
	out, _ := Render("n.md", "<script>alert(1)</script>\n\n[x](javascript:alert(1)) <img src=x onerror=alert(1)>\n")
	if strings.Contains(out, "<script") || strings.Contains(out, "javascript:") || strings.Contains(out, "onerror") {
		t.Fatalf("unsafe output:\n%s", out)
	}
}

func TestRelativeLinksAndImages(t *testing.T) {
	out, _ := Render("journal/2026/day.md",
		"![pic](../img/a%20b.png) ![web](https://x.org/i.png)\n\n[other](../other.md#h) [plain](notes) [pdf](files/r.pdf) [top](#sec) [out](../../../x.md)\n")
	for _, want := range []string{
		`src="/raw/journal/img/a%20b.png"`, `src="https://x.org/i.png"`,
		`href="#/n/journal/other.md"`, `href="#/n/journal/2026/notes.md"`,
		`href="/raw/journal/2026/files/r.pdf"`, `href="#sec"`, `href="../../../x.md"`,
	} {
		if !strings.Contains(out, want) {
			t.Errorf("missing %q in:\n%s", want, out)
		}
	}
}

func TestFrontmatter(t *testing.T) {
	cases := []struct{ in, front, body string }{
		{"---\ntitle: x\ntags: [a]\n---\n# Hi\n", "title: x\ntags: [a]", "# Hi\n"},
		{"---\r\na: 1\r\n---\r\nbody", "a: 1", "body"},
		{"---\n---\nbody", "", "body"},
		{"---\na: 1\n---", "a: 1", ""},
		{"# no front\n---\n", "", "# no front\n---\n"},
		{"---\nunclosed", "", "---\nunclosed"},
	}
	for _, c := range cases {
		f, b := SplitFrontmatter(c.in)
		if f != c.front || b != c.body {
			t.Errorf("SplitFrontmatter(%q) = %q, %q; want %q, %q", c.in, f, b, c.front, c.body)
		}
	}
	out, _ := Render("n.md", "---\ntitle: <b>\n---\ntext")
	if !strings.Contains(out, `<pre class="frontmatter"><code>title: &lt;b&gt;</code></pre>`) {
		t.Fatalf("frontmatter block:\n%s", out)
	}
}
