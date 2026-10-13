# Build image: JDK 21 + Android SDK 36 command-line tools. Gradle itself comes from the wrapper.
FROM eclipse-temurin:21-jdk-jammy

ARG CMDLINE_TOOLS=11076708
ENV ANDROID_HOME=/opt/android-sdk \
    GRADLE_USER_HOME=/cache/gradle
ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

# build-tools 35 is AGP 8.13's default. The SDK is made writable because build.sh runs as your uid.
RUN apt-get update && apt-get install -y --no-install-recommends unzip curl git && rm -rf /var/lib/apt/lists/* \
 && mkdir -p $ANDROID_HOME/cmdline-tools \
 && curl -fsSL -o /tmp/tools.zip https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip \
 && unzip -q /tmp/tools.zip -d $ANDROID_HOME/cmdline-tools && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
 && rm /tmp/tools.zip \
 && yes | sdkmanager --licenses >/dev/null \
 && sdkmanager "platforms;android-36" "build-tools;35.0.0" "build-tools;36.0.0" "platform-tools" \
 && chmod -R a+rwX $ANDROID_HOME

# The Gradle cache volume is written by the host user (build.sh runs as your uid).
RUN mkdir -p /cache/gradle && chmod 777 /cache/gradle

WORKDIR /src
