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

# Tauri's installer script includes Win\RestartManager.nsh, which first ships
# with NSIS 3.09; Debian 12 has 3.08. This is NSIS's own file (zlib licence),
# taken unchanged from NSIS 3.09. Last layer, so the heavy ones stay cached.
COPY RestartManager.nsh /usr/share/nsis/Include/Win/RestartManager.nsh

WORKDIR /src
