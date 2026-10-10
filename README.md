# samanote

**A small, self-hosted markdown editor with live preview for a folder of notes** — for
example the vault you also open in Obsidian. Pick a note, write, watch the preview, and it
saves itself.

- **Editor + live preview**, side by side or one at a time. Works on a phone, a tablet and a desktop.
- **Plain files.** No database, no index, nothing written next to your notes. Obsidian, Syncthing and your scripts keep working.
- **No lost edits.** Every save checks that no other app changed the file meanwhile. If one did, you choose: load theirs, keep yours, or save yours as a copy.
- **Nothing is deleted.** "Delete" moves the note to the vault's `.trash` folder (the one Obsidian uses).
- **Single Go binary** in an empty Docker image. No third-party requests, no tracking. The look is [nss](https://gitlab.com/niharokz/nss).

`/` is a public about page with install steps; the app itself is at `/app`.

---

## What you can do

| Area | What you get |
|---|---|
| Notes | Folder tree (open folders are remembered), filter box, new note / folder anywhere, rename and move (never overwrites), move to trash, open the raw file, "recently changed" list. |
| Editor | CodeMirror 6: markdown highlighting, soft wrap, undo, find and replace, list continuation on Enter, bracket closing. Properties at the top are muted. Windows line endings are kept. |
| Preview | CommonMark + tables, task lists, strikethrough, autolinks, footnotes. Single line breaks are kept, like Obsidian. Properties as a small block. Relative images load from the vault; links to other notes open them. Raw HTML in notes is not rendered. |
| Saving | Autosave about 1 s after you stop typing, `Ctrl+S` any time, a status dot. Saves are atomic. When you come back to the tab, a note changed elsewhere reloads by itself if you had no unsaved edits. |
| Layout | Desktop: resizable sidebar and split. Phone: the tree is a drawer; edit and preview take turns. Light / dark follows the system, or pick one. Installable on the home screen, with a "New note" shortcut. |

It deliberately has **no** wikilinks, backlinks, graph, tags pane, embeds, callouts,
plugins or sync. It is a web editor for your files, not an Obsidian clone.

## Install (Docker)

You need Docker, your notes in a folder on the same machine, and a reverse proxy that
serves HTTPS (Caddy, Traefik, nginx or a Cloudflare tunnel) on a Docker network.

```sh
git clone https://gitlab.com/niharokz/samanote.git && cd samanote
cp .env.example .env

# login: a bcrypt hash (paste into .env in single quotes) and a session secret
docker compose build
read -rs PW && printf '%s' "$PW" | docker run --rm -i samanote:local hash-password
openssl rand -hex 32                     # → SAMANOTE_SECRET
```

Set the rest of `.env` — `SAMANOTE_USER`, `NOTE_PATH`, `PUID`/`PGID` (from `id -u` /
`id -g`), `TZ`, `SAMANOTE_NETWORK` — then:

```sh
docker compose run --rm samanote check   # settings + any note or folder it cannot write
docker compose up -d
docker logs samanote                     # "samanote 2.1.0 listening on :8080 …"
```

The compose file publishes no port; point your proxy at `samanote:8080`. With Caddy:

```
notes.example.com {
	reverse_proxy samanote:8080
}
```

Open the site, sign in, and use your browser's **Install / Add to Home Screen**.
For a quick local try without a proxy, add `ports: ["127.0.0.1:8080:8080"]` to the service.

**Update:** `git pull && docker compose up -d --build`.

### File ownership

The container runs as `PUID:PGID` and can only write what that user may write.
If another app or container (often running as root) created folders in your vault,
`samanote check` lists them; fix them once with
`sudo chown -R "$(id -u):$(id -g)" /path/to/notes`. A note in a read-only folder can
still be saved (in place instead of atomically, with a warning in the log); creating,
renaming or trashing there needs the folder fixed.

## Configuration (`.env`)

| Variable | Used by | Meaning |
|---|---|---|
| `NOTE_PATH` | compose | Your notes folder on the host (mounted at `/notes`). |
| `PUID`, `PGID` | compose | User the container runs as (must own `NOTE_PATH`). |
| `SAMANOTE_NETWORK` | compose | Existing Docker network shared with your proxy (default `homelab`). |
| `TZ` | app | Time zone (used in conflict-copy names). |
| `SAMANOTE_USER` | app | The single login. |
| `SAMANOTE_PASSWORD` | app | A bcrypt hash from `samanote hash-password` (plain text works, with a warning). Placeholders like `change-me` refuse to start. |
| `SAMANOTE_SECRET` | app | Signs session cookies, 32+ characters (`openssl rand -hex 32`). Empty → you are signed out on every restart. |
| `SAMANOTE_HIDDEN` | app | Extra names or vault paths to hide, comma separated. Dot entries (`.obsidian`, `.trash`, `.git` …) are always hidden. |
| `SAMANOTE_AUTOSAVE_MS` | app | Autosave delay, default `1000`. |
| `SAMANOTE_MAX_FILE_MB` | app | Bigger files open read-only, default `5`. |

Only `TZ` and the `SAMANOTE_*` variables are passed into the container, so a `.env`
shared with other apps is fine.

## Commands

| Command | |
|---|---|
| `samanote serve` | Run the web app (default). |
| `samanote hash-password` | Read a password from stdin, print a bcrypt hash for `.env`. |
| `samanote check` | Validate the settings and list every folder/file the current user cannot write. Exit 1 on problems. |
| `samanote health` | Exit 0 if the running server answers (the Docker healthcheck). |
| `samanote version` | Print the version. |

## Keyboard

| Keys | Action |
|---|---|
| `Ctrl/⌘ S` | Save now |
| `Ctrl/⌘ E` | Switch between editing and preview |
| `Ctrl/⌘ F` | Find and replace |
| `Alt N` | New note |
| `Alt B` | Show / hide the sidebar |

## Security notes

- Single user; bcrypt password; constant-time comparison.
- HMAC-signed session cookie — `__Host-samanote` over HTTPS, HttpOnly, SameSite=Lax, 30 days — keyed on the secret *and* the password, so changing either signs every device out.
- 5 failed sign-ins per IP in 15 minutes → locked out. The client IP comes from `CF-Connecting-IP`, `X-Real-IP` or the last `X-Forwarded-For` entry, so **the container must only be reachable through your proxy** (it publishes no port by default).
- Every change is a same-origin JSON request with an `X-Samanote` header; cross-site requests are refused.
- Paths are confined to the notes folder: `..` cannot climb out, symlinks that point outside are refused, dot folders are hidden.
- Strict CSP (no inline scripts, no third-party anything), HSTS, no framing. Raw files are served with a sandbox CSP; HTML/JS files as plain text.
- `FROM scratch` image, non-root, read-only, `cap_drop: ALL`, `no-new-privileges`.
- Changes and sign-ins are logged with IP and path, never with contents or passwords.

See [SECURITY.md](SECURITY.md) for reporting problems.

## Running without Docker

```sh
go build ./cmd/samanote                  # Go 1.23+, no network needed (dependencies are vendored)
SAMANOTE_NOTES=~/notes SAMANOTE_PASSWORD='…' ./samanote   # http://localhost:8080
```

A `.env` file in the working directory is read too (real environment variables win).

## Development

```sh
go vet ./... && go test ./...
sh scripts/check-public.sh               # before pushing: no secrets or private words
```

Install the check as a pre-push hook with `ln -s ../../scripts/check-public.sh .git/hooks/pre-push`,
and list words that must never be published (your domain, host paths …) in `.private-words`
(gitignored).

The only third-party browser code is CodeMirror, bundled into
`web/static/vendor/codemirror.js` and committed. To rebuild it:

```sh
cd tools/codemirror && npm ci && npm run build
```

See [ARCHITECTURE.md](ARCHITECTURE.md) for how the pieces fit and [CHANGELOG.md](CHANGELOG.md) for releases.

## License

MIT
