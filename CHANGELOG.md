# Changelog

This is the web app's changelog. The Android app has its own: [android/CHANGELOG.md](android/CHANGELOG.md).

## 1.1.1 - 2026-10-31

- **Flow view.** Every note, newest first, one per screen — scroll (or `↑`/`↓`, `j`/`k`) to the next, `Enter` opens it. The views are now **List**, **Box** (was Cards), **Tabs** and **Flow**.
- **Cleanup** (Settings): the server's old `cleanupSync.sh`, built in and run only when you press it. Syncthing `*sync-conflict*` copies move to the trash, then trash items older than *N* days are erased (default 30, set in Settings; kept in `/state/settings.json`). A preview lists everything first; the demo can't run it. The trash ledger now records when each item was trashed, so age counts from then.
- **Vault size** — note count and storage under the tree (and `GET /api/stats`).
- **Home page** rewritten in the Natlas style: shorter, with Sign in, the **Android APK** (newest GitLab release, `MNEMOS_APK_URL`) and "Run your own" in three steps. The web app is always online on its own server; to keep your own notes, run your own.
- **New icon**, minimal like Natlas and Median: a note page with a folded corner and three lines on the dark nss tile (favicon, PWA icons, Android).
- **Bans.** 5 failed sign-ins or scanner probes (`/.env`, `/conf/.env`, `/.git/config`, `wp-admin`, `*.php` …) from one address within 24 hours ban it for 7 days: every request from it then gets `403`. Probes always get `404` and never reach the app; signed-in users are never struck, and LAN/loopback addresses are never banned unless `MNEMOS_BAN_PRIVATE=true`. Bans live in the new `mnemos-state` volume (`/state/bans.json`, mode 600) and survive restarts. `mnemos bans` lists them and `mnemos unban <ip>` lifts one without a restart. Settings: `MNEMOS_BAN_AFTER` (0 = off), `MNEMOS_BAN_DAYS`, `MNEMOS_BAN_PRIVATE`, `MNEMOS_STATE`.
- `POST /api/upload?path=` stores an image at an exact path (never overwriting), for the Android app's queued uploads.
- **Settings only from `MNEMOS_*`.** The `SAMANOTE_*` fallbacks are gone — rename any left in your `.env` (see `.env.example`). New: `MNEMOS_TRASH` and `MNEMOS_APK_URL` in compose.
- **GitLab:** a tag builds the signed APK on your own runner (tag `mnemos-apk`, no CI minutes, the key never leaves your server) and attaches it to the release as `mnemos.apk`; checks run on shared runners; issue and merge request templates; README badges. `scripts/release.sh` and the CI signing variables are no longer needed.

## 1.1.0 - 2026-10-14

- The version moves with the Android app (rename notes from the editor — see [android/CHANGELOG.md](android/CHANGELOG.md)).
- **Fix:** the container no longer crash-loops with `demo vault: mkdir /tmp/mnemos-demo-…: permission denied`. The compose tmpfs for `/tmp` is now world-writable (`mode=1777`), the image ships a writable `/tmp`, and if the demo vault still can't be unpacked Mnemos logs a warning and starts without the demo user.

## 1.0.1 - 2026-10-13

- **Images.** Paste, drop or pick images (🖼 in the formatting bar) into a note. They are saved in `attachments/` next to the note — the same place the Android app uses — and linked with plain markdown. Uploads are checked to really be PNG, JPEG, GIF, WebP or AVIF, get a safe name and never overwrite (`MNEMOS_MAX_UPLOAD_MB`, default 20).
- **Obsidian image embeds** (`![[photo.png]]`, `![[photo.png|300]]`) show in the preview, found the way Obsidian finds them.
- Tap any image for a full-screen view; image files open in a viewer with their size; folder cards show thumbnails.
- **Favicon** is the Mnemos logo everywhere (`favicon.ico` added for browsers that ask for it; icons are versioned so the old one is not kept from cache).
- **One version** for the repository: `VERSION` (embedded in the binary) and `android/version.properties`, checked by `scripts/check-version.sh`.
- **GitLab CI** checks every change (Go, front end, versions, secrets, Docker build, Android tests and lint) and a `v*` tag builds, signs and releases the APK and the web image. `scripts/release.sh` replaces `scripts/release-android.sh`.

## 1.0.0 - 2026-10-13

**Mnemos replaces Samanote.** Same file rules, same layout; the Android companion app now
lives in this repository under `android/`.

- **Two users.** Your account, plus an optional **read-only demo user** with its own sample vault (the Android demo vault, embedded in the binary). The sign-in card shows its login and a "Try the demo" button. The server refuses every change from the demo; typing, ticking tasks and the preview still work, and the page says nothing is saved. `MNEMOS_DEMO=false` turns it off.
- **CSV / TSV tables.** Sticky header, sort and filter (view only), cell editing, add / delete rows, column stats. Untouched files are saved byte for byte; a one-cell edit rewrites only that row.
- **Search** (`Ctrl+K`): fuzzy file names, full text, `#tags`, front-matter properties (`status: open`); results jump to the line.
- **Syncthing conflicts.** `*.sync-conflict-*` copies are counted in the sidebar and marked in the tree, compared side by side, and resolved by keeping either side or merging by hand; the loser goes to the trash.
- **Trash with restore.** The trash is listed in the app; restore puts a file back where it came from (recorded in `.trash/.mnemos-trash.tsv`).
- **Capture** (`Alt+C`, home-screen shortcut, and a PWA share target) appends a time-stamped line to `MNEMOS_CAPTURE` (default `inbox/note.md`).
- **Templates.** "New from template" lists `MNEMOS_TEMPLATES` (default `templates/`), filling in `{{date}}`, `{{time}}`, `{{title}}`.
- **Cards, pins, recent.** A folder as cards with note previews, pinned files in the sidebar, recently opened and changed files.
- **Editor.** Formatting bar and `Ctrl+B/I/K`; task boxes tick right in the preview; `.log` files open at their end and can follow new lines; any file can be downloaded.
- **About page** highlights the Android app with install steps from GitLab Releases, with a stable `mnemos.apk` download link.
- **New logo**: a note page with a brain and a pen, shared with the Android app.
- **Settings** are now `MNEMOS_*`. Every old `SAMANOTE_*` variable still works (in compose and in the binary) and is named in the log. The session cookie is now `__Host-mnemos`, so everyone signs in once more.

## Before 1.0.0

Mnemos web continues Samanote 2.1.0 (2026-10-10: about page, `/app`, hardened sessions,
CSRF and lockout fixes, read-only-folder fallback, `check` command) and 2.0.0 (2026-10-06:
the Go rewrite with CodeMirror, live preview, conflict-safe autosave and trash).
