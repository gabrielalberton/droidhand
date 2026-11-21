# Third-Party Notices

DroidHand is an original project. This file lists the third-party dependencies that ship in releases and their licenses. Full license texts are in each dependency's distribution.

### Android app (`droidhand-<version>.apk`)

| Component | Version | License |
|---|---|---|
| AndroidX Core, Lifecycle, Activity | 1.15.0 / 2.8.7 / 1.9.3 | Apache-2.0 |
| Jetpack Compose (UI, Material 3) via BOM | 2024.10.01 | Apache-2.0 |
| Kotlin standard library | 2.0.21 | Apache-2.0 |
| OkHttp (+ Okio) | 4.12.0 | Apache-2.0 |

### Relay + MCP server (`droidhand-mcp-server-<version>.tgz`, runtime dependencies)

| Component | Version | License |
|---|---|---|
| @modelcontextprotocol/sdk | ^1.32 | MIT |
| ws | ^8.18 | MIT |
| zod | ^3.23 | MIT |

Transitive npm dependencies are listed in `mcp-server/package-lock.json`.

## Design-study references (no code copied)

The following projects were studied for architecture and mechanism only. **No source is copied from them.**

- RikkaApps/Shizuku — non-root ADB-on-demand *mechanism* study only (GPL/Apache; not vendored).
- mobile-next/mobile-mcp (Apache-2.0) — MCP tool ergonomics.
- DroidRun/mobilerun (MIT) — on-device app concept.
- Magi-Labs/mobilemcp — outbound app → hub → MCP topology.
- danielealbano/android-remote-control-mcp — PII redaction idea.
