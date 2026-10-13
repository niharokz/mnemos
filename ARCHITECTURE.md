# Mnemos — architecture

Internal notes for working on Mnemos. No secrets or host details here; those live in
`.env` only.

## Shape

```
browser ──HTTPS──▶ Cloudflare / Caddy ──▶ mnemos:8080 (shared Docker network, no published port)
                                              │
                         owner ───────────────┼────────────── demo user (read-only)
                                              ▼                        ▼
                     /notes  (bind mount of NOTE_PATH)        /tmp/mnemos-demo-*  (sample vault,
                                              ▲                unpacked from the binary at start)
      Obsidian · Syncthing · Mnemos for Android · scripts edit the same files, unlocked
```

One Go binary serves the API and the embedded front end. State is the vault itself;
Mnemos keeps nothing else in it except `.trash/.mnemos-trash.tsv` (where trashed files
came from). The search index is in memory.

The Android app (`android/`) is a separate Gradle project with its own
[README](android/README.md) and [ARCHITECTURE](android/ARCHITECTURE.md). The only
thing the two share at build time is `android/demo-vault/`, which Go embeds.

## Code map

```
assets.go                go:embed android/demo-vault and VERSION (root package "mnemos")
cmd/mnemos/main.go       serve | hash-password | check | health | version; builds the accounts
internal/config/         MNEMOS_* env (SAMANOTE_* fallback, + optional .env) → Config, validated at start
internal/auth/           accounts (plain | bcrypt), per-account HMAC session cookie, sign-in rate limit, /api/login|logout|me
internal/httpx/          JSON helpers, StatusError, User in the request context, Router (session, X-Mnemos, same-origin, read-only, panics)
internal/vault/          the only code that touches files: path safety, tree, read, versioned atomic write, create, move, trash
  extra.go               trash ledger + restore, append (capture), Syncthing conflicts + resolve, folder cards
internal/search/         in-memory index per vault: names, text, #tags, front matter; refreshed by size/mtime
internal/demo/           builds the demo vault in a temp dir (embedded files + generated log and CSV)
internal/render/         goldmark (GFM + footnotes + hard wraps), frontmatter block, relative link/image rewriting
internal/server/         routes, handlers (api.go), error → status mapping, /raw, embedded assets, security headers
web/embed.go             go:embed of web/static
web/static/
  landing.html           public about page at / (features, Android install from GitLab, self-hosting)
  index.html             the app at /app: sign-in card (+ demo box) + app shell + SVG icon sprite
  css/nss-app.css        shared nss look for apps (same tokens as nss 3.0)
  css/mnemos.css         app layout, tree, panes, editor, preview, table, cards, diff, sheets
  css/landing.css        about-page layout (shared pattern with Natlas/Arthik)
  js/main.js             app logic: routes, open/save/conflict, views, file actions, pins, demo mode
  js/editor.js           CodeMirror setup, formatting bar, task toggle, goto line
  js/preview.js          debounced server render, tickable tasks, links, image/binary view
  js/table.js + csv.js   CSV/TSV grid; parser keeps each record's source bytes
  js/panels.js + diff.js search, recent, trash, capture, templates, conflict list and compare/merge
  js/cards.js            folder as cards (image thumbnails)
  js/images.js           paste / drop / pick images → /api/attach; full-screen lightbox
  js/tree.js             file tree + filter
  js/api.js              fetch wrapper (X-Mnemos, error mapping, 401 → sign-in)
  js/ui.js               h(), prefs (localStorage), toast, sheets
  js/landing.js          about page: sends installed-app launches and old /#/… links to /app
  vendor/codemirror.js   CodeMirror 6 bundle (built by tools/codemirror, committed)
  vendor/nss.min.css     nss subset used by the about page
android/                 Mnemos for Android (Compose, offline); build.sh builds it in Docker
tools/codemirror/        package.json + entry.js + stub-html.js to rebuild the bundle
scripts/check-public.sh  pre-push hook: blocks committed secrets, signing keys and private words
scripts/check-version.sh    VERSION, android/version.properties and changelogs agree (CI runs it)
scripts/release.sh          APK → GitLab package registry + release v<version> with a stable "mnemos.apk" link
.gitlab-ci.yml              checks (Go, front end, versions, secrets, Docker, Android) and tag releases
scripts/make-web-icons.py   web icons from the Android launcher mark
vendor/                  Go dependencies (goldmark, x/crypto/bcrypt) — builds need no network
```

