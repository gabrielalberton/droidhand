#!/usr/bin/env bash
# Build a DroidHand release locally: signed APK, MCP server tarball, checksums,
# and (with PUBLISH=1) a GitHub Release for the tag v<version>.
#
#   scripts/release.sh            # build into ./release/
#   PUBLISH=1 scripts/release.sh  # also tag + create the GitHub Release (needs gh)
#
# Signing: android/keystore.properties or DROIDHAND_KEYSTORE_PROPERTIES must point
# at the release key (see android/app/build.gradle.kts). Never commit either.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION="$(sed -nE 's/.*versionName = "([^"]+)".*/\1/p' android/app/build.gradle.kts)"
NPM_VERSION="$(node -p "require('./mcp-server/package.json').version")"
[[ "$VERSION" == "$NPM_VERSION" ]] || { echo "version mismatch: app $VERSION vs mcp-server $NPM_VERSION" >&2; exit 1; }
TAG="v$VERSION"
OUT="$ROOT/release"
rm -rf "$OUT" && mkdir -p "$OUT"
echo "==> DroidHand $TAG"

# Heavy builds run under a memory cap when systemd-run is available.
CAP=()
command -v systemd-run >/dev/null && CAP=(systemd-run --user --scope -q -p MemoryMax=3G -p MemorySwapMax=1G --nice=10 -E "JAVA_HOME=${JAVA_HOME:-}" --)

echo "==> MCP server: typecheck, test, build, pack"
( cd mcp-server && npm ci --no-fund --no-audit && npm run typecheck && npm test && npm run build && npm run docs:tools -- --check )
( cd mcp-server && npm pack --silent --pack-destination "$OUT" >/dev/null )
mv "$OUT"/droidhand-mcp-server-*.tgz "$OUT/droidhand-mcp-server-$VERSION.tgz"

echo "==> Android: unit tests + signed release"
( cd android && "${CAP[@]}" ./gradlew -q :app:testDebugUnitTest :app:assembleRelease )
APK="android/app/build/outputs/apk/release/droidhand-release.apk"
[[ -f "$APK" ]] || { echo "no signed APK (missing keystore.properties?)" >&2; exit 1; }
cp "$APK" "$OUT/droidhand-$VERSION.apk"

APKSIGNER="$(ls -d "${ANDROID_HOME:-$HOME/android-sdk}"/build-tools/*/apksigner 2>/dev/null | tail -1 || true)"
if [[ -n "$APKSIGNER" ]]; then
  "$APKSIGNER" verify --print-certs "$OUT/droidhand-$VERSION.apk" | grep -i 'SHA-256' | tee "$OUT/signing-certificate.txt"
fi

( cd "$OUT" && sha256sum droidhand-* > SHA256SUMS )
echo "==> Artifacts in $OUT"; ls -l "$OUT"

if [[ "${PUBLISH:-0}" == "1" ]]; then
  [[ -z "$(git status --porcelain)" ]] || { echo "working tree not clean" >&2; exit 1; }
  NOTES="$(awk -v v="$VERSION" '$0 ~ "^## \\[" v "\\]" {f=1; next} /^## \[/ {f=0} f' CHANGELOG.md)"
  git tag -a "$TAG" -m "DroidHand $TAG"
  git push origin "$TAG"
  gh release create "$TAG" "$OUT"/* --title "DroidHand $VERSION" --notes "$NOTES"
fi
