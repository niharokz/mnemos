# Changelog

The updater shows the section for the version it is about to install, so keep one `## <version>`
heading per release.

## 1.1.1

- **Server mode.** Sign in to your Mnemos server (setup, or Settings → Vault → Storage) and the app works like the web app — and keeps working offline: what you opened stays readable, edits are queued and sent when the network is back, and a note that changed on the server meanwhile is saved as a `(conflict …)` copy, never overwritten. Syncs every 30 s and on "Sync now". The phone folder mode is unchanged and makes no network requests. (The app now has the INTERNET permission, used only for this.)
- **Flow view** — every note, newest first, one per screen; swipe up for the next, like Shorts. Views are now List, Box, Tabs and Flow.
- **Cleanup** (Settings → Cleanup): conflict copies to the trash, then trash older than 7–365 days (default 30) erased — after a preview, only when you press it. In server mode the server does it.
- **Widgets:** tapping a note opens exactly that note, also while another one is open. Recent and Pinned show cards with folder and "2 h ago" and a + for a new note; Note preview shows tidied first lines and when the note changed.
- The header and Settings show how many notes the vault holds and its size.
- Smoother moves: folders slide in from the side, views cross-fade, notes open with a shorter glide.
- The PIN pad is centred again.
- New, simpler icon (a note page with a folded corner) in the same family as Natlas and Median; the default logo colour is the dark nss tile.

## 1.1.0

- **Rename a note right from the editor.** Tap its name in the top bar (or ⋯ → Rename), type, press Done. The folder and extension stay the same and an existing file is never overwritten.
- New notes from + open with "Untitled" selected, so you can name them straight away.
- Changing only the capital letters of a name ("idea" → "Idea") now works, from the editor, the tree and Manage folders.
- Pins, widgets (Open note, Note preview) and the last-open note follow a renamed note or folder.
- The Quick Settings capture tile is safe on Android 14 and newer; Android lint is clean again (CI prints every lint issue now, not just the first).

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