## Users

`main.go` builds a list of `auth.Account`s, each carrying an `httpx.User{Name, ReadOnly, Vault}`:

- the owner: `MNEMOS_USER` / `MNEMOS_PASSWORD`, read-write, the real vault;
- the demo (unless `MNEMOS_DEMO=false`): `MNEMOS_DEMO_USER` / `MNEMOS_DEMO_PASSWORD`,
  read-only, a fresh copy of the sample vault in `/tmp`, credentials shown by `/api/me`.

The session cookie names the user; `auth.Resolve` turns it back into the `User`, and
every handler takes the vault from the request context (`vaultOf(r)`). There is no
code path from the demo account to the real vault.

## HTTP API

All `/api/*` except login/logout/me need the session cookie. Anything that is not
GET/HEAD also needs the `X-Mnemos` header and must be same-origin. For a read-only user
the router refuses every non-GET with **403** `{error, read_only: true}` — except
`POST /api/render`, which changes nothing (`Router.HandleSafe`).

| Method | Path | Body → reply |
|---|---|---|
| GET | `/` | public about page (`{{VERSION}}` filled in) |
| GET | `/app` | the app; `/app/` redirects here |
| GET | `/robots.txt` | allows `/`, disallows `/app`, `/api/`, `/raw/` |
| POST | `/api/login` | `{user, password}` → `{ok, user, read_only}`; sets `__Host-mnemos` (HTTPS) or `mnemos_session` |
| POST | `/api/logout` | clears both cookies |
| GET | `/api/me` | `{signed_in, user?, read_only?, demo?: {user, password}}` |
| GET | `/api/config` | `{version, autosave_ms, max_bytes, user, read_only, capture, templates}` |
| GET | `/api/tree` | nested `{name, path, dir, size, mtime, children}` |
| GET | `/api/file?path=` | `{path, content, version, size, mtime, binary?, readonly?, too_large?}` |
| GET | `/api/version?path=` | `{version}` — cheap "did it change?" check |
| GET | `/api/folder?path=` | `[{name, path, dir?, size, mtime, preview?, items?}]` — cards |
| GET | `/api/search?q=` | `{hits: [{path, name, score, snippet?, line?, tags?}], total, files, ms}` (≤ 100 hits) |
| GET | `/api/templates` | `{folder, templates: [path]}` |
| GET | `/api/trash` | `[{name, dir?, size, mtime, original?}]`, newest first |
| GET | `/api/conflicts` | `[{copy, original, exists, when, device}]` |
| PUT | `/api/file?path=` | `{content, version, force}` → `{version}` or **409** `{conflict, current, version, deleted}` |
| POST | `/api/new` | `{path, folder, template?}` → `{path}` (adds `.md`; fills `{{date}} {{time}} {{title}}`) |
| POST | `/api/move` | `{from, to}` → `{path}` (never overwrites; a folder cannot go into itself) |
| POST | `/api/trash` | `{path}` → `{trash}` (`.trash/<name>`, `name 2.md` on a clash; recorded in the ledger) |
| POST | `/api/restore` | `{name, to}` → `{path}` (never overwrites) |
| POST | `/api/capture` | `{text}` → `{path}` (appends `- YYYY-MM-DD HH:mm text` to `MNEMOS_CAPTURE`) |
| POST | `/api/conflicts/resolve` | `{copy, keep: original\|copy\|merged, content?}` → `{path}`; the loser goes to the trash |
| POST | `/api/attach?note=&name=` | raw image body → `{path, markdown}`; saved as `<note folder>/attachments/<safe name>`, never overwriting |
| POST | `/api/render` | `{path, content}` → `{html}` (resolves `![[embeds]]` through the search index) |
| GET | `/raw/<path>[?download=1]` | the file as-is, own CSP `sandbox`; HTML/JS/XML sent as `text/plain` |
| GET | `/healthz` | `ok` (Docker healthcheck via `mnemos health`) |

