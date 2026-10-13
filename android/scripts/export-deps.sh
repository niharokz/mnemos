#!/usr/bin/env bash
# Packs the library jars/aars Gradle downloaded (from the Docker Gradle cache volume) into
# dist/deps.tgz, so the app can be compiled on another machine without internet.
# Only libraries and the Kotlin/Compose compiler; no secrets, no source, no build outputs.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p dist
vol=mnemos-gradle-cache
docker volume inspect "$vol" >/dev/null 2>&1 || vol=samanote-gradle-cache
docker run --rm -v "$vol":/cache:ro -v "$PWD/dist":/out alpine sh -c '
  cd /cache/caches/modules-2/files-2.1 &&
  # Gradle names group folders by full group id (androidx.compose.ui, org.jetbrains.kotlinx, ...).
  ls -d androidx.* org.jetbrains* org.commonmark* org.nibor.autolink org.jspecify com.google.guava 2>/dev/null > /tmp/groups;
  find $(cat /tmp/groups) \( -name "*.jar" -o -name "*.aar" \) ! -name "*-sources.jar" ! -name "*-javadoc.jar" > /tmp/list;
  tar czf /out/deps.tgz -T /tmp/list && chown '"$(id -u):$(id -g)"' /out/deps.tgz'
ls -lh dist/deps.tgz
