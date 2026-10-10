# Security

## Reporting a problem

Please report security issues privately, not in a public issue:
open a **confidential issue** at <https://gitlab.com/niharokz/samanote/-/issues/new>
(tick "This issue is confidential"). Include what you found, how to reproduce it,
and the version (`samanote version`, also shown at the bottom of the about page).

You should get a reply within a week. Fixes are released as a new version and
noted in `CHANGELOG.md`.

## Supported versions

Only the latest release is supported. Update with
`git pull && docker compose up -d --build`.

## What samanote protects, and how

See "Security notes" in `README.md` and "Security model" in `ARCHITECTURE.md`.
In short: single user, bcrypt password, signed `__Host-` session cookie bound to
the password, per-IP login lockout, same-origin JSON writes with a custom header,
paths confined to the notes folder, strict CSP and security headers, and a
non-root, read-only, capability-free container that can only write to your notes.

## Your part

- Use a long passphrase and store it as a bcrypt hash in `SAMANOTE_PASSWORD`.
- Set `SAMANOTE_SECRET` to 32+ random characters (`openssl rand -hex 32`).
- Keep `.env` out of git (`.gitignore` does this). A shared `.env` is fine:
  `docker-compose.yml` passes only `TZ` and the `SAMANOTE_*` variables into the
  container.
- Serve samanote only over HTTPS, behind a proxy you control, and do not publish
  its port: the login lockout trusts the client-IP headers your proxy sets.
