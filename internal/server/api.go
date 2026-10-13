package server

import (
	"errors"
	"io"
	"mime"
	"net/http"
	"os"
	"path"
	"sort"
	"strings"
	"sync"
	"time"

	"gitlab.com/niharokz/mnemos/internal/httpx"
	"gitlab.com/niharokz/mnemos/internal/render"
	"gitlab.com/niharokz/mnemos/internal/search"
	"gitlab.com/niharokz/mnemos/internal/vault"
)

// api is the notes API. The vault always comes from the signed-in user.
type api struct {
	opt     Options
	mu      sync.Mutex
	indexes map[*vault.Vault]*search.Index
}

func vaultOf(r *http.Request) *vault.Vault { return httpx.UserOf(r).Vault }

func ok(w http.ResponseWriter, v any) error {
	httpx.JSON(w, http.StatusOK, v)
	return nil
}

func (a *api) config(w http.ResponseWriter, r *http.Request) error {
	u := httpx.UserOf(r)
	return ok(w, map[string]any{
		"version": a.opt.Version, "autosave_ms": a.opt.AutosaveMS, "max_bytes": u.Vault.MaxBytes(),
		"user": u.Name, "read_only": u.ReadOnly, "capture": a.opt.CaptureNote, "templates": a.opt.TemplatesDir,
		"max_upload_bytes": int64(a.opt.MaxUploadMB) << 20,
	})
}

func (a *api) tree(w http.ResponseWriter, r *http.Request) error {
	t, err := vaultOf(r).Tree()
	if err != nil {
		return err
	}
	return ok(w, t)
}

func (a *api) read(w http.ResponseWriter, r *http.Request) error {
	f, err := vaultOf(r).Read(r.URL.Query().Get("path"))
	if err != nil {
		return vaultErr(err)
	}
	return ok(w, f)
}

// version is the cheap "did it change?" check the browser runs on focus.
func (a *api) version(w http.ResponseWriter, r *http.Request) error {
	ver, err := vaultOf(r).Stat(r.URL.Query().Get("path"))
	if err != nil {
		return vaultErr(err)
	}
	return ok(w, map[string]string{"version": ver})
}

// folder is the box view: one folder with short previews of its notes.
func (a *api) folder(w http.ResponseWriter, r *http.Request) error {
	cards, err := vaultOf(r).Folder(r.URL.Query().Get("path"))
	if err != nil {
		return vaultErr(err)
	}
	return ok(w, cards)
}

// index returns the search index of the request's vault, made on first use.
func (a *api) index(r *http.Request) *search.Index {
	v := vaultOf(r)
	a.mu.Lock()
	defer a.mu.Unlock()
	ix := a.indexes[v]
	if ix == nil {
		ix = search.New(v)
		a.indexes[v] = ix
	}
	return ix
}

func (a *api) search(w http.ResponseWriter, r *http.Request) error {
	ix := a.index(r)
	q := r.URL.Query().Get("q")
	if len(q) > 200 {
		return httpx.Errorf(http.StatusBadRequest, "query too long")
	}
	res, err := ix.Search(q, 100)
	if err != nil {
		return err
	}
	return ok(w, res)
}

// templates lists the notes in the templates folder (empty when there is none).
func (a *api) templates(w http.ResponseWriter, r *http.Request) error {
	cards, err := vaultOf(r).Folder(a.opt.TemplatesDir)
	out := []string{}
	if err == nil {
		for _, c := range cards {
			if !c.Dir {
				out = append(out, c.Path)
			}
		}
	}
	sort.Strings(out)
	return ok(w, map[string]any{"folder": a.opt.TemplatesDir, "templates": out})
}

func (a *api) trashList(w http.ResponseWriter, r *http.Request) error {
	items, err := vaultOf(r).ListTrash()
	if err != nil {
		return vaultErr(err)
	}
	return ok(w, items)
}

func (a *api) conflicts(w http.ResponseWriter, r *http.Request) error {
	list, err := vaultOf(r).Conflicts()
	if err != nil {
		return err
	}
	return ok(w, list)
}

