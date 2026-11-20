# DroidHand Runtime (EXPERIMENTAL)

A minimal Linux userland on the phone — **just enough to run coding-agent CLIs**
(Claude Code, Codex) and, optionally, a T3 Code server — inside its own APK.
No Termux app, no root, no PC. Lives on the `exp/linux-runtime` branch.

**Isolation.** Separate Gradle module and separate APK
(`work.alberton.droidhand.runtime`, own UID). It does not depend on `:portal`
and `:portal` does not depend on it. To remove it: delete `app/runtime/` and
the `include(":runtime")` line in `app/settings.gradle.kts`. To skip it in a
build: `./gradlew -Pdroidhand.runtime=false …`.

## What is inside

| Piece | Source (community maintained) | Size |
|---|---|---|
| proot + loader, libtalloc, libandroid-shmem | Termux package repo (`scripts/fetch-proot.sh`, pinned SHA-256, rewritten with patchelf) | ~360 KB |
| Terminal | Termux `terminal-view` / `terminal-emulator` (Apache-2.0, JitPack) | — |
| Linux | Alpine minirootfs 3.24 (downloaded on first run, SHA-256 checked) | 4 MB |

Nothing third-party is bundled beyond that. Tools come from **recipes**
(buttons that type plain shell into the terminal, so you see everything):

| Recipe | What it does | Disk (measured) |
|---|---|---|
| Base | `apk add bash ca-certificates curl git libgcc libstdc++ ripgrep` | 32 MB total |
| Claude Code | official `claude.ai/install.sh` (Alpine/musl is supported upstream); you sign in with your own account | 349 MB |
| Codex | official `codex-aarch64-unknown-linux-musl` release; `sandbox_mode = "danger-full-access"` (no bwrap/landlock under proot — the app's Android sandbox is the boundary) | 295 MB |
| T3 (optional) | `assets/recipes/t3.sh`: T3 ships glibc-only builds, so the recipe adds a private glibc layer (Debian trixie libc6/libstdc++6/libgcc-s1/libatomic1, SHA-256 from the Packages index) under `/opt/glibc` and patches only the T3 binary to use it | 468 MB + 9 MB |
| T3 serve | `t3 serve --lan ~/work` (pair with `t3 pair`) | — |

## Build

```sh
app/runtime/scripts/fetch-proot.sh          # once (needs curl, dpkg-deb)
cd app && ./gradlew :runtime:assembleDebug
```

## Measured on a Galaxy S24 Ultra (Android 17, 2026-11-20)

- proot + Alpine works with `targetSdk 35`: proot runs from `nativeLibraryDir`
  (W^X), and guest binaries load through proot's loader.
- Start-up inside proot: `claude --version` 0.04 s, `codex --version` 0.06 s,
  `git --version` 0.01 s, `t3 --version` 1.3 s. Alpine base install: seconds.
- `t3 serve --lan` answered HTTP 200 from another machine on the LAN, and pairing worked.
- Sessions keep running with the screen off (foreground service + partial wake lock).

## Known limits / TODO

- Android 16+ shows a "not 16 KB aligned" warning for **debuggable** builds
  (proot/talloc/shmem from Termux and `libtermux.so`). Harmless on 4 KB-page
  devices; fix = build proot with 16 KB alignment.
- Android 12+ phantom-process killer can kill child processes; on Android 14+
  turn on Developer options → "Disable child process restrictions".
- Plain HTTP for `t3 serve --lan`: use it over your tailnet.
- Not on Google Play (exec'ing downloaded tools); sideload only.

## Licenses

proot GPL-2.0 (github.com/termux/proot), talloc LGPL-3.0+, libandroid-shmem
BSD-3, Termux terminal libraries Apache-2.0, Alpine packages per package.
Claude Code is proprietary and is **never redistributed** — the recipe runs
Anthropic's installer on your device. Codex is Apache-2.0; T3 Code per its repo.
