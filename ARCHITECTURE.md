# samanote — architecture

Internal notes for working on samanote. No secrets or host details here; those live
in `.env` only.

## Shape

```
browser ──HTTPS──▶ Cloudflare / Caddy ──▶ samanote:8080 (homelab network, no published port)
                                              │
                                              ▼
                                   /notes  (bind mount of NOTE_PATH — the Obsidian vault)
                                              ▲
                     Obsidian · Syncthing · Omnimo · Kronos edit the same files, unlocked
```

One Go binary serves the API and the embedded front end. State is the vault itself;
samanote keeps nothing else (no index, no database, no cache files in the vault).

## Code map

```
cmd/samanote/main.go     serve | hash-password | health | version
internal/config/         SAMANOTE_* env (+ optional .env in the working dir) → Config, validated at start
internal/auth/           password check (plain | bcrypt), HMAC-signed session cookie, sign-in rate limit, /api/login|logout|me
internal/httpx/          JSON helpers, StatusError, Router (session check + X-Samanote header + panic recovery)
internal/vault/          the only code that touches files: path safety, tree, read, versioned atomic write, create, move, trash
internal/render/         goldmark (GFM + footnotes + hard wraps), frontmatter block, relative link/image rewriting
internal/server/         routes, error → status mapping, /raw, embedded assets (ETag + gzip), security headers
web/embed.go             go:embed of web/static
web/static/
  index.html             sign-in card + app shell + SVG icon sprite
  css/nss-app.css        shared nss look for apps (same tokens as nss 3.0) — copy unchanged into Natlas/Arthik
  css/samanote.css       app-only layout, tree, panes, CodeMirror colours, preview typography
  js/main.js             app logic: routing, open/save/conflict, views, file actions, sidebar, resizers
  js/editor.js           CodeMirror setup (markdown, frontmatter block, keymaps, nss highlight style)
  js/preview.js          debounced server render, link handling, image/binary view, scroll follow
  js/tree.js             file tree + filter
  js/api.js              fetch wrapper (adds X-Samanote, maps errors, 401 → sign-in)
  js/ui.js               h() element builder, prefs (localStorage), toast, sheet prompt/confirm/menu
  vendor/codemirror.js   CodeMirror 6 bundle (built by tools/codemirror, committed)
tools/codemirror/        package.json + entry.js + stub-html.js to rebuild the bundle
```

## HTTP API

All `/api/*` except login/logout/me need the session cookie. Anything that is not
GET/HEAD also needs the `X-Samanote` header (CSRF guard: a cross-site page cannot add
custom headers without CORS, which is never granted; cookies are `SameSite=Lax`).

| Method | Path | Body → reply |
|---|---|---|
| POST | `/api/login` | `{user, password}` → sets `samanote_session` |
| POST | `/api/logout` | clears the cookie |
| GET | `/api/me` | `{signed_in}` |
| GET | `/api/config` | `{version, autosave_ms, max_bytes}` |
| GET | `/api/tree` | nested `{name, path, dir, size, mtime, children}` |
| GET | `/api/file?path=` | `{path, content, version, size, mtime, binary?, readonly?, too_large?}` |
| GET | `/api/version?path=` | `{version}` — cheap "did it change?" check |
| PUT | `/api/file?path=` | `{content, version, force}` → `{version}` or **409** `{conflict, current, version, deleted}` |
| POST | `/api/new` | `{path, folder}` → `{path}` (adds `.md` to a note without extension) |
| POST | `/api/move` | `{from, to}` → `{path}` (never overwrites; a folder cannot go into itself) |
| POST | `/api/trash` | `{path}` → `{trash}` (`.trash/<name>`, `name 2.md` on a clash) |
| POST | `/api/render` | `{path, content}` → `{html}` |
| GET | `/raw/<path>` | the file as-is, own CSP `sandbox`; HTML/JS/XML sent as `text/plain` |
| GET | `/healthz` | `ok` (Docker healthcheck via `samanote health`) |