## Security model

- **Session.** Cookie value is `base64(user|expiry|HMAC-SHA256)`. The HMAC key is per account: `SHA-256("mnemos-session" | MNEMOS_SECRET | user | password)`, so changing a password or the secret invalidates that account's sessions. Over HTTPS the cookie is `__Host-mnemos` (Secure, Path=/, no Domain). 30 days, HttpOnly, SameSite=Lax.
- **CSRF.** Every non-GET request needs the `X-Mnemos` header *and* must pass `httpx.SameOrigin`: `Sec-Fetch-Site` must be `same-origin`/`none` when present, and `Origin` must match `Host` when present. Login and logout too.
- **Read-only demo.** Enforced in `httpx.Router`, before any handler runs; tests cover every write route.
- **Login.** bcrypt or plain comparison; an unknown user is compared against a dummy hash so timing does not reveal which users exist. 5 failures per IP per 15 min → 429. The limiter holds at most 10 000 IPs.
- **Client IP.** `CF-Connecting-IP`, then `X-Real-IP`, then the **last** `X-Forwarded-For` entry, then the socket. Only valid IPs are accepted. This trusts your proxy; the container must not be reachable except through it.
- **Headers.** CSP (`script-src 'self'`; inline styles allowed for CodeMirror), HSTS over HTTPS, X-Frame-Options DENY, nosniff, Referrer-Policy same-origin, COOP/CORP same-origin, Permissions-Policy. `/raw` adds its own `sandbox` CSP.
- **Errors.** Unexpected errors are logged in full and returned as "internal error — see the server log". Permission problems return 403 naming the vault-relative path and the uid.
- **Logging.** Every non-GET request (except successful previews) is logged as `ip method path [note] → status (duration)`; sign-ins with user and IP, failed sign-ins with IP. Never bodies, passwords or note contents.
- **Server limits.** ReadHeaderTimeout 10 s, Read 30 s, Write 60 s, Idle 120 s, headers ≤ 64 KB, JSON bodies ≤ 2×max file size, search queries ≤ 200 bytes.
- **Container.** `FROM scratch`, runs as PUID:PGID, read-only root, `/tmp` tmpfs (demo vault), `cap_drop: ALL`, `no-new-privileges`, only `/notes` writable, no published port.
- **Startup checks.** A placeholder password refuses to start, so does an owner named like the demo user; an empty or short secret and a plain-text password are warned about.

## Rules that matter

**Paths.** `vault.Clean` normalises against the root (so `..` cannot climb out),
refuses NUL and backslashes, and refuses any segment starting with `.` plus the names
or paths in `MNEMOS_HIDDEN`. `vault.Resolve` then follows symlinks on the path (or its
nearest existing parent, for new files) and refuses anything that lands outside the
real vault root. The tree skips symlinked folders entirely.

**Versions and conflicts.** A version is the first 12 bytes of SHA-256 of the content
(mtime is ignored: touching a file is not a change). The browser sends the version its
text is based on with every save; the server re-reads under a mutex and answers 409
with what is on disk (or `deleted: true`). `force: true` skips the check. Saving
identical content does not rewrite the file.

**Writes.** Temp file `.mnemos-*.tmp` in the same folder → write → fsync → chmod to the
old mode → rename. If the folder is not writable but the file is, the save falls back to
writing in place (warned once per folder). Capture appends the same way and keeps the
file's line endings.

