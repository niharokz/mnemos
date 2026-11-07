#!/usr/bin/env python3
"""Prepare app/gen/android for a release build (run inside the builder image).

Tauri generates the Android project (`tauri android init`); it is not kept in
git. This script creates it when missing and applies mnemostxt's changes:

  1. storage permissions (All files access) in AndroidManifest.xml
  2. our MainActivity.kt (asks for access, keeps content clear of the bars)
  2b. the Gradle task that calls back into the Tauri CLI: point it at the real
      CLI file (started as `node <file>`, Tauri records just "tauri")
  3. the launcher icons from app/icons/android
  4. release signing from keystore.properties (written from the key folder)

Every step is idempotent: running it twice changes nothing more.
Usage: python3 scripts/android-prepare.py [--keys DIR]
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APP = ROOT / "app"
GEN = APP / "gen" / "android"
TAURI = ROOT / "frontend" / "node_modules" / ".bin" / "tauri"
MARK = "mnemostxt:"


def die(msg):
    print(f"android-prepare: {msg}", file=sys.stderr)
    sys.exit(1)


def init():
    gradle = GEN / "app" / "build.gradle.kts"
    if gradle.exists() and MARK in gradle.read_text() and SIGNING_MARK not in gradle.read_text():
        print("android-prepare: patched by an older version — regenerating app/gen/android")
        shutil.rmtree(GEN)
    if gradle.exists():
        return
    if not TAURI.exists():
        die("run `npm ci` in frontend/ first (it installs the Tauri CLI)")
    print("android-prepare: generating app/gen/android (tauri android init)")
    subprocess.run([str(TAURI), "android", "init", "--ci"], cwd=APP, check=True)


def manifest():
    p = GEN / "app" / "src" / "main" / "AndroidManifest.xml"
    s = p.read_text()
    if MARK in s:
        return
    if 'xmlns:tools=' not in s:
        s = s.replace("<manifest ", '<manifest xmlns:tools="http://schemas.android.com/tools" ', 1)
    perms = (
        f"    <!-- {MARK} the notes folder lives in shared storage -->\n"
        '    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />\n'
        '    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="29" />\n'
        '    <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" tools:ignore="ScopedStorage" />\n'
    )
    s, n = re.subn(r"(\s*<application\b)", "\n" + perms + r"\1", s, count=1)
    if not n:
        die(f"no <application> in {p}")
    s = re.sub(r"<application\b", '<application android:requestLegacyExternalStorage="true"', s, count=1)
    # the activity resizes with the keyboard
    if "windowSoftInputMode" not in s:
        s = re.sub(r'(<activity\b[^>]*android:name="\.MainActivity")', r'\1 android:windowSoftInputMode="adjustResize"', s, count=1)
    p.write_text(s)
    print("android-prepare: manifest patched")


def activity():
    found = list((GEN / "app" / "src" / "main").rglob("MainActivity.kt"))
    if not found:
        die("MainActivity.kt not found in the generated project")
    shutil.copyfile(APP / "android" / "MainActivity.kt", found[0])
    print(f"android-prepare: {found[0].relative_to(ROOT)} replaced")


# Gradle runs this from app/ (rootDirRel); the CLI lives in frontend/node_modules.
CLI_FROM_APP = "../frontend/node_modules/@tauri-apps/cli/tauri.js"


def build_task():
    found = list((GEN / "buildSrc").rglob("BuildTask.kt"))
    if not found:
        die("BuildTask.kt not found in app/gen/android/buildSrc")
    p = found[0]
    s = p.read_text()
    if CLI_FROM_APP in s:
        return
    s, n1 = re.subn(r'val executable = """[^"]*""";?', 'val executable = """node""";', s, count=1)
    s, n2 = re.subn(r'val args = listOf\([^)]*\);?',
                    f'val args = listOf("{CLI_FROM_APP}", "android", "android-studio-script");', s, count=1)
    if not (n1 and n2):
        die(f"unexpected BuildTask.kt layout in {p}")
    p.write_text(s)
    print("android-prepare: Gradle build task points at the Tauri CLI")


def icons():
    res = GEN / "app" / "src" / "main" / "res"
    src = APP / "icons" / "android"
    for d in src.iterdir():
        if d.is_dir():
            dest = res / d.name
            dest.mkdir(parents=True, exist_ok=True)
            for f in d.iterdir():
                shutil.copyfile(f, dest / f.name)
    print("android-prepare: launcher icons copied")


# Kotlin stdlib only: inside `android { }` the name `java` is Gradle's Java
# extension, so java.util.Properties does not resolve there.
SIGNING_MARK = "mnemostxt-signing-v2"
SIGNING = """
    // mnemostxt-signing-v2: release signing from keystore.properties (never committed)
    signingConfigs {
        create("release") {
            val ks = rootProject.file("keystore.properties")
            if (ks.exists()) {
                val p = ks.readLines()
                    .filter { it.contains('=') && !it.trimStart().startsWith("#") }
                    .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
                storeFile = file(p.getValue("storeFile"))
                storePassword = p.getValue("storePassword")
                keyAlias = p.getValue("keyAlias")
                keyPassword = p.getValue("keyPassword")
            }
        }
    }
"""


def signing():
    p = GEN / "app" / "build.gradle.kts"
    s = p.read_text()
    if SIGNING_MARK in s:
        return
    s, n = re.subn(r"(\n\s*buildTypes\s*\{)", SIGNING.rstrip("\n") + r"\1", s, count=1)
    if not n:
        die(f"no buildTypes block in {p}")
    s, n = re.subn(r'(getByName\("release"\)\s*\{)', r'\1\n            signingConfig = signingConfigs.getByName("release")', s, count=1)
    if not n:
        die(f'no getByName("release") in {p}')
    p.write_text(s)
    print("android-prepare: release signing added")


def keystore(keys):
    """keystore.env holds MNEMOSTXT_STORE_PASSWORD, MNEMOSTXT_KEY_PASSWORD, MNEMOSTXT_KEY_ALIAS."""
    if not keys:
        return
    keys = Path(keys)
    env = {}
    for line in (keys / "keystore.env").read_text().splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip().strip("'\"")
    jks = keys / "mnemostxt.jks"
    if not jks.exists():
        die(f"{jks} missing — run scripts/make-keystore.sh first")
    props = (
        f"storeFile={jks}\n"
        f"storePassword={env['MNEMOSTXT_STORE_PASSWORD']}\n"
        f"keyAlias={env.get('MNEMOSTXT_KEY_ALIAS', 'mnemostxt')}\n"
        f"keyPassword={env.get('MNEMOSTXT_KEY_PASSWORD', env['MNEMOSTXT_STORE_PASSWORD'])}\n"
    )
    out = GEN / "keystore.properties"
    out.write_text(props)
    os.chmod(out, 0o600)
    print("android-prepare: keystore.properties written")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--keys", help="folder with mnemostxt.jks and keystore.env")
    a = ap.parse_args()
    init()
    manifest()
    activity()
    build_task()
    icons()
    signing()
    keystore(a.keys)
