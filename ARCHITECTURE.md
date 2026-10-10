# samanote — architecture

Internal notes for working on samanote. No secrets or host details here; those live
in `.env` only.

## Shape

```
browser ──HTTPS──▶ Cloudflare / Caddy ──▶ samanote:8080 (shared Docker network, no published port)
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
cmd/samanote/main.go     serve | hash-password | check | health | version
internal/config/         SAMANOTE_* env (+ optional .env in the working dir) → Config, validated at start
internal/auth/           password check (plain | bcrypt), HMAC-signed session cookie, sign-in rate limit, /api/login|logout|me
internal/httpx/          JSON helpers, StatusError, Router (session check + X-Samanote header + panic recovery)
internal/vault/          the only code that touches files: path safety, tree, read, versioned atomic write, create, move, trash
internal/render/         goldmark (GFM + footnotes + hard wraps), frontmatter block, relative link/image rewriting
internal/server/         routes, error → status mapping, /raw, embedded assets (ETag + gzip), security headers
web/embed.go             go:embed of web/static
web/static/
  landing.html           public about page at / (install steps, features, Sign in)
  index.html             the app at /app: sign-in card + app shell + SVG icon sprite
  css/nss-app.css        shared nss look for apps (same tokens as nss 3.0) — copy unchanged into Natlas/Arthik
  css/samanote.css       app-only layout, tree, panes, CodeMirror colours, preview typography
  js/main.js             app logic: routing, open/save/conflict, views, file actions, sidebar, resizers
  js/editor.js           CodeMirror setup (markdown, frontmatter block, keymaps, nss highlight style)
  js/preview.js          debounced server render, link handling, image/binary view, scroll follow
  js/tree.js             file tree + filter
  js/api.js              fetch wrapper (adds X-Samanote, maps errors, 401 → sign-in)
  js/ui.js               h() element builder, prefs (localStorage), toast, sheet prompt/confirm/menu
  css/landing.css        about-page layout (shared with Natlas/Arthik)
  js/landing.js          about page: sends installed-app launches and old /#/n/ links to /app
  vendor/codemirror.js   CodeMirror 6 bundle (built by tools/codemirror, committed)
  vendor/nss.min.css     nss subset used by the about page
tools/codemirror/        package.json + entry.js + stub-html.js to rebuild the bundle
scripts/check-public.sh  pre-push hook: blocks committed secrets and private words
vendor/                  Go dependencies (goldmark, x/crypto/bcrypt) — builds need no network
```

## HTTP API

All `/api/*` except login/logout/me need the session cookie. Anything that is not
GET/HEAD also needs the `X-Samanote` header (CSRF guard: a cross-site page cannot add
custom headers without CORS, which is never granted; cookies are `SameSite=Lax`).

| Method | Path | Body → reply |
|---|---|---|
| GET | `/` | public about page (`{{VERSION}}` filled in) |
| GET | `/app` | the app; `/app/` redirects here |
| GET | `/robots.txt` | allows `/`, disallows `/app`, `/api/`, `/raw/` |
| POST | `/api/login` | `{user, password}` → sets `__Host-samanote` (HTTPS) or `samanote_session` (plain http) |
| POST | `/api/logout` | clears both cookies |
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

## Security model

- **Session.** Cookie value is `base64(user|expiry|HMAC-SHA256)`. The HMAC key is `SHA-256("samanote-session" | SAMANOTE_SECRET | password)`, so changing the password or the secret invalidates every session. Over HTTPS the cookie is `__Host-samanote` (Secure, Path=/, no Domain). 30 days, HttpOnly, SameSite=Lax.
- **CSRF.** Every non-GET request needs the `X-Samanote` header *and* must pass `httpx.SameOrigin`: `Sec-Fetch-Site` must be `same-origin`/`none` when present, and `Origin` must match `Host` when present. Login and logout too.
- **Login.** Constant-time user + password check (bcrypt or plain). 5 failures per IP per 15 min → 429; each failure is slowed a little more. The limiter holds at most 10 000 IPs.
- **Client IP.** `CF-Connecting-IP`, then `X-Real-IP`, then the **last** `X-Forwarded-For` entry, then the socket. Only valid IPs are accepted. This trusts your proxy; the container must not be reachable except through it.
- **Headers.** CSP (`script-src 'self'`; inline styles allowed for CodeMirror), HSTS over HTTPS, X-Frame-Options DENY, nosniff, Referrer-Policy same-origin, COOP/CORP same-origin, Permissions-Policy. `/raw` adds its own `sandbox` CSP.
- **Errors.** Unexpected errors are logged in full and returned as "internal error — see the server log" (no server paths in the browser). Permission problems return 403 naming the vault-relative path and the uid.
- **Logging.** Every non-GET request (except successful previews) is logged as `ip method path [note] → status (duration)`; sign-ins and failed sign-ins are logged with the IP. Never bodies, passwords or note contents.
- **Server limits.** ReadHeaderTimeout 10 s, Read 30 s, Write 60 s, Idle 120 s, headers ≤ 64 KB, JSON bodies ≤ 2×max file size.
- **Container.** `FROM scratch`, runs as PUID:PGID, read-only root, `cap_drop: ALL`, `no-new-privileges`, only `/notes` writable, no published port.
- **Startup checks.** A placeholder password (`change-me`, …) refuses to start; an empty or short secret and a plain-text password are warned about.

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
If the folder itself is not writable for PUID:PGID (e.g. created by a container
running as root) but the file is, the save falls back to overwriting the file in
place, and a warning is logged once per folder. Anything else the OS refuses comes
back as a 403 that names the path and the uid, telling you to fix the ownership.

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
- Routing: the app lives at `/app`; notes are `/app#/n/<url-encoded path>`, `/app#/new` opens "New note" (home-screen shortcut). Back/forward work; the last note reopens on start.
- Views: `edit | split | preview`; under 820 px "split" shows the editor only and the Split button hides. Under 900 px the sidebar is a drawer.

## Shared nss for apps

`css/nss-app.css` = nss 3.0 tokens (same variable names and values, dark default,
light by system, `data-theme` override) + app components (`.top`, `.side` drawer,
buttons, inputs, `.seg`, `dialog.sheet`, `.menu`, `.toast`, `.badge`, sign-in `.card`).
The plan is to drop the same file into Natlas v2 and Arthik and keep only app-specific
rules in each app's own CSS.

## Deploy

1. `.env` (or a shared `.env` symlinked into the folder) holds `NOTE_PATH`, `PUID`, `PGID`, `TZ`, `SAMANOTE_NETWORK` and the `SAMANOTE_*` keys. Compose passes only `TZ` and `SAMANOTE_*` into the container.
2. `docker compose run --rm samanote check` — settings plus every folder/file the container user cannot write.
3. `docker compose up -d --build`. The container is `samanote` on the external network, so the proxy block is `reverse_proxy samanote:8080`.
4. Release: bump `VERSION` in `docker-compose.yml` and `version` in `cmd/samanote/main.go`, add a `CHANGELOG.md` entry.