**Trash.** Moves into `<vault>/<MNEMOS_TRASH>` (default `.trash`), flat, with " 2", " 3"
… on a clash. The ledger `.trash/.mnemos-trash.tsv` (`name \t original \t unix`) lets
restore offer the original path; files trashed by other apps restore by name. Mnemos
never deletes.

**Syncthing conflicts.** A copy is any file named
`<base>.sync-conflict-YYYYMMDD-HHMMSS-<DEVICE7>[.ext]`. Resolve: *original* trashes the
copy; *copy* trashes the original and renames the copy into its place; *merged* writes
the merged text to the original and trashes the copy.

**Images.** Uploads are sniffed (PNG, JPEG, GIF, WebP, AVIF — never SVG, which can carry
scripts) and saved with the extension of what they really are, under a sanitised name
(`My photo (1).JPEG` → `My-photo-1.jpg`; a pasted `image.png` → `image-<time>.png`), in the
note's `attachments/` folder, atomically, never overwriting (`name 2.png`). The reply's
markdown links relative to the note with spaces escaped, so it works in any renderer. The
Android app follows the same rule. Obsidian's `![[target|alt or 300 or 300x200]]` is read by
an inline goldmark parser (before the link parser, so code spans stay text) and resolved
like Obsidian: from the note's folder, its `attachments/`, the vault root, then the closest
file with that name.

**Search.** One `search.Index` per vault, created on first use. Every query rescans the
tree at most every 3 s and re-reads only files whose size or mtime changed; text files
≤ 2 MB are indexed. Scoring: name 60 (+20 prefix), path 25, fuzzy name 15, text 10 +
count. `#tag` and `key: value` are filters.

**Rendering.** Server side only. goldmark's safe defaults: raw HTML omitted, dangerous
URLs dropped. Frontmatter shown escaped in `<pre class="frontmatter">`. Relative image →
`/raw/<resolved>`; relative link to `.md`/`.txt`/no extension → `#/n/<resolved>`; other
relative link → `/raw/…`.

**Outside edits.** On focus, on returning to the tab and every 30 s, the browser asks
`/api/version`. Same: nothing. Different and clean: reload quietly (cursor and scroll
kept). Different with unsaved edits: save, which raises the conflict bar. A followed
`.log` polls every 2 s and scrolls to the end.

## Front end notes

- No framework, ES modules only, no build step except the CodeMirror bundle (~357 KB, gzipped once at start-up, served with ETags).
- Routes: `#/n/<path>` file, `#/f/<path>` folder cards, `#/c/<copy>` conflict compare; `#/new`, `#/capture`, `#/search` are home-screen shortcuts. The PWA share target arrives as `/app?title&text&url` and opens Capture.
- One text source of truth: the CSV table writes back through `editor.setText`, ticking a task through `editor.toggleTask`, so autosave, undo and conflicts behave the same everywhere.
- Demo mode: `body.demo` hides `.rw-only` controls, the status says "Demo — not saved", nothing is marked dirty, so there are no save attempts and no leave prompts.
- Per-device settings in `localStorage` (`mnemos.*`): view per kind, split %, sidebar, open folders, theme; per user: last file, recent, pins. Everything works if storage is blocked.

## Deploy

1. `.env` (or a shared `.env` symlinked into the folder) holds `NOTE_PATH`, `PUID`, `PGID`, `TZ`, `MNEMOS_NETWORK` and the `MNEMOS_*` keys (old `SAMANOTE_*` keys still work). Compose passes only `TZ` and those keys into the container.
2. `docker compose run --rm mnemos check` — settings plus every folder/file the container user cannot write.
3. `docker compose up -d --build`. The container is `mnemos` on the external network: `reverse_proxy mnemos:8080`.
4. Release: bump `VERSION` and `android/version.properties` (`versionName`, `versionCode` + 1), add both changelog sections, `sh scripts/check-version.sh`, then push the tag `v<version>`; CI does the rest (README → Versions and releases).
