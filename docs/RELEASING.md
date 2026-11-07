# Releasing

One version for everything (desktop, Android, web). A tag `vX.Y.Z` makes
GitLab build the three installers on your runner and publish a release.

## Every release

1. Make sure `main` is green in **Build → Pipelines**.
2. Add the notes to `CHANGELOG.md`:
   ```md
   ## 0.0.2 - 2026-11-20

   - What changed, in plain words.
   ```
   Commit it (`git commit -am "Changelog for 0.0.2"`).
3. Run
   ```sh
   sh scripts/release.sh 0.0.2
   ```
   It writes the version into `VERSION`, `frontend/package.json`,
   `app/Cargo.toml` and `app/tauri.conf.json`, runs the version check, commits
   "mnemostxt 0.0.2", tags `v0.0.2`, and (after you answer `y`) pushes the
   branch and the tag to every remote.
4. Watch **Build → Pipelines**: the `release-files` job (~10–15 min) and then
   `release`.
5. Update the web app on the server:
   ```sh
   cd <deploy folder>/mnemostxt && git pull && docker compose up -d --build
   ```
6. Install the new `.apk` on the phone and the `.deb` / `setup.exe` on the
   computers (or download them from the home page of your web app).

## The first release (0.0.1)

0.0.1 is already written in every version file and in `CHANGELOG.md`, so it
only needs the tag:

```sh
sh scripts/check-version.sh v0.0.1
git tag -a v0.0.1 -m "mnemostxt 0.0.1"
git push origin v0.0.1
```

Before that: the runner is set up ([RUNNER.md](RUNNER.md)), `v*` tags are
protected, and the signing key exists.

## A release failed

`v*` tags are protected, so GitLab refuses to move them (`Protected tags
cannot be updated`). Fix the problem, commit, push, then:

1. GitLab → **Code → Tags** → delete `vX.Y.Z` (the trash icon; maintainers
   may delete protected tags in the UI). If a release was already created,
   delete it first in **Deploy → Releases**, and its files in
   **Deploy → Package registry**.
2. Re-create the tag on the fixed commit and push it:
   ```sh
   git tag -fa vX.Y.Z -m "mnemostxt X.Y.Z"
   git push -f origin vX.Y.Z      # GitLab: new tag; GitHub/Codeberg: moved
   ```

## Roll back the web app

```sh
git checkout v0.0.1 && docker compose up -d --build
```

Desktop and Android: install the older file from its release page.

## Building by hand

```sh
sh scripts/build-release.sh            # all three, into dist/release/
sh scripts/build-release.sh deb        # only the Linux package
```
