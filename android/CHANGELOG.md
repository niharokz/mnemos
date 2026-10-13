# Changelog

The updater shows the section for the version it is about to install, so keep one `## <version>`
heading per release.

## 1.0.1

- **Pictures in notes.** Add photos from the gallery or the camera with the new picture button (formatting bar, or ⋯ → Add picture). They are saved in `attachments/` next to the note — the same place and the same naming as the web app — and linked with plain markdown at the cursor. HEIC photos are turned into upright JPEGs so every device and browser can show them.
- **Obsidian image embeds** (`![[photo.png]]`, `![[photo.png|300]]`) show in the preview, found the way Obsidian finds them.
- Tap any picture in the preview for full screen: pinch to zoom, double-tap to reset.
- Several pictures in a row each get their own full-width place in the preview; AVIF images open too.
- Released from GitLab CI: the APK is built, signed and checked for the missing INTERNET permission on every version tag.

## 1.0.0

First release of Mnemos for Android (formerly built as "Samanote for Android").

- New name and logo: a note page with a brain on it and a pen writing on the brain. Twelve logo colours (including transparent); the home-screen icon changes with it.
- New widgets: New note, Open note, Quick actions and Recent notes, alongside Pinned notes, Note preview and Quick capture.
- Readable in every theme; light and dark each keep their own background, card, text and accent colours.
- Home views: List, Compact, Grid, Box (note previews) and Tabs.
- + creates a note in the default folder and opens it; folders are managed on their own screen and can't be deleted from home.
- Anything shared from other apps is saved to a share file, files into `attachments/`.
- Your name is asked once at setup; the demo user disappears after setup.
- Smoother, calmer motion throughout.

- Offline editor and viewer for any text file in a folder tree (no INTERNET permission).
- Markdown editor with live native preview, Edit / Split / Preview, formatting toolbar, find and replace.
- CSV/TSV table with byte-exact saves, view-only sort and filter, column stats.
- Large-file viewer with search, jump to line and follow-tail.
- Conflict-safe saving next to Syncthing and Obsidian; side-by-side diff for sync-conflict copies.
- Search over names, full text, #tags and front matter.
- Quick capture: share target, Quick Settings tile, widgets, shortcuts.
- PIN/password and fingerprint lock; demo user with a sample vault.
- Offline updates from `<vault>/apps/`.
