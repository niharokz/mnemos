# The release runner

Check jobs run on GitLab's shared runners. The **release build** (a `vX.Y.Z`
tag) runs on your own machine through a GitLab **shell runner** tagged
`mnemostxt-build`, because it needs Docker, a ~10 GB builder image, warm caches
and the Android signing key, none of which should live on shared runners.

The job runs `sh scripts/build-release.sh deb exe apk`, which uses Docker to
build the Linux `.deb`, the Windows `setup.exe` (cross-compiled) and the
signed Android `.apk`.

## What the machine needs

- Linux x86-64 with **Docker** (Engine 24+), about **25 GB** free disk
  (builder image ~10 GB, caches up to ~10 GB) and 8 GB RAM or more.
- **gitlab-runner** installed (`gitlab-runner --version`).
- Internet access during builds (crates, npm, Gradle, and the Windows SDK
  headers that `cargo-xwin` downloads once).

## 1. Find out which user the runner runs as

```sh
ps -o user= -C gitlab-runner          # the service user, e.g. gitlab-runner
grep -E '^\s*(user|working-directory)' /etc/systemd/system/gitlab-runner.service 2>/dev/null
```

Call it `RUNNER_USER` below. If you installed the runner with
`--user <you>`, that is your own login.

## 2. Let that user use Docker

```sh
sudo usermod -aG docker RUNNER_USER
sudo systemctl restart gitlab-runner
sudo -u RUNNER_USER docker ps          # must work without sudo
```

## 3. Give the runner to the project

**A. You already have a shell runner for another project** (reuse it):

1. Open the project that *owns* the runner → **Settings → CI/CD → Runners**.
2. Click the pencil on the runner → **Tags**: add `mnemostxt-build`
   (keep the other project's tag). Leave **Run untagged jobs** off. Save.
3. Open **mnemostxt → Settings → CI/CD → Runners → Other available project
   runners** and click **Enable for this project** on that runner.

**B. A new runner just for mnemostxt:**

1. **mnemostxt → Settings → CI/CD → Runners → New project runner**.
2. Tags: `mnemostxt-build`. **Run untagged jobs**: off. Description:
   `mnemostxt release builder`. Create, copy the `glrt-…` token.
3. On the machine:
   ```sh
   sudo gitlab-runner register --non-interactive \
     --url https://gitlab.com --token glrt-XXXXXXXX \
     --executor shell --description "mnemostxt release builder"
   sudo gitlab-runner verify
   ```

Either way, also turn on **Enable instance runners for this project** (same
page) so the check jobs run on GitLab's shared runners.

## 4. Make the Android signing key

As the runner user, once. The key lives outside the repository:

```sh
sudo -u RUNNER_USER -H sh -c 'cd /tmp && git clone https://gitlab.com/niharokz/mnemostxt.git mx && cd mx && sh scripts/make-keystore.sh; rm -rf /tmp/mx'
```

This writes `~/.local/share/keys/.mnemostxt-keys/mnemostxt.jks` and
`keystore.env` in the runner user's home (or `$KEYS_HOME/.mnemostxt-keys` when
`KEYS_HOME` is set). **Back the folder up**: Android installs updates only when
they are signed with the same key.

If the key is somewhere else, tell the runner — in
`/etc/gitlab-runner/config.toml`, inside the `[[runners]]` block of this runner:

```toml
  environment = ["MNEMOSTXT_KEY_DIR=/path/to/.mnemostxt-keys"]
```

then `sudo gitlab-runner restart`. The folder must be readable by the runner
user (`chmod 700` folder, `600` files, owned by that user).

## 5. Warm the caches (optional, saves ~20 minutes on the first release)

```sh
sudo -u RUNNER_USER -H sh -c 'cd /tmp && git clone https://gitlab.com/niharokz/mnemostxt.git mx && cd mx && sh scripts/build-release.sh deb exe apk; rm -rf /tmp/mx'
```

The first run builds the image `mnemostxt-builder:1` and fills the Docker
volume `mnemostxt-build-cache`; later builds reuse both.

## 6. Project settings on GitLab

- **Settings → Repository → Protected tags**: add `v*` (only maintainers may
  create release tags).
- **Settings → General → Visibility**: *Package registry* and *Releases* on.
- **Settings → CI/CD → General pipelines**: timeout **2h** (the first release
  build is slow; later ones take 10–15 minutes).

## 7. Check it

Push a tag (see [RELEASING.md](RELEASING.md)). In **Build → Pipelines** the
`release-files` job should be picked up by your runner (its name shows in the
job log header). When it finishes, **Deploy → Releases** has the three files
and these links work:

```
https://gitlab.com/niharokz/mnemostxt/-/releases/permalink/latest/downloads/mnemostxt.apk
https://gitlab.com/niharokz/mnemostxt/-/releases/permalink/latest/downloads/mnemostxt-setup.exe
https://gitlab.com/niharokz/mnemostxt/-/releases/permalink/latest/downloads/mnemostxt.deb
```

## Troubleshooting

| Symptom | Fix |
|---|---|
| Job stuck "pending" | the runner lacks the `mnemostxt-build` tag, or is not enabled for the project (step 3) |
| `permission denied … docker.sock` | step 2, then restart the runner |
| `no signing key in …` | step 4, or set `MNEMOSTXT_KEY_DIR` |
| `cargo-xwin` download errors | the machine needs internet; retry the job |
| Disk full | `docker volume rm mnemostxt-build-cache` and `docker image prune` |
| Changed `docker/builder/Dockerfile` | bump `BUILDER_TAG` in `scripts/build-release.sh`; the next build makes the new image |