## Rules that matter

**Paths.** `vault.Clean` normalises against the root (so `..` cannot climb out),
refuses NUL and backslashes, and refuses any segment starting with `.` plus the names
or paths in `SAMANOTE_HIDDEN`. `vault.Resolve` then follows symlinks on the path (or its
nearest existing parent, for new files) and refuses anything that lands outside the
real vault root. The tree skips symlinked folders entirely.

**Versions and conflicts.** A version is the first 12 bytes of SHA-256 of the file
content (mtime is ignored on purpose: touching a file is not a change). The browser
keeps the version its text is based on and sends it with every save. The server
re-reads the file under a mutex and refuses with 409 if the content differs, returning
what is on disk, or `deleted: true` if the file is gone. `force: true` ("keep mine",
"recreate it") skips the check. A save of identical content does not rewrite the file.

**Writes.** Temp file `.samanote-*.tmp` in the same folder → write → fsync → chmod to
the old mode → rename over the target. Readers only ever see the old or the new file.
The temp name starts with a dot, so Obsidian and the tree ignore it.

**Trash.** Trash moves into `<vault>/<SAMANOTE_TRASH>` (default `.trash`), flat, with
" 2", " 3" … before the extension when a name is taken. samanote never deletes.

**Rendering.** Server side only, so preview and saved file always use the same
parser. goldmark's safe defaults: raw HTML is omitted and dangerous URLs
(`javascript:` etc.) dropped. Frontmatter is cut off by `SplitFrontmatter` and shown
escaped in `<pre class="frontmatter">`. Relative image → `/raw/<resolved>`; relative
link to `.md`/`.txt`/no extension → `#/n/<resolved>`; other relative link → `/raw/…`;
anything that would leave the vault is left untouched (and fails harmlessly).

**Browser checks for outside edits.** On window focus, on returning to the tab and
every 30 s, the browser asks `/api/version`. Same version: nothing. Different and no
unsaved edits: reload quietly (cursor and scroll kept). Different with unsaved edits:
save, which brings up the conflict bar. 404: the "deleted" conflict bar.

## Front end notes

- No framework, ES modules only, no build step except the CodeMirror bundle.
- CodeMirror's `@codemirror/lang-html` is replaced with a stub at bundle time (HTML-in-markdown highlighting and tag completion are not needed); that saves ~160 KB. The bundle is ~355 KB, ~118 KB gzipped; the server gzips embedded assets once at start-up and serves them with ETags (`Cache-Control: no-cache` → cheap 304s, new deploys show at once).
- CSRF/XSS: CSP is `script-src 'self'`; `style-src` allows inline because CodeMirror injects its styles. Preview HTML comes only from the server renderer.
- Per-device settings in `localStorage` (`samanote.*`): view, split %, sidebar width/collapsed, open folders, last note, theme. Everything works if storage is blocked.
- Routing: `#/n/<url-encoded path>`; back/forward work; the last note reopens on start.
- Views: `edit | split | preview`; under 820 px "split" shows the editor only and the Split button hides. Under 900 px the sidebar is a drawer.

## Shared nss for apps

`css/nss-app.css` = nss 3.0 tokens (same variable names and values, dark default,
light by system, `data-theme` override) + app components (`.top`, `.side` drawer,
buttons, inputs, `.seg`, `dialog.sheet`, `.menu`, `.toast`, `.badge`, sign-in `.card`).
The plan is to drop the same file into Natlas v2 and Arthik and keep only app-specific
rules in each app's own CSS.

## Deploy (homelab)

1. The shared `.env` (symlinked into the compose folder) already has `NOTE_PATH`, `PUID`, `PGID`, `TZ`; add the `SAMANOTE_*` keys.
2. `git clone` into the compose folder (keep the `.env` symlink), then `docker compose up -d --build`.
3. The container is named `samanote` on the external `homelab` network, so the existing Caddy block (`reverse_proxy samanote:8080`) works unchanged.
