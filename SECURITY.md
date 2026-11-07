# Security

## Reporting

Please report security problems privately to the maintainer (GitLab profile
email), not in a public issue.

## How mnemostxt is built to be safe

**Desktop and Android apps**

- Work offline. The only network use is opening a link you click.
- Files are reached through the app's own commands (app/src), not a general
  file-system plugin; the window may only call those commands, the file
  dialogs and "open URL" (app/capabilities/default.json).
- Strict Content Security Policy: no remote scripts, no inline scripts.
- Saves are atomic and refuse to overwrite a file that changed on disk.
- Unsaved edits are backed up in the app's own data folder, not next to your
  files.

**Web app (server/)**

- One user, password as plain text or bcrypt hash, compared in constant time.
- Session cookie signed with HMAC-SHA256 from `MNEMOSTXT_SECRET` and the
  password (changing either signs out everywhere); `__Host-` cookie over HTTPS,
  HttpOnly, SameSite=Lax.
- Every change needs the `X-Mnemostxt` header and same-origin, which blocks
  cross-site forgery.
- Failed sign-ins: 5 within 15 minutes locks the address; public addresses are
  then banned for `MNEMOSTXT_BAN_HOURS` (bans survive restarts).
- Paths are checked lexically and again after resolving symlinks: nothing
  outside the notes folder can be read or written.
- Runs as your user in a read-only, capability-free container from `scratch`.

**Release keys**

The Android signing key stays on the build machine (outside the repository);
`scripts/check-public.sh` blocks commits of keys, passwords, hashes and any
words listed in your own `.private-words`.
