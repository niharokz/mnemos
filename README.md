# Mnemos

[![pipeline](https://gitlab.com/niharokz/mnemos/badges/main/pipeline.svg)](https://gitlab.com/niharokz/mnemos/-/pipelines)
[![release](https://gitlab.com/niharokz/mnemos/-/badges/release.svg)](https://gitlab.com/niharokz/mnemos/-/releases)
[![Android APK](https://img.shields.io/badge/Android-APK-3a807a)](https://gitlab.com/niharokz/mnemos/-/releases/permalink/latest/downloads/mnemos.apk)

**Your notes folder, on the web and on Android** — for example the vault you also open in
Obsidian and keep in sync with Syncthing. Plain files in, plain files out.

This repository holds both apps:

| | Where | What |
|---|---|---|
| **Mnemos web** | `cmd/`, `internal/`, `web/` | A self-hosted Go server with the web app built in: markdown with live preview, CSV tables, one search box, capture, sync-conflict merge, trash with restore, and a read-only demo user. |
| **Mnemos for Android** | [`android/`](android/README.md) | A native app in two modes: **server** (sign in to your Mnemos, works offline and syncs back) or **phone folder** (the folder Syncthing keeps, no network at all). |

Both follow the same file rules: atomic saves with a version check, line endings kept,
unchanged files never rewritten, delete = move to the vault's `.trash/`. The look is
[nss](https://gitlab.com/niharokz/nss) everywhere.

The web app is always online: it edits the notes folder of the server it runs on. To keep
your own notes, run your own server (below). `/` is a public home page with the Android
download and the sign-in; the web app is at `/app`.

---

## The web app

| Area | What you get |
|---|---|
| Files | Folder tree (open folders remembered), filter box, pinned files, recent files, new note / folder / note from template, rename and move (never overwrites), download, open raw. The vault's note count and size sit under the tree. |
| Views | **List** (compact rows), **Box** (cards with note previews and thumbnails), **Tabs** (one tab per open note) and **Flow** — every note, newest first, one per screen: scroll or `↑`/`↓` (`j`/`k`) to the next, `Enter` to open. |
| Markdown | CodeMirror 6 editor: highlighting, formatting bar (`Ctrl+B/I/K`), find and replace, list continuation, undo. Edit / Split / Preview; task boxes can be ticked in the preview. CommonMark + tables, task lists, footnotes, properties; images from the vault; links to other notes open them. |
| Images | Paste, drop or pick (🖼 in the formatting bar) images into a note: they are saved in `attachments/` next to the note and linked as `![name](attachments/name.png)`. Obsidian embeds (`![[photo.png]]`, `![[photo.png\|300]]`) show too. Tap an image for full screen; image files open in a viewer; folder cards show thumbnails. |
| CSV / TSV | A real table: sticky header, view-only sort and filter, cell editing, add / delete rows, column stats. Saving after no edits is byte-identical; a one-cell edit rewrites only that row. |
| Other text | txt, json, yaml, code … in the editor. `.log` files open at their end and can follow new lines. Images show; PDFs and other files open or download. |
| Search | `Ctrl+K`: file names (fuzzy), full text, `#tags` (body and front matter) and properties (`status: open`). Results jump to the line. In-memory, nothing written to the vault. |
| Capture | `Alt+C` (or the PWA share target / home-screen shortcut) appends `- YYYY-MM-DD HH:mm text` to `inbox/note.md`. |
| Conflicts | Live edits from other apps: **Load theirs / Keep mine / Save mine as copy**. Syncthing `*.sync-conflict-*` copies: listed with a count, compared side by side, **Keep original / Keep copy / Merge by hand** — the loser goes to `.trash`. |
| Trash | Lists `.trash/` and restores to the original path (Mnemos records where each file came from, and when, in `.trash/.mnemos-trash.tsv`). |
| Cleanup | Settings → **Clean up now** (only when you press it): Syncthing `*sync-conflict*` copies move to the trash, then trash items older than *N* days (default 30, set there) are erased. Preview first; the demo can't. |
| Users | Your account (read-write, your vault) and an optional **demo user** (read-only, a built-in sample vault, credentials shown on the sign-in card). |
| Layout | Desktop: resizable sidebar and split. Phone: drawer, panes take turns. Light / dark. Installable, with shortcuts and a share target. |

It deliberately has **no** wikilinks, backlinks, graph, note embeds, callouts, plugins or sync
of its own. It is an editor for your files, not an Obsidian clone.

### Install (Docker)

You need Docker, your notes in a folder on the same machine, and a reverse proxy that
serves HTTPS (Caddy, Traefik, nginx or a Cloudflare tunnel) on a Docker network.

```sh
git clone https://gitlab.com/niharokz/mnemos.git && cd mnemos
cp .env.example .env

# login: a bcrypt hash (paste into .env in single quotes) and a session secret
docker compose build
read -rs PW && printf '%s' "$PW" | docker run --rm -i mnemos:local hash-password
openssl rand -hex 32                     # → MNEMOS_SECRET
```

Set the rest of `.env` — `MNEMOS_USER`, `NOTE_PATH`, `PUID`/`PGID` (from `id -u` /
`id -g`), `TZ`, `MNEMOS_NETWORK` — then:

```sh
docker compose run --rm mnemos check     # settings + any note or folder it cannot write
docker compose up -d
docker logs mnemos                       # "mnemos <version> listening on :8080 …"
```

The compose file publishes no port; point your proxy at `mnemos:8080`. With Caddy:

```
notes.example.com {
	reverse_proxy mnemos:8080
}
```

Open the site, sign in, and use your browser's **Install / Add to Home Screen**.
For a quick local try without a proxy, add `ports: ["127.0.0.1:8080:8080"]` to the service.

**Update:** `git pull && docker compose up -d --build`.

### File ownership

The container runs as `PUID:PGID` and can only write what that user may write.
If another app or container (often running as root) created folders in your vault,
`mnemos check` lists them; fix them once with
`sudo chown -R "$(id -u):$(id -g)" /path/to/notes`. A note in a read-only folder can
still be saved (in place instead of atomically, with a warning in the log); creating,
renaming or trashing there needs the folder fixed.

### Configuration (`.env`)

| Variable | Used by | Meaning |
|---|---|---|
| `NOTE_PATH` | compose | Your notes folder on the host (mounted at `/notes`). |
| `PUID`, `PGID` | compose | User the container runs as (must own `NOTE_PATH`). |
| `MNEMOS_NETWORK` | compose | Existing Docker network shared with your proxy (default `homelab`). |
| `TZ` | app | Time zone (capture stamps, conflict-copy names). |
| `MNEMOS_USER` | app | Your login. |
| `MNEMOS_PASSWORD` | app | A bcrypt hash from `mnemos hash-password` (plain text works, with a warning). Placeholders like `change-me` refuse to start. |
| `MNEMOS_SECRET` | app | Signs session cookies, 32+ characters (`openssl rand -hex 32`). Empty → everyone is signed out on every restart. |
| `MNEMOS_HIDDEN` | app | Extra names or vault paths to hide, comma separated. Dot entries (`.obsidian`, `.trash`, `.git` …) are always hidden. |
| `MNEMOS_TRASH` | app | Trash folder in the vault, default `.trash` (the one Obsidian uses). |
| `MNEMOS_AUTOSAVE_MS` | app | Autosave delay, default `1000`. |
| `MNEMOS_MAX_FILE_MB` | app | Bigger files open read-only, default `5`. |
| `MNEMOS_MAX_UPLOAD_MB` | app | Largest image that can be added to a note, default `20`. |
| `MNEMOS_CAPTURE` | app | Note that capture appends to, default `inbox/note.md`. |
| `MNEMOS_TEMPLATES` | app | Folder listed by "New from template", default `templates`. `{{date}}`, `{{time}}`, `{{title}}` are filled in. |
| `MNEMOS_DEMO` | app | `false` turns the demo user off (default on). |
| `MNEMOS_DEMO_USER`, `MNEMOS_DEMO_PASSWORD` | app | Demo login, default `demouser` / `demo`. Shown on the sign-in card on purpose. |
| `MNEMOS_BAN_AFTER`, `MNEMOS_BAN_DAYS`, `MNEMOS_BAN_PRIVATE` | app | Failed sign-ins or scanner probes in 24 h before a ban (default `5`, `0` = off), ban length (default `7` days), and whether LAN addresses can be banned (default `false`). |
| `MNEMOS_APK_URL` | app | The home page's Android download, default the newest GitLab release. |

Only `TZ` and the `MNEMOS_*` variables are passed into the container, so a `.env` shared
with other apps is fine. Server state (bans, the cleanup setting) lives in the
`mnemos-state` volume, never in your notes.

### The demo user

The demo signs in to its own copy of the sample vault (`android/demo-vault`, embedded in
the binary, unpacked into `/tmp` at start, plus a generated 2 MB log and 500-row CSV). It
never sees your notes. The server refuses every change it sends with `403` — typing,
ticking tasks and the preview work, nothing is saved, and the page says so.

### Commands

| Command | |
|---|---|
| `mnemos serve` | Run the web app (default). |
| `mnemos hash-password` | Read a password from stdin, print a bcrypt hash for `.env`. |
| `mnemos check` | Validate the settings and list every folder/file the current user cannot write. Exit 1 on problems. |
| `mnemos health` | Exit 0 if the running server answers (the Docker healthcheck). |
| `mnemos version` | Print the version. |

### Keyboard

| Keys | Action |
|---|---|
| `Ctrl/⌘ K` (or `P`) | Search |
| `Ctrl/⌘ S` | Save now |
| `Ctrl/⌘ E` | Switch between editing and preview / table |
| `Ctrl/⌘ F` | Find and replace |
| `Ctrl/⌘ B`, `I`, `K` | Bold, italic, link (in the editor) |
| `Alt N` / `Alt C` / `Alt R` | New note / capture / recent |
| `Alt B` | Show / hide the sidebar |

### Security notes

- bcrypt passwords; sign-in takes the same time whether the user exists or not.
- HMAC-signed session cookie — `__Host-mnemos` over HTTPS, HttpOnly, SameSite=Lax, 30 days — keyed per account on the secret *and* the password, so changing either signs that account out everywhere.
- The demo user is read-only **on the server**: one check in the router refuses every change for it.
- 5 failed sign-ins per IP in 15 minutes → locked out. The client IP comes from `CF-Connecting-IP`, `X-Real-IP` or the last `X-Forwarded-For` entry, so **the container must only be reachable through your proxy** (it publishes no port by default).
- **Bans:** 5 failed sign-ins *or* scanner probes (`/.env`, `/.git/…`, `wp-admin`, `*.php`, …) from one address within 24 h ban it for 7 days — it then gets `403` for everything, the about page included. Probes always get `404`; signed-in users are never struck. Bans are kept in the `mnemos-state` volume, so restarts don't clear them. LAN/loopback addresses are never banned (`MNEMOS_BAN_PRIVATE=true` changes that). `docker exec mnemos /mnemos bans` lists them, `… /mnemos unban <ip>` lifts one. Tune with `MNEMOS_BAN_AFTER` / `MNEMOS_BAN_DAYS`.
- Only the signed-in user's own vault is reachable: every `/api/` and `/raw/` request needs a valid session, and dot files (`.env`, `.git`, `.obsidian` …) are never listed, read or written, even for the owner.
- Every change is a same-origin JSON request with an `X-Mnemos` header; cross-site requests are refused.
- Paths are confined to the vault: `..` cannot climb out, symlinks that point outside are refused, dot folders are hidden.
- Strict CSP (no inline scripts, no third-party anything), HSTS, no framing. Raw files are served with a sandbox CSP; HTML/JS files as plain text.
- `FROM scratch` image, non-root, read-only, `cap_drop: ALL`, `no-new-privileges`.
- Changes are logged with IP and path, sign-ins with user and IP — never contents or passwords.

See [SECURITY.md](SECURITY.md) for reporting problems.

### Running without Docker

```sh
go build ./cmd/mnemos                    # Go 1.23+, no network needed (dependencies are vendored)
MNEMOS_NOTES=~/notes MNEMOS_PASSWORD='…' ./mnemos   # http://localhost:8080
```

A `.env` file in the working directory is read too (real environment variables win).

## The Android app

Download the newest APK from the home page or
[GitLab releases](https://gitlab.com/niharokz/mnemos/-/releases/permalink/latest/downloads/mnemos.apk).
At setup (or later, Settings → Vault → Storage) pick one:

- **Your Mnemos server** — `https://` address, user and password. The phone keeps a copy of
  what you open and queues edits made offline; they are sent when the network is back, and a
  note that changed on the server meanwhile is kept as a `(conflict …)` copy, never
  overwritten. The demo account can't be used here.
- **A folder on the phone** — e.g. the one Syncthing keeps. No network at all.

Everything else — features, widgets, build, signing — is in [android/README.md](android/README.md).

## Building and releasing

Both apps share one version: `VERSION` (embedded in the web binary) and
`android/version.properties` (`versionName`, plus a `versionCode` that goes up by one each
release). `sh scripts/check-version.sh` checks they agree and that both changelogs have a
`## <version>` section.

### Locally

**Web app** — build and (re)start the container from the repo root:

```sh
docker compose up -d --build
docker logs mnemos            # "mnemos <version> listening on :8080 …" and the guard line
```

**Android app** — from `android/` (needs only Docker):

```sh
cd android
./build.sh keystore           # once: key + passwords in $KEYS_HOME/.mnemos-keys (back it up)
./build.sh check              # unit tests + lint
./build.sh                    # signed APK → android/dist/mnemos-<version>.apk
./build.sh install            # adb install over USB (optional)
```

The key folder is `$KEYS_HOME/.mnemos-keys` (default `~/.local/share/keys/.mnemos-keys`);
`MNEMOS_KEY_DIR` overrides it. `build.sh` keeps it at mode 700 and its files at 600. The
release runner uses the same key and the same build, so a local APK and a released one
install over each other.

### Applying a patch

Fixes that arrive as a zip go in `patch/` (gitignored, so it is never committed). The paths
in the zip start at the repo root:

```sh
cd /path/to/mnemos
unzip -o patch/<name>.zip      # -o overwrites without asking (lowercase o)
git status && git diff --stat  # review what changed
docker compose up -d --build   # web app; for Android: cd android && ./build.sh
git checkout -- . && git clean -fd -e patch   # undo, if needed
```

### Releases and the Android APK

A release is a tag:

```sh
# bump VERSION + android/version.properties (versionCode +1), write both changelog sections
sh scripts/check-version.sh
git commit -am "Mnemos 1.2.0" && git push
git tag v1.2.0 && git push origin v1.2.0
```

GitLab CI ([.gitlab-ci.yml](.gitlab-ci.yml)) checks every push and merge request on shared
runners (Go format/vet/tests, front-end syntax, versions, no committed secrets, the Docker
build). A tag then builds the signed APK on **your own runner** — so it uses none of the free
tier's CI minutes and the signing key never leaves your server — uploads it to the project's
package registry, pushes the web image (`registry.gitlab.com/niharokz/mnemos:<version>` and
`:latest`) and creates the release with that version's changelog sections. The newest APK
is always at

```
https://gitlab.com/niharokz/mnemos/-/releases/permalink/latest/downloads/mnemos.apk
```

and the home page links there (`MNEMOS_APK_URL`). A tag that does not match `VERSION`, or a
version without a changelog section, fails the pipeline. To redo a failed tag: fix, push,
`git tag -f v1.2.0 && git push -f origin v1.2.0`.

Set up the runner once, on the machine that holds the signing key and Docker:

1. Install [GitLab Runner](https://docs.gitlab.com/runner/install/linux-repository/) and let it
   run as **your user** (it needs Docker and `$KEYS_HOME/.mnemos-keys`):
   `sudo gitlab-runner uninstall && sudo gitlab-runner install --user "$USER" --working-directory "$HOME/.gitlab-runner" && sudo gitlab-runner start`.
2. GitLab → the project → **Settings → CI/CD → Runners → New project runner**: tag
   `mnemos-apk`, untick "Run untagged jobs". Copy the token, then
   `sudo gitlab-runner register --url https://gitlab.com --token <token> --executor shell`.
3. The runner does not read `~/.zprofile`: if your keys are not in
   `~/.local/share/keys`, add `environment = ["MNEMOS_KEY_DIR=/path/to/keys/.mnemos-keys"]`
   to the runner's section in `~/.gitlab-runner/config.toml` (or `/etc/gitlab-runner/config.toml`).
4. *Settings → Repository → Protected tags*: protect `v*`, so only you can release.

The first release build makes the Docker build image and fills the Gradle cache (a few GB,
kept in the `mnemos-gradle-cache` volume); later ones take a few minutes.

Issues and merge requests have templates (`.gitlab/`): bug, feature, and a merge request
checklist.

## Development

```sh
go vet ./... && go test ./...
sh scripts/check-public.sh               # before pushing: no secrets, keys or private words
```

Install the check as a pre-push hook with `ln -s ../../scripts/check-public.sh .git/hooks/pre-push`,
and list words that must never be published (your domain, host paths …) in `.private-words`
(gitignored).

The only third-party browser code is CodeMirror, bundled into
`web/static/vendor/codemirror.js` and committed. To rebuild it:

```sh
cd tools/codemirror && npm ci && npm run build
```

Web icons come from the same mark as the Android launcher icon:
`python3 scripts/make-web-icons.py`, then render `icon.svg` / `maskable.svg` to the PNGs.

See [ARCHITECTURE.md](ARCHITECTURE.md) for how the pieces fit and [CHANGELOG.md](CHANGELOG.md) for releases.

## License

MIT
