#!/usr/bin/env bash
# Fetches proot (+ its two libraries) from the Termux package repository, checks the
# pinned SHA-256 values, and rewrites them so Android can run them from the APK's
# native library directory (the only place an app targeting API 29+ may exec from).
#
#   proot            -> jniLibs/arm64-v8a/libproot.so
#   proot loader     -> jniLibs/arm64-v8a/libproot-loader.so
#   libtalloc.so.2   -> jniLibs/arm64-v8a/libtalloc.so
#   libandroid-shmem -> jniLibs/arm64-v8a/libandroid-shmem.so
#
# Licenses: proot GPL-2.0, talloc LGPL-3.0+, libandroid-shmem BSD-3 (sources:
# github.com/termux/proot, talloc.samba.org, github.com/termux/libandroid-shmem).
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$HERE/src/main/jniLibs/arm64-v8a"
REPO=https://packages-cf.termux.dev/apt/termux-main
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

fetch() { # path sha256
  curl -fsSL "$REPO/$1" -o "$WORK/pkg.deb"
  echo "$2  $WORK/pkg.deb" | sha256sum -c --quiet -
  dpkg-deb -x "$WORK/pkg.deb" "$WORK/x"
}
fetch pool/main/p/proot/proot_5.1.107.102_aarch64.deb 10a394b585c2fc2b2c8502544f4e5f1be581dc8bfff79e8ba62f16207423e030
fetch pool/main/libt/libtalloc/libtalloc_2.5.0_aarch64.deb 556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7
fetch pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb 0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6

PATCHELF="$(command -v patchelf || true)"
if [[ -z "$PATCHELF" ]]; then
  curl -fsSL https://github.com/NixOS/patchelf/releases/download/0.18.0/patchelf-0.18.0-x86_64.tar.gz | tar xz -C "$WORK" ./bin/patchelf
  PATCHELF="$WORK/bin/patchelf"
fi

U="$WORK/x/data/data/com.termux/files/usr"
mkdir -p "$OUT"
cp "$U/bin/proot" "$OUT/libproot.so"
cp "$U/libexec/proot/loader" "$OUT/libproot-loader.so"
cp "$U/lib/libtalloc.so.2.5.0" "$OUT/libtalloc.so"
cp "$U/lib/libandroid-shmem.so" "$OUT/libandroid-shmem.so"
"$PATCHELF" --replace-needed libtalloc.so.2 libtalloc.so --remove-rpath "$OUT/libproot.so"
"$PATCHELF" --set-soname libtalloc.so --remove-rpath "$OUT/libtalloc.so"
"$PATCHELF" --remove-rpath "$OUT/libandroid-shmem.so"
chmod 0755 "$OUT"/*.so
ls -la "$OUT"
