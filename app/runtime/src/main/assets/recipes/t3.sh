#!/bin/sh
# DroidHand Runtime recipe: T3 Code server (optional).
# T3 ships glibc-only Linux builds; Alpine is musl. We add a private glibc layer
# (Debian's libc6/libstdc++6/libgcc-s1/libatomic1) under /opt/glibc and point
# only the T3 binary at it (ELF interpreter + RUNPATH), so nothing else in Alpine
# changes and child processes (git, claude, codex) stay pure musl.
set -eu
DEBIAN=https://deb.debian.org/debian
SUITE=trixie
GLIBC_PKGS="libc6 libstdc++6 libgcc-s1 libatomic1"

apk add -q curl xz patchelf
apk add -q --virtual .t3-build binutils
work=$(mktemp -d); trap 'rm -rf "$work"; apk del -q .t3-build >/dev/null 2>&1 || true' EXIT
cd "$work"

echo "→ camada glibc (Debian $SUITE)…"
curl -fsSL "$DEBIAN/dists/$SUITE/main/binary-arm64/Packages.xz" | xz -d > Packages
for p in $GLIBC_PKGS; do
  stanza=$(awk -v RS= -v want="Package: $p" 'index($0, want "\n") == 1 { print; exit }' Packages)
  file=$(echo "$stanza" | sed -n 's/^Filename: //p'); sum=$(echo "$stanza" | sed -n 's/^SHA256: //p')
  curl -fsSL "$DEBIAN/$file" -o p.deb
  echo "$sum  p.deb" | sha256sum -c -s
  ar x p.deb; mkdir -p x; tar -xf data.tar.* -C x; rm -f data.tar.* control.tar.* debian-binary p.deb
done
rm -rf /opt/glibc; mkdir -p /opt/glibc/lib
cp -L x/usr/lib/aarch64-linux-gnu/*.so* /opt/glibc/lib/ 2>/dev/null || true
cp -L x/usr/lib/ld-linux-aarch64.so.1 /opt/glibc/lib/

echo "→ T3 Code (release oficial)…"
ver=$(curl -fsSL https://registry.npmjs.org/t3/latest | sed -n 's/.*"version":"\([^"]*\)".*/\1/p')
base=https://github.com/pingdotgg/t3code/releases/download/v$ver
archive=t3-$ver-linux-arm64.tar.gz
curl -fsSL "$base/SHA256SUMS" | grep " $archive\$" > sums
curl -fsSL "$base/$archive" -o "$archive"
sha256sum -c -s sums
rm -rf "/opt/t3/$ver"; mkdir -p "/opt/t3/$ver"
tar -xzf "$archive" -C "/opt/t3/$ver" --strip-components=1
patchelf --set-interpreter /opt/glibc/lib/ld-linux-aarch64.so.1 --set-rpath /opt/glibc/lib "/opt/t3/$ver/t3"
ln -sfn "/opt/t3/$ver" /opt/t3/current
ln -sf /opt/t3/current/t3 /usr/local/bin/t3
# Keep only the current version.
for d in /opt/t3/*/; do case "$d" in */"$ver"/|*/current/) ;; *) rm -rf "$d";; esac; done
t3 --version
echo "✔ T3 pronto. Numa aba nova: t3 serve --lan ~/work   (pareamento: t3 pair)"