func (a *api) write(w http.ResponseWriter, r *http.Request) error {
	v := vaultOf(r)
	var in struct {
		Content string `json:"content"`
		Version string `json:"version"`
		Force   bool   `json:"force"`
	}
	if err := httpx.Decode(w, r, v.MaxBytes()*2+64<<10, &in); err != nil {
		return err
	}
	ver, err := v.Write(r.URL.Query().Get("path"), in.Content, in.Version, in.Force)
	if err != nil {
		return vaultErr(err)
	}
	return ok(w, map[string]string{"version": ver})
}

// create makes a note or folder; with a template, the note starts with the
// template's text ({{date}}, {{time}} and {{title}} filled in).
func (a *api) create(w http.ResponseWriter, r *http.Request) error {
	v := vaultOf(r)
	var in struct {
		Path     string `json:"path"`
		Folder   bool   `json:"folder"`
		Template string `json:"template"`
	}
	if err := httpx.Decode(w, r, 16<<10, &in); err != nil {
		return err
	}
	var body string
	if in.Template != "" && !in.Folder {
		f, err := v.Read(in.Template)
		if err != nil {
			return vaultErr(err)
		}
		if f.Binary || f.TooLarge {
			return httpx.Errorf(http.StatusBadRequest, "the template is not a text file")
		}
		body = fill(f.Content, in.Path)
	}
	p, err := v.Create(in.Path, in.Folder)
	if err != nil {
		return vaultErr(err)
	}
	if body != "" {
		if _, err := v.Write(p, body, vault.Version(nil), false); err != nil {
			return vaultErr(err)
		}
	}
	return ok(w, map[string]string{"path": p})
}

// fill replaces template variables.
func fill(tpl, notePath string) string {
	now := time.Now()
	title := strings.TrimSuffix(path.Base(notePath), path.Ext(notePath))
	return strings.NewReplacer("{{date}}", now.Format("2006-01-02"), "{{time}}", now.Format("15:04"), "{{title}}", title).Replace(tpl)
}

func (a *api) move(w http.ResponseWriter, r *http.Request) error {
	var in struct{ From, To string }
	if err := httpx.Decode(w, r, 16<<10, &in); err != nil {
		return err
	}
	p, err := vaultOf(r).Move(in.From, in.To)
	if err != nil {
		return vaultErr(err)
	}
	return ok(w, map[string]string{"path": p})
}

func (a *api) trash(w http.ResponseWriter, r *http.Request) error {
	var in struct{ Path string }
	if err := httpx.Decode(w, r, 16<<10, &in); err != nil {
		return err
	}
	p, err := vaultOf(r).Trash(in.Path)
	if err != nil {
		return vaultErr(err)
	}
	return ok(w, map[string]string{"trash": p})
}

func (a *api) restore(w http.ResponseWriter, r *http.Request) error {
	var in struct{ Name, To string }
	if err := httpx.Decode(w, r, 16<<10, &in); err != nil {
		return err
	}
	p, err := vaultOf(r).Restore(in.Name, in.To)
	if err != nil {
		return vaultErr(err)
	}
	return ok(w, map[string]string{"path": p})
}

// capture appends "- YYYY-MM-DD HH:mm text" to the capture note; extra lines are indented.
func (a *api) capture(w http.ResponseWriter, r *http.Request) error {
	var in struct{ Text string }
	if err := httpx.Decode(w, r, 64<<10, &in); err != nil {
		return err
	}
	text := strings.TrimSpace(strings.ReplaceAll(in.Text, "\r\n", "\n"))
	if text == "" {
		return httpx.Errorf(http.StatusBadRequest, "nothing to capture")
	}
	line := "- " + time.Now().Format("2006-01-02 15:04") + " " + strings.ReplaceAll(text, "\n", "\n  ")
	p, err := vaultOf(r).Append(a.opt.CaptureNote, line)
	if err != nil {
		return vaultErr(err)
	}
	return ok(w, map[string]string{"path": p})
}

// attach saves an uploaded image next to a note (in its attachments/ folder) and
// returns the new file's vault path and the markdown to insert. The body is the
// raw image; ?note= is the note it is for, ?name= the suggested file name.
func (a *api) attach(w http.ResponseWriter, r *http.Request) error {
	limit := int64(a.opt.MaxUploadMB) << 20
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, limit+1))
	if err != nil {
		return httpx.Errorf(http.StatusRequestEntityTooLarge, "the image is bigger than %d MB", a.opt.MaxUploadMB)
	}
	note := r.URL.Query().Get("note")
	p, err := vaultOf(r).SaveAttachment(note, r.URL.Query().Get("name"), body)
	if err != nil {
		return vaultErr(err)
	}
	alt := strings.TrimSuffix(path.Base(p), path.Ext(p))
	return ok(w, map[string]string{"path": p, "markdown": "![" + alt + "](" + vault.RelLink(note, p) + ")"})
}

