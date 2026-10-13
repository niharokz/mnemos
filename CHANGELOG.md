# Changelog

This is the web app's changelog. The Android app has its own: [android/CHANGELOG.md](android/CHANGELOG.md).

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
