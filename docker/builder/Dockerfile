# mnemostxt release builder: everything needed to build the three release
# files on one Linux machine (your own GitLab runner):
#   Linux    .deb          (Tauri, webkit2gtk)
#   Windows  setup.exe     (Tauri NSIS, cross-compiled with cargo-xwin)
#   Android  .apk          (Tauri, Android SDK + NDK)
# Built once per BUILDER_TAG (scripts/build-release.sh) and reused.
FROM mirror.gcr.io/library/rust:1-bookworm

ARG NODE_MAJOR=22
ARG ANDROID_PLATFORM=36
ARG ANDROID_BUILD_TOOLS=36.0.0
ARG ANDROID_NDK=27.3.13750724
ARG CMDLINE_TOOLS=13114758

ENV DEBIAN_FRONTEND=noninteractive \
    ANDROID_HOME=/opt/android-sdk \
    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
    XWIN_ACCEPT_LICENSE=1 \
    CARGO_TERM_COLOR=never

# Linux (webkit2gtk), Windows cross tools (nsis, lld, clang), Java for Gradle
RUN apt-get update && apt-get install -y --no-install-recommends \
      libwebkit2gtk-4.1-dev libayatana-appindicator3-dev librsvg2-dev libxdo-dev libssl-dev \
      build-essential curl file unzip xz-utils python3 ca-certificates git \
      nsis lld llvm clang openjdk-17-jdk-headless \
 && curl -fsSL https://deb.nodesource.com/setup_${NODE_MAJOR}.x | bash - \
 && apt-get install -y --no-install-recommends nodejs \
 && rm -rf /var/lib/apt/lists/*

# Android SDK, build tools and NDK
RUN mkdir -p $ANDROID_HOME/cmdline-tools \
 && curl -fsSL -o /tmp/tools.zip https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip \
 && unzip -q /tmp/tools.zip -d $ANDROID_HOME/cmdline-tools && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
 && rm /tmp/tools.zip \
 && yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null \
 && $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --install \
      "platform-tools" "platforms;android-${ANDROID_PLATFORM}" "build-tools;${ANDROID_BUILD_TOOLS}" "ndk;${ANDROID_NDK}" >/dev/null
ENV NDK_HOME=$ANDROID_HOME/ndk/$ANDROID_NDK \
    PATH=$PATH:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools

# Rust targets and the Windows cross-linker wrapper
RUN rustup target add x86_64-pc-windows-msvc aarch64-linux-android \
 && cargo install --locked cargo-xwin \
 && rm -rf $CARGO_HOME/registry $CARGO_HOME/git

# Tauri's Windows-installer tools, baked in so the release build never has to
# download them. On Linux the bundler (tauri-cli 2.12, pinned in
# frontend/package-lock.json) checks <cache>/tauri/NSIS for NSIS.exe and the
# nsis_tauri_utils plugin by SHA-1 and re-downloads both from GitHub on every
# build when they are missing. scripts/build-in-container.sh points
# XDG_CACHE_HOME here. Hashes = the constants in tauri-bundler 2.10.x.
ARG NSIS_ZIP=https://github.com/tauri-apps/binary-releases/releases/download/nsis-3.13/nsis-3.13.zip
ARG NSIS_ZIP_SHA1=db14f8af2ea346b786c6ae19343f61aaf5e09b8e
ARG NSIS_EXE_SHA1=6d45d4e8ccf18fe1fdac9164058f356102a2ecf2
ARG NSIS_UTILS=https://github.com/tauri-apps/nsis-tauri-utils/releases/download/nsis_tauri_utils-v0.5.3/nsis_tauri_utils.dll
ARG NSIS_UTILS_SHA1=75197fee3c6a814fe035788d1c34ead39349b860
RUN set -e; T=/opt/tauri-tools/tauri/NSIS; mkdir -p $T/Plugins/x86-unicode/additional \
 && curl -fsSL --retry 5 -o /tmp/nsis.zip "$NSIS_ZIP" \
 && echo "$NSIS_ZIP_SHA1  /tmp/nsis.zip" | sha1sum -c - \
 && unzip -q -j /tmp/nsis.zip nsis-3.13/NSIS.exe -d $T \
 && echo "$NSIS_EXE_SHA1  $T/NSIS.exe" | sha1sum -c - \
 && curl -fsSL --retry 5 -o $T/Plugins/x86-unicode/additional/nsis_tauri_utils.dll "$NSIS_UTILS" \
 && echo "$NSIS_UTILS_SHA1  $T/Plugins/x86-unicode/additional/nsis_tauri_utils.dll" | sha1sum -c - \
 && rm /tmp/nsis.zip && chmod -R a+rwX /opt/tauri-tools

# Tauri's installer script includes Win\RestartManager.nsh, which first ships
# with NSIS 3.09; Debian 12 has 3.08. This is NSIS's own file (zlib licence),
# taken unchanged from NSIS 3.09. Last layer, so the heavy ones stay cached.
COPY RestartManager.nsh /usr/share/nsis/Include/Win/RestartManager.nsh

# What the Android build would otherwise download in every job: the SDK
# platform Tauri's Gradle project compiles against, and the Rust targets
# `tauri android init` installs.
RUN sdkmanager --install "platforms;android-37.0" >/dev/null \
 && rustup target add armv7-linux-androideabi i686-linux-android x86_64-linux-android

WORKDIR /src
