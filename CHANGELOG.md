# Changelog

## 2.1.0 - 2026-10-10

- **About page.** `/` is now a public page describing samanote, with install steps and a Sign in button (no demo). The app moved to **`/app`**; old `/#/n/…` links and the installed home-screen app are sent there automatically. Reinstalling the phone app once makes `/app` its start page and adds a **New note** shortcut.
- **Sessions.** Over HTTPS the cookie is now `__Host-samanote`, and it is bound to the password: changing the password (or `SAMANOTE_SECRET`) signs every device out. Everyone is signed out once by this update.
- **Login lockout fixed behind proxies.** The visitor IP now comes from `CF-Connecting-IP`, `X-Real-IP` or the *last* `X-Forwarded-For` entry. Before, a client could dodge the lockout by forging the first entry. Failed logins are slowed down progressively, and the lockout table can no longer grow without limit.
- **CSRF.** Every change — including login and logout — must be same-origin (`Sec-Fetch-Site` / `Origin`) as well as carry the `X-Samanote` header.
- **Hardening.** HSTS, COOP/CORP, a wider Permissions-Policy; header and read-header limits; unexpected errors no longer show server paths in the browser; access log of every change and sign-in (IP, method, path, status — never contents).
- **Read-only folders.** If a folder in the vault is owned by another user, saving a note in it falls back to writing in place (with a warning in the log). Anything else the OS refuses now returns a clear 403 naming the path and the uid. A refused save is no longer retried every 8 seconds.
- **`samanote check`.** Validates the settings and lists every folder and file the container user cannot write. A placeholder password (`change-me` …) now refuses to start; a missing or short secret and a plain-text password are warned about.
- **Ops.** `FROM scratch` image with vendored dependencies (builds need no network), `VERSION` build arg, `cap_drop: ALL`, network name from `SAMANOTE_NETWORK`, `robots.txt`, maskable icon.
- **Docs.** `SECURITY.md`, `CHANGELOG.md`, `scripts/check-public.sh` pre-push hook; README and ARCHITECTURE updated.

## 2.0.0 - 2026-10-06

A rewrite of the old single-file samanote.

- **Go, one binary**, web app embedded. Configuration only from environment variables (`.env`); `config.yml` is gone.
- **CodeMirror 6 editor** with markdown highlighting, find and replace, list continuation; **live preview** rendered by the server (goldmark: GFM, footnotes, properties block, hard line breaks).
- **Conflict-safe saving.** Autosave with a content version; a note changed elsewhere raises a bar to load theirs, keep mine, or save mine as a copy. Atomic writes keep permissions and line endings.
- **Trash instead of delete**, into the vault's `.trash` folder. Rename/move never overwrites.
- **Security.** The old forgeable `session=1` cookie is replaced by an HMAC-signed one; bcrypt passwords; login rate limit; changes need POST/PUT with a custom header; paths confined to the vault including symlinks; strict CSP.
- **nss theme**, light and dark, phone/tablet/desktop layouts, installable.
