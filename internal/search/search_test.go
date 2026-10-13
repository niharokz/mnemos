package search

import (
	"os"
	"path/filepath"
	"testing"

	"gitlab.com/niharokz/mnemos/internal/vault"
)

func index(t *testing.T, files map[string]string) *Index {
	t.Helper()
	dir := t.TempDir()
	for p, s := range files {
		abs := filepath.Join(dir, filepath.FromSlash(p))
		os.MkdirAll(filepath.Dir(abs), 0o755)
		if err := os.WriteFile(abs, []byte(s), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	v, err := vault.New(dir, ".trash", nil, 5<<20)
	if err != nil {
		t.Fatal(err)
	}
	return New(v)
}

func paths(t *testing.T, ix *Index, q string) []string {
	t.Helper()
	r, err := ix.Search(q, 50)
	if err != nil {
		t.Fatal(err)
	}
	var out []string
	for _, h := range r.Hits {
		out = append(out, h.Path)
	}
	return out
}

func TestSearch(t *testing.T) {
	ix := index(t, map[string]string{
		"notes/garden.md":  "---\nstatus: open\ntags: [home, plants]\n---\n# Garden\nRepot the #mint soon\n",
		"notes/todo.md":    "Buy coco peat\n```\n#notatag\n```\n",
		"Markdown tour.md": "every feature",
		".trash/garden.md": "hidden",
		"tables/runs.csv":  "city,km\nPuri,21\n",
		"images/teal.png":  "\x89PNG\x00\x00",
	})
	cases := []struct {
		q    string
		want []string
	}{
		{"garden", []string{"notes/garden.md"}},       // name, dot folders hidden
		{"coco", []string{"notes/todo.md"}},           // full text
		{"#mint", []string{"notes/garden.md"}},        // body tag
		{"#plants", []string{"notes/garden.md"}},      // front-matter tag
		{"#notatag", nil},                             // code fences skipped
		{"status: open", []string{"notes/garden.md"}}, // property, spaced
		{"status:closed", nil},                        // property mismatch
		{"mtour", []string{"Markdown tour.md"}},       // fuzzy name
		{"puri", []string{"tables/runs.csv"}},         // any text file
		{"", nil},
	}
	for _, c := range cases {
		got := paths(t, ix, c.q)
		if len(got) != len(c.want) || (len(got) > 0 && got[0] != c.want[0]) {
			t.Errorf("%q: got %v, want %v", c.q, got, c.want)
		}
	}
	r, _ := ix.Search("coco", 5)
	if r.Hits[0].Line != 1 || r.Hits[0].Snippet != "Buy coco peat" {
		t.Errorf("snippet: %+v", r.Hits[0])
	}
}

func TestRefreshSeesChanges(t *testing.T) {
	ix := index(t, map[string]string{"a.md": "alpha"})
	if len(paths(t, ix, "beta")) != 0 {
		t.Fatal("beta before write")
	}
	os.WriteFile(filepath.Join(ix.v.Root(), "b.md"), []byte("beta"), 0o644)
	ix.scanned = ix.scanned.AddDate(0, 0, -1) // skip the 3 s cache
	if got := paths(t, ix, "beta"); len(got) != 1 {
		t.Fatalf("after write: %v", got)
	}
}

func TestResolve(t *testing.T) {
	ix := index(t, map[string]string{
		"notes/day.md":                "x",
		"notes/attachments/photo.png": "\x89PNG",
		"other/photo.png":             "\x89PNG",
		"photo.png":                   "\x89PNG",
		"img/logo.webp":               "RIFF",
	})
	cases := []struct{ target, from, want string }{
		{"photo.png", "notes", "notes/attachments/photo.png"}, // closest to the note
		{"photo.png", "", "photo.png"},                        // exact vault path
		{"attachments/photo.png", "notes", "notes/attachments/photo.png"},
		{"logo.webp", "notes", "img/logo.webp"},
		{"../photo.png", "notes", "photo.png"}, // cleaned, cannot climb out
		{"missing.png", "notes", ""},
	}
	for _, c := range cases {
		got, ok := ix.Resolve(c.target, c.from)
		if got != c.want || ok != (c.want != "") {
			t.Errorf("Resolve(%q, %q) = %q, %v; want %q", c.target, c.from, got, ok, c.want)
		}
	}
}
