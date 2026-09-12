#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Linux/POSIX bootstrap for the Gradle Wrapper used by this project.
#
# The project archive is generated in an environment where the binary wrapper JAR
# cannot be fetched directly. On first run this script downloads the official
# Gradle 9.5.0 wrapper JAR from Gradle's GitHub repository, verifies it against
# Gradle's published SHA-256, then hands control to the normal Wrapper runtime.

set -eu

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WRAPPER_DIR="$APP_HOME/gradle/wrapper"
WRAPPER_JAR="$WRAPPER_DIR/gradle-wrapper.jar"
WRAPPER_URL="https://raw.githubusercontent.com/gradle/gradle/v9.5.0/gradle/wrapper/gradle-wrapper.jar"
WRAPPER_SHA256="497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7"

fail() {
    printf '%s\n' "ERROR: $*" >&2
    exit 1
}

sha256_file() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        fail "Need sha256sum or shasum to verify the Gradle wrapper JAR."
    fi
}

download() {
    url=$1
    out=$2
    if command -v curl >/dev/null 2>&1; then
        curl --fail --location --retry 3 --connect-timeout 20 --output "$out" "$url"
    elif command -v wget >/dev/null 2>&1; then
        wget --tries=3 --timeout=20 --output-document="$out" "$url"
    else
        fail "Need curl or wget for the first Gradle wrapper bootstrap."
    fi
}

mkdir -p "$WRAPPER_DIR"

need_wrapper=true
if [ -f "$WRAPPER_JAR" ]; then
    current_sha=$(sha256_file "$WRAPPER_JAR")
    if [ "$current_sha" = "$WRAPPER_SHA256" ]; then
        need_wrapper=false
    else
        printf '%s\n' "Existing Gradle wrapper JAR has an unexpected checksum; replacing it." >&2
    fi
fi

if [ "$need_wrapper" = true ]; then
    tmp="$WRAPPER_JAR.tmp.$$"
    trap 'rm -f "$tmp"' EXIT HUP INT TERM
    printf '%s\n' "Bootstrapping Gradle 9.5.0 wrapper JAR..." >&2
    download "$WRAPPER_URL" "$tmp"
    actual_sha=$(sha256_file "$tmp")
    if [ "$actual_sha" != "$WRAPPER_SHA256" ]; then
        fail "Gradle wrapper JAR checksum mismatch. Expected $WRAPPER_SHA256, got $actual_sha"
    fi
    mv "$tmp" "$WRAPPER_JAR"
    trap - EXIT HUP INT TERM
fi

if [ -n "${JAVA_HOME:-}" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
    [ -x "$JAVACMD" ] || fail "JAVA_HOME does not point to a usable JDK: $JAVA_HOME"
else
    JAVACMD=$(command -v java || true)
    [ -n "$JAVACMD" ] || fail "Java not found. Install JDK 17+ or set JAVA_HOME."
fi

exec "$JAVACMD" -jar "$WRAPPER_JAR" "$@"
