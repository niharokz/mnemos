# Mnemos for Android — architecture

The spec this was built from is the "Mnemos for Android — build prompt (v1.0.0)". This file
explains how the code is laid out, how data flows, the file rules, and where the build differs
from the spec (see **Assumptions** at the end).

## Modules

```
app ─────────────┬─► feature:signin    welcome, owner/demo sign-in, PIN pad, setup wizard
 Activity, nav,  ├─► feature:tree      file tree, pins, swipe, multi-select, trash
 AppGraph wiring,├─► feature:editor    EditText host, highlighting, helpers, find/replace, preview ─► feature:conflicts (DiffView)
 widgets, tile,  ├─► feature:table     CSV/TSV grid
 share, shortcuts├─► feature:viewer    large text (mmap), image, binary info
                 ├─► feature:search    one search box
                 ├─► feature:conflicts sync-conflict diff + resolution
                 ├─► feature:recent
                 └─► feature:settings  settings + update-from-vault
                           │
                           ▼
                      core:data  AppGraph, VaultSession, settings (DataStore), SAF VaultFs, watcher,
                           │      capture, trash ledger, demo vault, FileKind, Open with
     ┌──────────┬─────────┼───────────┬───────────┬──────────┐
 core:text  core:csv  core:vaultfs  core:markdown  core:index  core:security  core:design
 (JVM)      (JVM)     (JVM)         (JVM)          (SQLite)   (PIN, Keystore) (nss × M3, motion, icons)
```

`core:text`, `core:csv`, `core:vaultfs` and `core:markdown` are plain Kotlin/JVM modules: the
data-safety rules live there and are unit tested without Android. Everything else is an Android
library. Each feature module has `Screen.kt` (UI), `ViewModel.kt` (state) and `State.kt`
(immutable models).

Dependency wiring is manual: one `AppGraph` (in `core:data`) is created by `MnemosApp` and
reached from Compose through `LocalAppGraph`; ViewModels are built with `graphViewModel { … }`.

## Users and sessions

`AppGraph.session` is the signed-in user — a `VaultSession` for the **owner** (the chosen vault)
or the **demo** (an app-private copy of `assets/demo`). A session bundles the `VaultFs`, the
`SaveCoordinator`, the trash, the SQLite index, the watcher and the user's settings. Owner and
demo never share a database, settings file or trash ledger. ViewModels are keyed by `session.id`
so switching users (or resetting the demo) builds fresh screens.

The lock (`LockManager`) is separate from the session: locking does not close the vault. When the
owner is locked, `AppRoot` draws the PIN pad over the app, so open editors keep their state and
autosave runs on lock.

## Data flow

```
EditText (owns live text) ──onEdited──► EditorViewModel ──debounce──► SaveCoordinator.save(path, bytes, baseVersion)
     ▲                                        │                              │
     └────────── EditorCommand ◄──────────────┘                              ▼
                                        session.notifyChanged ◄──── atomic write (VaultFs)
FileObserver (VaultWatcher) ──300 ms batch──► session.changes ──► tree, recent, search, index, widgets
```

Screens observe `StateFlow`s of immutable state. The EditText is the one exception: it owns the
live text for speed, and the ViewModel only reads it (`toString()`) when saving or parsing.

## File rules (same as the web app)

- **Versions.** `version = SHA-256(bytes)`, first 12 bytes as hex.
- **Save.** Under a per-file lock, re-read the file. Same version as the editor's base → write
  atomically: `.<name>.mnemos-<rand>.tmp` in the same folder, fsync, copy the file mode,
  `ATOMIC_MOVE` over the target, fsync the folder. Different version → **Conflict**, nothing
  written. Missing → **Missing**, nothing written. Identical bytes → **Unchanged**, nothing written
  (no mtime churn, so Syncthing stays quiet).
- **Read-only folders.** If the folder refuses the temp file but the file is writable, overwrite
  in place with fsync and log a warning.
- **Text.** A file is text if its first 64 KB has no NUL and is valid UTF-8 (BOM allowed), or
  UTF-16 with a BOM. Decoding is strict, so a file with bad bytes later on is reported instead of
  being mangled on save. The editor sees `\n`; the original line ending and BOM are written back.
  Files that mix LF and CRLF are unified on the first save that changes them.
