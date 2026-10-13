# Mnemos for Android

A fast, fully offline notes app for a folder of plain-text files — for example an Obsidian
vault that Syncthing keeps on your phone. It is the phone companion of the
[Mnemos web app](../README.md) in the same repository: same file rules, same nss look,
rebuilt natively with Jetpack Compose and Material 3. This folder (`android/`) is a
self-contained Gradle project; run every command below from here.

- **Offline by construction.** The APK has no `INTERNET` permission, no analytics, no crash
  upload, no Google Play Services.
- **Your files stay plain files.** Mnemos writes only the notes you edit or create, and moves
  deleted files into the vault's `.trash/`. Index, cache and settings live in app storage.
- **Never loses data.** Atomic saves (temp file → fsync → rename), a version check before every
  save, line endings / encoding / BOM kept exactly, unchanged files never rewritten.

## Features

| | |
|---|---|
| Any text file | `.md`, `.txt`, `.csv`, `.tsv`, `.log`, `.json`, `.yaml`, `.ini`, code — anything that is valid text |
| Markdown | Fast `EditText` editor with incremental highlighting, list continuation, auto-pairs, formatting toolbar, find & replace, undo/redo; native live preview (tables, task lists, footnotes, front matter, images); Edit / Split / Preview |
| Pictures | Add photos from the gallery or camera: saved in `attachments/` next to the note (same rules as the web app), linked at the cursor. Obsidian `![[photo.png]]` embeds show; tap a picture for full screen with pinch-zoom |
| CSV / TSV | Real table: sticky header and first column, virtualized rows and columns, cell editing, view-only sort and filter, column stats. Saving after no edits is byte-identical; a one-cell edit rewrites only that row |
| Big files | Over 2 MB opens in a memory-mapped viewer with search, jump to line and follow-tail for logs; "Edit anyway" up to 10 MB |
| Conflicts | Notices Syncthing / Obsidian / script changes. Clean notes reload in place; dirty ones get **Load theirs / Keep mine / Save mine as copy**. `*.sync-conflict-*` copies get a side-by-side diff |
| Search | One box for file names (fuzzy), full text (SQLite FTS), `#tags` and front matter (`status: open`) |
| Capture | Share target, Quick Settings tile, app shortcuts |
| Widgets | New note (1×1/2×1), Open note (a chosen note), Quick actions (2×2, 4×1, 1×2: new, capture, search, last note), Recent notes, Pinned notes, Note preview, Quick capture. Widgets show note names only, never content while locked |
| Home | Five views (List, Compact, Grid, Box with note previews, Tabs per top-level folder). **+** makes a note in your default folder and opens it. Folders are created and removed only in *Manage folders* |
| Sharing | Share anything from any app (a link from Chrome, selected text, photos, files) and it lands in your share file (Settings → Vault); files are copied into `attachments/` beside it |
| Look | Light and dark themes, each with its own background, card, text and accent colours; text always stays readable whatever you pick. The logo colour is yours to choose from 12 presets (including transparent) and the home-screen icon follows it |
| Lock | PIN/password (PBKDF2) + strong fingerprint bound to a Keystore key; auto-lock; hidden in Recents |
| Demo user | Shown only before you set up your vault: try everything on a sample vault without permissions |

## Install

Mnemos is sideloaded (no Play Store). Android 12 or newer.

1. On the phone, download the latest APK from GitLab Releases — this link always points at the newest one:
   <https://gitlab.com/niharokz/mnemos/-/releases/permalink/latest/downloads/mnemos.apk>
   (or build it yourself, below).
2. Open it, allowing installs from your browser or file manager when Android asks.
3. On first run choose **My notes**, tell Mnemos your name, grant **All files access**, pick your vault folder
   (folders with `.obsidian` or `.stfolder` are highlighted), set a PIN and, if you like,
   fingerprint unlock. Or tap **Demo user** to look around first.

If you refuse All files access, Mnemos can use Android's folder picker instead ("slower mode"):
same features, slower indexing, and saves overwrite in place because the picker cannot rename.

## Build

Needs Docker. Nothing else is installed on the host.

```sh
./build.sh keystore   # once: creates the signing key outside the repo (~/.mnemos-keys)
./build.sh            # signed release APK → dist/mnemos-<version>.apk (checks: no INTERNET permission)
./build.sh check      # unit tests + Android lint (warnings are errors)
./build.sh install    # adb install over USB
./build.sh profile    # regenerate the Baseline Profile (host SDK + connected device)
```

The first build downloads Gradle, the Android SDK and dependencies into a Docker volume; later
builds work offline. The key and its passwords (`mnemos.jks`, `keystore.env`) live in
`~/.mnemos-keys`, outside the repo. Back up that folder — updates must be signed with the same key.

The version lives in `version.properties` only.

### Publishing a release

Releases are made by GitLab CI from the repository root: bump `version.properties`
(`versionName` = the repo's `VERSION`, `versionCode` + 1), add a `## <version>` section here,
then push the tag `v<version>`. CI builds and signs the APK, checks it has no INTERNET
permission and publishes it as `mnemos.apk` on the release, which is what the link above and
the web app's about page serve. See "Versions and releases" in the [main README](../README.md).

## Updating on the phone, offline

Put the new `mnemos-<version>.apk` into `<vault>/apps/` on your computer. When Syncthing has
brought it over, open **Settings → Update → Check vault for update**. Mnemos installs it only if it
is the same app, a higher version and signed with the same certificate, and shows that version's
changelog first. Settings are kept.

## Manual test checklist

Some things are best checked by hand on a real phone:

- [ ] Syncthing writes a note while it is open: a clean note reloads in place with "Updated from another app"; an edited one shows the conflict banner on the next save.
- [ ] Obsidian edits the same note: same as above; all three resolutions work and "View diff" shows the changes.
- [ ] A `*.sync-conflict-*` file: Keep original / Keep conflict copy / Merge by hand; the loser is in `.trash/`.
- [ ] A 2 MB log: opens fast, scrolls smoothly, searches, and follows its tail while something appends to it.
- [ ] A 5 000-file vault: `scripts/make-test-vault.sh ./test-vault 5000`, push it, pick it as the vault, then search while it indexes.
- [ ] Airplane mode end to end; `aapt dump permissions dist/mnemos-*.apk` shows no INTERNET.
- [ ] Text size at 200 %, TalkBack, and Settings → Appearance → Motion → Reduced.

## Performance

Targets (mid-range Android 16 phone, release build with Baseline Profile) are in
`ARCHITECTURE.md`. Measure them with:

```sh
./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest
```

## Licence

MIT. Fonts and libraries are listed in the app under **Settings → About → Open-source licences**.
