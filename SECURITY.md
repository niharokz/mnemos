# Security

## Reporting a problem

Please report security issues privately, not in a public issue:
open a **confidential issue** at <https://gitlab.com/niharokz/mnemos/-/issues/new>
(tick "This issue is confidential"). Include what you found, how to reproduce it,
and the version (`mnemos version`, also shown at the bottom of the about page; for the
Android app, Settings → About).

You should get a reply within a week. Fixes are released as a new version and
noted in `CHANGELOG.md` (web) or `android/CHANGELOG.md` (Android).

## Supported versions

Only the latest release of each app is supported. Update the web app with
`git pull && docker compose up -d --build`; the Android app from GitLab Releases.

## What Mnemos protects, and how

See "Security notes" in `README.md` and "Security model" in `ARCHITECTURE.md`.
In short: bcrypt passwords, signed `__Host-` session cookies bound to each account's
password, a demo user that is read-only on the server and never sees your vault,
per-IP login lockout, same-origin JSON writes with a custom header, paths confined to
the notes folder, strict CSP and security headers, and a non-root, read-only,
capability-free container that can only write to your notes.

The Android app has no INTERNET permission; see `android/README.md`.

## Your part

- Use a long passphrase and store it as a bcrypt hash in `MNEMOS_PASSWORD`.
- Set `MNEMOS_SECRET` to 32+ random characters (`openssl rand -hex 32`).
- Keep `.env` out of git (`.gitignore` does this). A shared `.env` is fine:
  `docker-compose.yml` passes only `TZ` and the `MNEMOS_*` (or old `SAMANOTE_*`)
  variables into the container.
- Serve Mnemos only over HTTPS, behind a proxy you control, and do not publish
  its port: the login lockout trusts the client-IP headers your proxy sets.
- The demo login is public on purpose. If you do not want a demo, set `MNEMOS_DEMO=false`.
- Keep the Android signing key (`~/.mnemos-keys`) out of the repo and backed up;
  `scripts/check-public.sh` refuses a push that tracks a keystore.