- **CSV.** Parsed without newline normalising. Each record keeps its exact source text and is
  re-serialised only if edited, so open → save is byte-identical and a one-cell edit changes one row.
- **Delete.** Never. Files and folders move to `.trash/` (flat, ` 2`, ` 3`… on clashes). The
  original location is remembered app-private (`trash-<user>.tsv`) so Restore can put it back.
- **Paths.** Normalised, `..` rejected, symlinks resolved and required to stay inside the vault.
  Hidden folders are never written, except `.trash/`. There is no delete API at all.
- **Sync conflicts.** `name.sync-conflict-YYYYMMDD-HHMMSS-XXXXXXX.ext` is grouped with
  `name.ext`; resolving moves the loser to the trash.
- **Pictures.** `Attachments` (core:data, pure and unit tested) mirrors the web app's
  `internal/vault/attach.go`: bytes are sniffed (PNG, JPEG, GIF, WebP, AVIF; never SVG), the name
  is made safe (`My photo (1).JPEG` → `My-photo-1.jpg`, generic names get a time stamp), the file
  goes to `attachments/` next to the note with `createNew` (never overwriting, ` 2` on clashes)
  and the markdown links it relative to the note. `ImageImport` (feature:editor) reads picker and
  camera URIs and turns HEIC into upright JPEG with `ImageDecoder`.

## Index and search

`core:index` keeps an app-private SQLite cache per user: `files`, an FTS table `content` (FTS5
when the framework SQLite has it — probed at runtime — otherwise FTS4) and `recent`. The scanner
compares mtime and size, reads only changed files (≤ 2 MB of text) with four parallel readers,
and writes in batches of 200. Schema changes or corruption drop and rebuild it. Search combines
fuzzy file names, FTS prefix terms with snippets, `#tag` and `key: value` front-matter matches.

## Editor

- `NoteEditText` (a platform `EditText`): undo history (`EditHistory`, grouped typing, lives in the
  ViewModel so it survives rotation), markdown helpers (`MarkdownActions`, pure and unit tested),
  hardware shortcuts (Tab/Shift-Tab, Ctrl+S/F/Z/Y/B/I), smart URL paste, cached line numbers.
- `Highlighter`: tokenises (`SyntaxTokens`, pure) only the edited paragraphs on a background
  thread and applies spans on the main thread if the text did not change meanwhile. Opening a file
  highlights the visible window first, then the rest in chunks.
- Preview: commonmark → `Block` list → one `LazyColumn` item per block, keyed by a hash of the
  block's source, so typing recomposes only the changed block. Task checkboxes write the file
  (`TaskToggle`) through the normal save. Split view syncs preview scroll to the editor.
- Pictures: the photo picker (`PickMultipleVisualMedia`) or the camera (`TakePicture` into the
  app cache via the FileProvider) → `EditorViewModel.addImages` → `MarkdownActions.insertBlock`
  at the cursor. The preview decodes images off the main thread with downsampling; Obsidian
  `![[embeds]]` (`core:markdown` `Embeds`) are resolved like Obsidian: note folder, its
  `attachments/`, vault root, then the closest indexed file of that name. Tapping opens a
  full-screen viewer with pinch-zoom.

## Home views and folders

`TreeViewModel` serves all five home views. List and Compact flatten the expandable tree; Grid,
Box and Tabs show one folder at a time (`currentDir`, with a breadcrumb and system back going up).
Box cards read the first 2 KB of each note in the folder (`Previews`, capped at 60 files). Folders
can't be trashed from home: the + button only makes notes, and `FoldersScreen` is the one place
folders are created, renamed or moved to the trash.

## Theme colours

`NssTokens.custom(base, ThemeColors)` applies the user's picks for one theme and re-derives every
other colour from them. Text that would fall under 4.5:1 on the background becomes black or
white, cards are pulled toward the background until the text reads on them, and the accent is
darkened or lightened for use as text. A unit test feeds it 500 random colour sets.

## Logo and launcher icon

`MnemosLogo` (core:design) holds the mark's shapes. `scripts/make-launcher-icons.py` generates one
adaptive icon per logo colour from the same shapes. The manifest has one `activity-alias` per
colour pointing at `MainActivity`; `LauncherIcon.apply` enables exactly one whenever the owner's
logo colour changes. Android can't recolour an icon at runtime, so the colours are fixed presets.

