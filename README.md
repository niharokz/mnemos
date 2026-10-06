# samanote

A small, self-hosted **markdown editor with live preview** for a folder of notes —
for example the vault you also open in Obsidian. Pick a note from the tree, write,
and it saves itself.

- **Editor + live preview**, side by side or one at a time. Works on a phone, a tablet and a desktop.
- **Plain files.** No database and no extra files: notes stay ordinary `.md` files, so Obsidian, Syncthing and your scripts keep working.
- **No lost edits.** Every save checks the file was not changed by another app meanwhile. If it was, you choose: load theirs, keep yours, or save yours as a copy.
- **Nothing is deleted.** "Delete" moves the note to the vault's `.trash` folder (the one Obsidian uses).
- One static Go binary, one container, no third-party requests, no tracking. The look is [nss](https://gitlab.com/niharokz/nss).

## Features

| Area | What you get |
|---|---|
| Notes | Folder tree (open folders are remembered), filter box, new note / new folder anywhere, rename and move (never overwrites), move to trash, open the raw file, recently changed list. |
| Editor | CodeMirror 6: markdown highlighting, soft wrap, undo, find and replace (`Ctrl+F`), list continuation on Enter, bracket closing. YAML properties at the top are shown muted. Windows line endings are kept. |
| Preview | CommonMark + GitHub extras: tables, task lists, strikethrough, autolinks, footnotes. Single line breaks are kept, like Obsidian. Properties shown as a small block. Relative images load from the vault; links to other notes open them. Raw HTML in notes is not rendered. |
| Saving | Autosave about 1 s after you stop typing, `Ctrl+S` any time, a status dot (saved / edited / saving / conflict). Saves are atomic. When you come back to the tab, a note changed elsewhere reloads by itself if you had no unsaved edits. |
| Layout | Desktop: resizable sidebar and split. Phone: the tree is a drawer; edit and preview take turns full-screen. Light / dark follows the system, or pick one. Can be added to the home screen. |
| Security | One user from `.env` (plain or bcrypt password), signed session cookie, sign-in rate limit, an anti-CSRF header on every change, paths locked inside the vault (no `..`, no symlinks out, dot folders hidden), strict Content-Security-Policy. |

What it deliberately does **not** do: wikilinks, backlinks, graph, tags pane, embeds,
callouts, plugins, sync. It is a web editor for the files, not an Obsidian clone.

## Quick start (Docker)

```sh
git clone https://gitlab.com/niharokz/samanote.git && cd samanote
cp .env.example .env        # set NOTE_PATH, PUID/PGID, SAMANOTE_USER, SAMANOTE_PASSWORD, SAMANOTE_SECRET
docker network create homelab 2>/dev/null || true
docker compose up -d --build
```

The compose file publishes no port: put a reverse proxy on the `homelab` network in
front of it. With Caddy:

```
notes.example.com {
	reverse_proxy samanote:8080
}
```

For a quick local try without a proxy, add `ports: ["127.0.0.1:8080:8080"]` to the service.

To store a bcrypt hash instead of a plain password:

```sh
docker compose run --rm -it samanote hash-password
# paste the result into .env in single quotes: SAMANOTE_PASSWORD='$2a$12$…'
```

## Configuration (`.env`)

| Variable | Used by | Meaning |
|---|---|---|
| `NOTE_PATH` | compose | The vault on the host (mounted at `/notes`). |
| `PUID`, `PGID` | compose | User the container runs as (must own `NOTE_PATH`, so new notes are yours). |
| `TZ` | app | Time zone (used in conflict-copy names). |
| `SAMANOTE_USER`, `SAMANOTE_PASSWORD` | app | Your sign-in; the password may be a bcrypt hash. |
| `SAMANOTE_SECRET` | app | Signs session cookies; set it so you stay signed in across restarts (`openssl rand -hex 32`). |
| `SAMANOTE_HIDDEN` | app | Extra names or vault paths to hide, comma separated. Dot entries are always hidden. |
| `SAMANOTE_AUTOSAVE_MS` | app | Autosave delay, default `1000`. |
| `SAMANOTE_MAX_FILE_MB` | app | Bigger files open read-only, default `5`. |

## Keyboard

| Keys | Action |
|---|---|
| `Ctrl/⌘ S` | Save now |
| `Ctrl/⌘ E` | Switch between editing and preview |
| `Ctrl/⌘ F` | Find and replace |
| `Alt N` | New note |
| `Alt B` | Show / hide the sidebar |

## Running without Docker

```sh
go build -o samanote ./cmd/samanote
SAMANOTE_NOTES=~/notes SAMANOTE_PASSWORD=secret ./samanote serve   # http://localhost:8080
```

A `.env` file in the working directory is read too. Other commands: `samanote
hash-password`, `samanote health`, `samanote version`.

## Development

```sh
go vet ./... && go test ./...
```

The only third-party browser code is CodeMirror, bundled into
`web/static/vendor/codemirror.js` and committed, so the app makes no CDN requests.
To rebuild it (e.g. after bumping versions in `tools/codemirror/package.json`):

```sh
cd tools/codemirror && npm ci && npm run build
```

See [ARCHITECTURE.md](ARCHITECTURE.md) for how the pieces fit.

## License

MIT
