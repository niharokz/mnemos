# The release runner (all in Docker)

Check jobs run on GitLab's shared runners. The **release build** (a `vX.Y.Z`
tag) runs on your own machine, on a GitLab runner that **lives in a container**
and can serve all your apps:

```
runner folder/                       e.g. <deploy folder>/gtlbrunner
  docker-compose.yml                 from docker/runner/ in this repo
  config/config.toml                 runner settings + tokens, one [[runners]] per app
  keys/mnemostxt/                    mnemostxt.jks + keystore.env (mode 700/600)
  keys/<other app>/

host: only Docker
 ├─ gtlbrunner             (container: gitlab-runner, Docker executor)
 │    starts each job as a sibling container ↓
 ├─ mnemostxt release job  (image mnemostxt-builder:4)
 │    mounts: volume mnemostxt-build-cache → /build-cache, keys/mnemostxt → /keys (read-only)
 └─ volume mnemostxt-build-cache
```

Nothing is installed on the host; removing it all is `docker compose down`,
`docker volume rm mnemostxt-build-cache` and deleting the folder.

> The runner container gets the Docker socket (that is how it starts job
> containers), which is root-equivalent on the host. Use it for your own
> projects only: project runners, protected `v*` tags, `allowed_images`.

Each app gets **its own `[[runners]]` block** (registered from that app's
project, tag `<app>-app`), so a job only ever sees its own key and cache.

## Needs

Linux x86-64 with Docker (Compose v2, BuildKit), ~25 GB free, 8 GB RAM,
internet during builds.

## 1. The runner folder

```sh
mkdir -p <runner folder>/{config,keys/mnemostxt}
chmod 700 <runner folder>/config <runner folder>/keys <runner folder>/keys/mnemostxt
cp docker/runner/docker-compose.yml <runner folder>/
```

## 2. The release image

```sh
cd <runner folder>
docker compose build mnemostxt-builder        # ~15 min, from the repo's docker/builder
docker image ls mnemostxt-builder             # mnemostxt-builder   1
```

## 3. The signing key

From any checkout of this repo:

```sh
MNEMOSTXT_KEY_DIR=<runner folder>/keys/mnemostxt sh scripts/make-keystore.sh
```

**Back up** `keys/mnemostxt/` — Android installs updates only when they are
signed with the same key.

## 4. Register mnemostxt

1. GitLab → **mnemostxt → Settings → CI/CD → Runners → New project runner**:
   tag `mnemostxt-app`, **Run untagged jobs** off, description
   `mnemostxt-app` → *Create runner* → copy the `glrt-…` token.
2. ```sh
   cd <runner folder>
   docker compose run --rm runner register --non-interactive \
     --url https://gitlab.com --token glrt-XXXXXXXX \
     --executor docker --docker-image alpine:3.20 --name mnemostxt-app
   ```
3. Edit `config/config.toml` (it belongs to root: `sudo`). Make the top and
   the new block match `docker/runner/config.toml.example`: `concurrent = 1`,
   `pull_policy`, `allowed_images`, `allowed_pull_policies` and the `volumes`
   (absolute host path of `keys/mnemostxt`).
4. ```sh
   docker compose up -d
   docker compose logs -f runner               # "Configuration loaded", no errors
   ```
   The runner gets a green dot in **Settings → CI/CD → Runners**.

## 5. Project settings (mnemostxt)

- **Settings → Repository → Protected tags**: `v*`.
- **Settings → General → Visibility**: Package registry and Releases on.
- **Settings → CI/CD → General pipelines**: timeout **2h** (first build).
- **Settings → CI/CD → Runners**: *Enable instance runners for this project*
  (the check jobs).

## 6. Release

See [RELEASING.md](RELEASING.md). The `release-files` job log header names
the runner `mnemostxt-app` and the image `mnemostxt-builder:4`.

## Adding another app

1. `mkdir -m 700 keys/<app>` and put that app's key there.
2. Add a `<app>-builder` service to `docker-compose.yml` and build it.
3. Register from that app's project with tag `<app>-app` (step 4), with its
   own `allowed_images` and `volumes`.
4. The app's release job must run **inside its builder image** (`image:`,
   `tags: [<app>-app]`) rather than calling `docker` itself — the Docker
   executor gives jobs no Docker access.

## Updating

| What | How |
|---|---|
| The runner | `docker compose pull runner && docker compose up -d` |
| mnemostxt's builder (`docker/builder/Dockerfile` changed) | bump `BUILDER_TAG` in `scripts/build-release.sh`, `image:` of `release-files` in `.gitlab-ci.yml`, the tag in `docker/runner/docker-compose.yml`; push; `docker compose build mnemostxt-builder` |
| Free disk | `docker volume rm mnemostxt-build-cache` (refilled on the next release) |

## Troubleshooting

| Symptom | Fix |
|---|---|
| Job stuck "pending" | runner offline (`docker compose logs runner`) or the job's tag differs from the runner's |
| `pull access denied for mnemostxt-builder` | image not built (step 2) or `pull_policy = ["if-not-present"]` missing |
| `not allowed image` / `pull policy not allowed` | `allowed_images` / `allowed_pull_policies` of that runner |
| `mnemostxt.jks missing` | the `/keys` volume path in config.toml (must be the absolute host path) |
| `cargo-xwin` download errors | the job needs internet; retry |

## Building by hand (no runner)

```sh
sh scripts/build-release.sh            # → dist/release/
```
