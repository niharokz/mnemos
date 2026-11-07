# Changelog

All notable changes to mnemostxt. One version for every build (desktop,
Android, web); `sh scripts/check-version.sh` keeps them in step.

## 0.0.1 - 2026-11-07

First release. mnemostxt replaces mnemos.

- Nothing is written at the top of the notes folder: settings and trash live
  in `logs/mnemostxt/`.
- Notepad for Linux (.deb), Windows (setup.exe) and Android (.apk), plus the
  web app (Docker) for the notes folder.
- Tabs, folder sidebar with nested folders, open editors list, find in files.
- The notes folder is always in the sidebar; other folders can be opened next
  to it (desktop).
- Notepad++ key bindings, line operations, bookmarks, column selection,
  multi-cursor, encodings (UTF-8, UTF-8 BOM, UTF-16, ANSI) and line endings.
- Syntax highlighting for 140+ languages (plus Caddyfile, .env, batch).
- Markdown preview, image and PDF viewer.
- One settings file shared by every device: `<notes>/logs/mnemostxt/settings.json`.
- Hot exit: unsaved edits survive closing (or killing) the app.
- Changes on disk are detected; saving never overwrites them silently.
- Syncthing conflict copies are flagged.
- Trash in `<notes>/logs/mnemostxt/trash` with "Empty trash" after N days.
- Collapsible menu bar (Alt opens it), nss dark and light themes.
- Web: sign-in with rate limiting and bans, home page with download links.