func (a *api) resolve(w http.ResponseWriter, r *http.Request) error {
	v := vaultOf(r)
	var in struct {
		Copy    string `json:"copy"`
		Keep    string `json:"keep"`
		Content string `json:"content"`
	}
	if err := httpx.Decode(w, r, v.MaxBytes()*2+64<<10, &in); err != nil {
		return err
	}
	p, err := v.ResolveConflict(in.Copy, in.Keep, in.Content)
	if err != nil {
		return vaultErr(err)
	}
	return ok(w, map[string]string{"path": p})
}

func (a *api) render(w http.ResponseWriter, r *http.Request) error {
	var in struct{ Path, Content string }
	if err := httpx.Decode(w, r, vaultOf(r).MaxBytes()*2+64<<10, &in); err != nil {
		return err
	}
	out, err := render.Render(in.Path, in.Content, a.index(r).Resolve)
	if err != nil {
		return err
	}
	return ok(w, map[string]string{"html": out})
}

// raw serves a vault file as-is for the preview (images) and "open raw". Anything
// that a browser could run (HTML, SVG, JS) is sandboxed by its own CSP, and HTML
// and scripts are sent as plain text.
func (a *api) raw(w http.ResponseWriter, r *http.Request) error {
	abs, err := vaultOf(r).RawPath(r.PathValue("path"))
	if err != nil {
		return vaultErr(err)
	}
	f, err := os.Open(abs)
	if err != nil {
		return vaultErr(vault.ErrNotFound)
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return err
	}
	ext := strings.ToLower(path.Ext(abs))
	ct := mime.TypeByExtension(ext)
	switch {
	case ext == ".html" || ext == ".htm" || ext == ".js" || ext == ".mjs" || ext == ".xhtml" || ext == ".xml":
		ct = "text/plain; charset=utf-8"
	case ct == "" || strings.HasPrefix(ct, "text/"):
		ct = "text/plain; charset=utf-8"
	}
	h := w.Header()
	h.Set("Content-Type", ct)
	h.Set("Content-Security-Policy", "sandbox; default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'; media-src 'self'")
	h.Set("Cache-Control", "private, no-cache")
	if r.URL.Query().Get("download") == "1" {
		h.Set("Content-Disposition", mime.FormatMediaType("attachment", map[string]string{"filename": info.Name()}))
	}
	http.ServeContent(w, r, info.Name(), info.ModTime(), f)
	return nil
}

// vaultErr maps vault errors to HTTP statuses and clear messages.
func vaultErr(err error) error {
	var ce *vault.ConflictError
	if errors.As(err, &ce) {
		return &httpx.StatusError{Code: http.StatusConflict, Msg: ce.Error(), Body: map[string]any{
			"conflict": true, "current": ce.Current, "version": ce.Version, "deleted": ce.Deleted,
		}}
	}
	var pe *vault.PermissionError
	switch {
	case errors.As(err, &pe):
		return httpx.Errorf(http.StatusForbidden, "%s", pe.Error())
	case errors.Is(err, vault.ErrNotFound):
		return httpx.Errorf(http.StatusNotFound, "%s", "not found — it may have been moved or deleted")
	case errors.Is(err, vault.ErrExists):
		return httpx.Errorf(http.StatusConflict, "%s", err.Error())
	case errors.Is(err, vault.ErrHidden):
		return httpx.Errorf(http.StatusForbidden, "%s", err.Error())
	case errors.Is(err, vault.ErrTooLarge):
		return httpx.Errorf(http.StatusRequestEntityTooLarge, "%s", err.Error())
	case errors.Is(err, vault.ErrNotImage):
		return httpx.Errorf(http.StatusUnsupportedMediaType, "%s", err.Error())
	case errors.Is(err, vault.ErrBadPath), errors.Is(err, vault.ErrIsDir):
		return httpx.Errorf(http.StatusBadRequest, "%s", err.Error())
	}
	return err
}