## Adding a file-type viewer

Implement `FileTypeHandler` (in `app/AppNav.kt`) and add it to `fileHandlers`. The first handler
whose `handles(kind, ctx)` returns true draws the file. `FileKinds.classify` decides the kind from
content, size and extension.

## Security

No `INTERNET`, no WebView, `allowBackup=false` plus data-extraction rules that exclude everything,
`FLAG_SECURE` on by default. PIN: PBKDF2-HMAC-SHA256, 16-byte random salt, iterations calibrated
to ~250 ms (minimum 100 000), constant-time compare, 30 s cooldown from the 5th wrong try,
doubling to 15 min and surviving restarts. Fingerprint: `BIOMETRIC_STRONG` with an AES key that
requires authentication and is invalidated by new enrolments; a successful prompt must also
unlock the key (`doFinal`). Logs carry counts, timings and vault-relative paths only.

## Performance targets

| Metric | Target | Measured by |
|---|---|---|
| Cold start → lock screen interactive | ≤ 350 ms | `StartupBenchmark.coldStart` |
| Unlock → tree visible (5 000 files) | ≤ 150 ms | manual + `scrollTree` |
| Open 100 KB note | ≤ 80 ms | `StartupBenchmark.openNote` |
| Open 2 MB log | ≤ 200 ms | manual |
| Typing on a 1 MB note | no frame > 8.3 ms | `StartupBenchmark.typing` |
| Search, 5 000 files | ≤ 150 ms/keystroke | shown in the Search tab ("N results · X ms") |
| First index, 5 000 files / 50 MB | ≤ 20 s, UI usable | progress chip in the tree |
| APK size | ≤ 12 MB | `dist/` |
| Memory idle in editor | ≤ 120 MB PSS | `adb shell dumpsys meminfo com.nihars.mnemos` |

## Assumptions and differences from the spec

- **Not compiled on a device yet.** The repo was written in an environment without Google Maven,
  so the Android/Compose code has not been through Gradle, lint or a device. The JVM modules and
  the pure logic (CSV, text, vault FS, diff, markdown model, PIN/cooldown, editor helpers, line
  index, capture, fuzzy match, contrast) were compiled and unit tested; the non-Compose Android
  code was type-checked against `android.jar` and small AndroidX stubs. Library versions in
  `gradle/libs.versions.toml` are pinned to releases known to exist; bump them if Gradle
  suggests newer ones.
- **Extra module `core:data`.** The spec's layout has no home for shared app state (AppGraph,
  sessions, settings, SAF access). It lives in `core:data` rather than in `app`, so features can
  use it without depending on the app.
- **Material 3 Expressive.** Standard Material 3 plus the spec's motion tokens is used; the
  Expressive APIs are still experimental.
- **Container transform / shared element** (tree row → editor, FAB → sheet) uses the spec's
  fallback: fade-through with a small slide, and an `AnimatedContent` size transform for the FAB.
  Predictive back scales and fades the page; rounded corners during the gesture are not drawn.
- **Glance** is not used; widgets are plain `RemoteViews` with size-mapped layouts and
  `RemoteCollectionItems` (both API 31).
- **SVG** files open as text (they are text); there is no SVG renderer without a WebView or library.
- **`[[wikilinks]]`** show as plain text (decided in the spec).
- **Light error colour** is `#c93434` instead of nss `#d63c3c`, which is only 4.0:1 on
  surface-2 (the contrast test would fail).
- **Licence list** is generated into the APK from `config/licenses.txt` at build time rather than
  scraped from the dependency graph.
- **Front matter** is split and parsed by our own small YAML-subset reader (display and search
  only, never rewritten) instead of commonmark's front-matter extension.
- **Baseline Profile.** A hand-written starter profile ships in `app/src/main/baseline-prof.txt`;
  run `./build.sh profile` on a device to replace it with a measured one.
- **SAF mode** saves in place (truncate + write + fsync); the picker API cannot rename over a file.
- **Androidx Fragment** is an extra dependency because `BiometricPrompt` needs a `FragmentActivity`.
