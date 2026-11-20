# DroidHand

> **Hands-on-device control of a personal Android phone for any AI agent — without leaving ADB on.**
> Status: **early / pre-MVP** · License: **Apache-2.0** · Codename `droidhand` (provisional)

DroidHand lets an AI coding agent (Claude Code, Codex, Cursor, …) perceive and drive a **personal** Android phone over the [Model Context Protocol](https://modelcontextprotocol.io), while keeping USB/Wireless **debugging off by default**. It runs primarily through the Android **AccessibilityService** and only **flashes ADB on-demand** (Shizuku-style) for the few operations that truly need shell power, then turns it back off.

## Why

The incumbents ([mobile-mcp](https://github.com/mobile-next/mobile-mcp), [DroidRun](https://github.com/droidrun/mobilerun)) are great but need a **persistent ADB connection from a computer** — debugging always on. DroidHand keeps debugging **off** except for brief, logged windows, which matters when the same phone carries an MDM work profile.

## Architecture (one line each)

- **Portal app** (Kotlin) — AccessibilityService + `WRITE_SECURE_SETTINGS` + ADB-on-demand, dials **out** to the NUC.
- **Relay** (Node/WSS over Tailscale) — outbound-only from phone, token + TLS, append-only audit.
- **MCP server** (TypeScript) — exposes phone tools to Claude Code / Codex.

```
Claude Code / Codex ──MCP──► MCP server ──relay──► Portal app ──a11y / adb-on-demand──► phone
```

See the full design in **[docs/PRD.md](docs/PRD.md)**.

## Repository layout

| Path | What |
|---|---|
| `docs/PRD.md` | Complete product requirements & architecture |
| `app/` | Android Portal app (Kotlin) — *scaffold* |
| `mcp-server/` | MCP server + relay (TypeScript) — *scaffold* |
| `relay/` | Relay transport notes/shared — *scaffold* |
| `.github/` | CI, issue/PR templates |
| `SECURITY.md` | Vulnerability disclosure |
| `CONTRIBUTING.md` | How to contribute |

## Status / roadmap

- **P0 — PRD + scaffold** ✅
- **P1 — MVP** *(in progress)*
  - ✅ Relay daemon + per-session MCP adapter (TypeScript), 37 tools, token auth, audit, panic — built & tested, deployed as a user service, registered in Claude Code + Codex.
  - ✅ Feature-suggestion log (suggest/list/export), OFF by default, device-gated.
  - ✅ Android Portal app (a11y perception + interaction, relay client, floating panic button) — built and tested on a Galaxy S24 Ultra (Android 17).
  - ✅ Background display with mini window: native method (debugging OFF) and own-window method (trusted display via a shell helper; hide/show, resize, opacity, touch passthrough, drag-to-X close). See PRD §7.10.
- **P2 — Privilege:** ADB-on-demand, settings writer, confirmation gates, encrypted on-device audit
- **P3 — Breadth & hardening:** files/media/comms, PII mode, CI-signed releases, F-Droid

## ⚠️ Safety & acceptable use

DroidHand grants **full control of a phone**. Only run it against a device **you own**. It is built for the **personal profile** and does **not** touch, read, or bypass an MDM/work-profile container. ADB-on-demand is a *minimize-exposure* feature, **not** a way to hide activity from a device administrator — complying with any MDM policy on your device is **your responsibility**. See [SECURITY.md](SECURITY.md) and PRD §2.1 / §9.

## License

[Apache-2.0](LICENSE). Not affiliated with Google, Microsoft, or the referenced projects.
