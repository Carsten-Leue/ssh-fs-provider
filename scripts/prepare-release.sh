#!/usr/bin/env bash
# prepare-release.sh <version>
#
# Called by semantic-release's @semantic-release/exec prepareCmd.
# 1. Derives versionCode from the semver string.
# 2. Writes version.properties so Gradle picks up the values.
# 3. Builds the release APK.
# 4. Renames the APK to include the version for a human-readable release asset.

set -euo pipefail

VERSION="${1:?Usage: prepare-release.sh <semver>}"

# Strip any pre-release / build-metadata suffix from the patch component
# so the arithmetic is always on three plain integers.
MAJOR=$(echo "$VERSION" | cut -d. -f1)
MINOR=$(echo "$VERSION" | cut -d. -f2)
PATCH=$(echo "$VERSION" | cut -d. -f3 | grep -oE '^[0-9]+')

# versionCode formula: major*10000 + minor*100 + patch
# Supports up to v99.99.99 without overflow on a 32-bit signed integer.
VERSION_CODE=$(( MAJOR * 10000 + MINOR * 100 + PATCH ))

printf 'VERSION_NAME=%s\nVERSION_CODE=%d\n' "$VERSION" "$VERSION_CODE" \
    > version.properties

echo "::notice::versionName=${VERSION}  versionCode=${VERSION_CODE}"

# Build the release APK (Gradle reads version.properties).
./gradlew :app:assembleRelease

# Locate the output: signed builds produce app-release.apk,
# unsigned builds produce app-release-unsigned.apk.
APK_SRC="app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$APK_SRC" ]; then
    APK_SRC="app/build/outputs/apk/release/app-release-unsigned.apk"
fi

APK_DST="app/build/outputs/apk/release/ssh-fs-provider-${VERSION}.apk"
cp "$APK_SRC" "$APK_DST"

echo "::notice::APK ready at ${APK_DST}"
