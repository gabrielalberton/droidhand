# DroidHand — Product Requirements Document (PRD)

> **Status:** Original design document (v0.2). It describes the full vision, including parts not built yet
> (ADB-on-demand from the phone, notification listener, on-device confirmation dialogs, resources). What ships is
> described in the [README](../README.md), [architecture](architecture.md) and [tools](tools.md). · **Owner:** project maintainer
> **Codename:** `droidhand` *(provisional — rename is a project-wide find/replace of the string `droidhand`)*
> **Tagline:** *Hands-on-device control of a personal Android phone for any AI agent — without leaving ADB on.*

---

## 0. Document control

| | |
|---|---|
| Document type | Product Requirements Document |
| Audience | Maintainers, contributors, security reviewers, downstream integrators |
| License of this project | Apache-2.0 (proposed) |
| Related prior art | DroidRun/mobilerun, mobile-next/mobile-mcp, Magi-Labs/mobilemcp, danielealbano/android-remote-control-mcp, Shizuku |
| Distribution | Source on GitHub; built APK via GitHub Releases + F-Droid (no Play Store — accessibility apps are rejected) |

### 0.1 Change log

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1 | 2026-11-20 | maintainer | Initial complete draft |
| 0.2 | 2026-11-20 | maintainer | §7.10 background display (two methods, measured limits on a Galaxy S24 Ultra / Android 17) |

### 0.2 Glossary

| Term | Meaning |
|---|---|
| **Agent** | An LLM-driven client (Claude Code, Codex, Cursor, etc.) that issues high-level intents. |
| **MCP** | Model Context Protocol — the tool/resource protocol the agent speaks. |
| **MCP server** | The DroidHand process that exposes phone capabilities to the agent as MCP tools. Runs on the NUC. |
| **Relay** | The transport that connects the MCP server to the on-device app. Phone is always the outbound client. |
| **Portal app** | The on-device Android app that performs the actions (accessibility + privileged settings + ADB-on-demand). |
| **A11y** | Android AccessibilityService — reads the on-screen node tree and dispatches gestures. |
| **ADB-on-demand** | Turning wireless debugging on only for the duration of a privileged action, then off again, à la Shizuku boot-start. |
| **WSS** | `WRITE_SECURE_SETTINGS` — the special permission that lets an app change protected system settings. |
| **Work profile** | The Intune/Android Enterprise managed container. Out of scope — DroidHand lives in the personal profile. |
| **Kill switch** | Any user action that instantly severs agent control and (optionally) disables privileges. |

---

## 1. Executive summary

DroidHand gives an AI coding agent a reliable, auditable, **full-control** channel to a *personal* Android phone, and does it **without keeping USB/Wireless debugging permanently enabled**.

It is three parts:

1. **Portal app** (Kotlin) running on the phone — performs actions through the AccessibilityService, holds `WRITE_SECURE_SETTINGS`, and can bring up a short-lived local ADB session on demand for the handful of operations that truly need shell power.
2. **Relay** — the phone dials *out* to the NUC over Tailscale (WebSocket). No inbound ports on the phone.
3. **MCP server** (TypeScript) on the NUC — translates MCP tool calls from Claude Code / Codex into relay messages, and streams perception (screen tree, screenshots, notifications) back.

The differentiator vs. the ~10k-star incumbents (DroidRun, mobile-mcp) is that those require a **persistent ADB connection from a computer**. DroidHand runs **primarily over the AccessibilityService** (works with debugging off) and only **flashes ADB on** for privileged operations, mirroring the Shizuku boot-start mechanism. This keeps the device's debugging state off for the vast majority of the time.

### 1.1 Why build rather than adopt

| Incumbent | Why not sufficient alone |
|---|---|
| mobile-next/mobile-mcp | Excellent MCP ergonomics, but control path is ADB-from-computer → requires debugging permanently on. |
| DroidRun/mobilerun | Portal app exists, but local control is ADB-centric; the no-ADB path is the paid Cloud. |
| Magi-Labs/mobilemcp | Closest architecture (outbound app → hub → MCP). Good base to study/vendor, but early, and no privileged/ADB-on-demand engine or audit/kill-switch posture we want. |
| danielealbano/android-remote-control-mcp | On-device MCP server with PII redaction; useful ideas, different topology (server on phone). |
| AndroMCP | 234 tools incl. root; root is a non-starter here (kills Knox, fails Intune). |

**Decision:** build DroidHand as an original project, studying Magi-Labs/mobilemcp and android-remote-control-mcp for topology and tool design, vendoring nothing without a full source review and a clean-room reimplementation of anything we ship.

---

## 2. Problem statement & context

The owner is a developer whose single personal phone also carries an employer-managed **Intune work profile** (Teams, Outlook, Edge). Two needs collide:

- **Development/automation** wants Developer Options and debugging available.
- **Intune compliance** ("Block USB debugging on device") reads the *device-wide* debugging state; some work apps misbehave when developer mode is on.

Today the owner toggles this by hand, repeatedly, and a sideloaded Shizuku+helper was silently re-enabling debugging on every boot — which is how this investigation started. There is no existing tool that gives an agent **full, safe, auditable** control of the phone while keeping debugging **off by default** and only **on-demand**.

### 2.1 Acceptable-use / compliance note (normative)

DroidHand is for a device **you own**. Toggling Developer Options or debugging on your own hardware is your right, **but compliance with any employer MDM policy on that device is the operator's responsibility.** DroidHand:

- Operates **only in the personal profile**; it does not read, modify, or bypass the work-profile container, its encryption, or its data.
- Treats ADB-on-demand as a **minimize-exposure** feature (debugging off except for brief, logged windows), not a means of hiding activity from a device owner/administrator.
- Ships an explicit **"MDM-managed device" warning** in the setup flow.

The project will not accept features whose primary purpose is defeating a device administrator's visibility on a device they administer.

---

## 3. Goals & non-goals

### 3.1 Goals (what success looks like)

- **G1** — An agent on the NUC can perceive (screen tree + screenshot + notifications) and act (tap/type/swipe/keys/app-launch) on the phone **with debugging off**.
- **G2** — Privileged operations (install/uninstall, `pm grant`, arbitrary `settings`, shell) work via **ADB-on-demand**: debug on → act → debug off, fully logged.
- **G3** — The full control plane is **outbound-only from the phone**, reachable only over Tailscale, authenticated, and **TLS-encrypted**.
- **G4** — Every action is **audited** (append-only, on NUC and phone), and the user has an always-available **kill switch** and **per-category confirmation gates**.
- **G5** — **First-class** Claude Code and Codex integration on the NUC, with ready-to-paste config.
- **G6** — Clean **open-source** release: Apache-2.0, CI-built APK, signed releases, docs, SECURITY policy, contribution guide.

### 3.2 Non-goals (explicitly out of scope, at least initially)

- **N1** — No root. No Magisk/LSPosed modules.
- **N2** — No control *inside* the work profile; no attempt to defeat Intune's container or its reporting.
- **N3** — No Play Store distribution (accessibility apps are rejected; GitHub/F-Droid instead).
- **N4** — No bundled LLM/model; the agent is the brain, DroidHand is the hands.
- **N5** — iOS support is a stretch goal (Phase 4), not committed.
- **N6** — No silent/background install without the on-screen confirmation that Android requires outside ADB.

---

## 4. Personas & use cases

### 4.1 Personas

- **P1 — The developer-operator (primary).** Runs Claude Code/Codex on the NUC. Wants to say "grab the OTP from my SMS and paste it into the web form," "clear these 40 notifications," "install this debug APK and grant it camera," "screenshot the app every 2s while I reproduce a bug." Trusts the agent but wants a log and a brake.
- **P2 — The contributor.** Wants a clean module boundary (app / relay / mcp-server) to add a tool or a device-OEM quirk fix.
- **P3 — The security reviewer.** Wants the threat model, the auth story, and the audit trail to be legible before trusting the APK.

### 4.2 Representative use cases

| # | As operator I want to… | Tools exercised | Tier |
|---|---|---|---|
| U1 | Read an OTP from SMS and type it into a field | `get_sms`, `screen_snapshot`, `type_text` | sensitive |
| U2 | Clear all notifications | `get_notifications`, `global_action(clear_all)` | standard |
| U3 | Install a debug build and grant runtime perms | `adb_session_begin`, `adb_install`, `pm_grant`, `adb_session_end` | privileged |
| U4 | Reproduce a UI bug with a screenshot every 2s | `screen_capture` (loop) | standard |
| U5 | Toggle Wi-Fi / change a secure setting | `set_setting`, `toggle_wifi` | privileged (WSS) |
| U6 | Drive an app end-to-end ("book the 7pm slot") | perception + interaction suite | standard |
| U7 | Pull a log file off the device | `adb_session_begin`, `pull_file` | privileged |
| U8 | "Dev mode on for the next hour, then off" | `adb_session_begin(ttl)` / scheduled `set_setting` | privileged |

---

## 5. System architecture

```
┌──────────────────────────── NUC (Ubuntu, Tailscale) ────────────────────────────┐
│                                                                                  │
│   Claude Code ─┐                                                                 │
│                ├── stdio/MCP ──►  DroidHand MCP server (TypeScript)              │
│   Codex ───────┘                        │     ▲                                  │
│                                         │     │ perception events                │
│                                   tool  │     │ (tree, png, notif)               │
│                                  calls  ▼     │                                  │
│                              ┌──────────────────────────┐                        │
│                              │  Relay (WSS server)       │  bound to Tailscale IP │
│                              │  auth token + TLS + ACL   │  :PORT (no public)     │
│                              └──────────────┬───────────┘                        │
│                              append-only audit log  ◄── every command/result     │
└─────────────────────────────────────────────┼───────────────────────────────────┘
                                               │  outbound WSS over Tailscale
                                               │  (phone is the client; no inbound)
                                               ▼
┌──────────────────────────── Phone (personal profile) ───────────────────────────┐
│   DroidHand Portal app (Kotlin, foreground service)                              │
│   ├── Relay client (reconnecting WSS)                                            │
│   ├── AccessibilityService  ── snapshot tree, dispatch gestures, global actions  │
│   ├── Screen capture         ── MediaProjection / a11y takeScreenshot            │
│   ├── Settings writer        ── WRITE_SECURE_SETTINGS (secure/global/system)     │
│   ├── ADB-on-demand engine   ── enable wireless debug → in-proc adb client →     │
│   │                              run shell/install/grant → disable debug         │
│   ├── Notification listener  ── read/act on notifications                        │
│   ├── Security layer         ── confirm gates, kill switch tile, local audit     │
│   └── Foreground notification ── "Agent connected" + Disconnect button           │
└──────────────────────────────────────────────────────────────────────────────────┘
          [ Work profile (Intune) — OUT OF SCOPE, not touched ]
```

### 5.1 Topology decisions

- **Phone is always the WebSocket client** → no inbound ports, no NAT/firewall holes, survives IP changes. Reconnects with backoff.
- **Relay + MCP server co-located** in one Node process on the NUC (simpler); internally separable so the relay can later serve multiple MCP consumers or a cloud option.
- **Transport = WSS over Tailscale.** Tailscale provides device identity + encryption; we add an app-level bearer token and bind the listener to the Tailscale interface only (never `0.0.0.0`).
- **Perception is push + pull.** The agent can request a snapshot; the app can also push events (new notification, foreground app change) the agent subscribes to.

---

## 6. Component specifications

### 6.1 Portal app (Android, Kotlin)

- **Min SDK 30 (Android 11)** — required for stable wireless debugging + `takeScreenshot` a11y API. **Target** latest.
- **Modules:**
  - `a11y/` — `DroidHandAccessibilityService`: builds a serializable snapshot of the active window(s) with **stable refs** (hash of view path + text + bounds), dispatches `GestureDescription` taps/swipes/long-press, `performGlobalAction` (back/home/recents/notifications/quick-settings/screenshot), text set/clear via `ACTION_SET_TEXT`.
  - `capture/` — screenshot via `AccessibilityService.takeScreenshot()` (no per-shot consent) with MediaProjection fallback for screen recording.
  - `settings/` — typed wrappers over `Settings.Secure/Global/System` guarded by `WRITE_SECURE_SETTINGS`.
  - `adb/` — **ADB-on-demand engine** (see §8). In-process ADB client (clean-room; see license note), mDNS discovery of the TLS port, bundled per-app key, pairing flow.
  - `notif/` — `NotificationListenerService`: enumerate, read, dismiss, trigger actions.
  - `relay/` — reconnecting WSS client, message framing, backpressure, heartbeat.
  - `security/` — confirmation dialogs, Quick Settings **kill-switch tile**, persistent "Agent connected" notification, local append-only audit (encrypted at rest), idle timeout, rate limiter.
  - `ui/` — Jetpack Compose setup wizard (permission onboarding, pairing, status, log viewer, panic button).
- **Survival on Samsung/One UI:** request battery-optimization exemption; foreground service (type `connectedDevice`/`dataSync`); self-heal the a11y binding; guidance for "never sleeping apps."

### 6.2 Relay (NUC)

- WSS server bound to the Tailscale IP; TLS (self-signed pinned, or Tailscale-provided cert).
- **Auth:** bearer token (32+ bytes, stored `chmod 600` outside git) checked on connect; reject non-Tailscale peers; optional Tailscale ACL tag allowlist.
- One phone ↔ one relay session; rejects duplicates; session IDs; command/response correlation; event fan-out to subscribed MCP consumers.
- **Append-only audit sink** (JSONL) — every command, actor, args (secret-redacted), result, latency, confirmation outcome.

### 6.3 MCP server (NUC, TypeScript)

- `@modelcontextprotocol/sdk`, stdio transport (how Claude Code/Codex spawn it).
- Exposes the **tool catalog** (§7) + **resources** (`droidhand://screen`, `droidhand://tree`, `droidhand://notifications`, `droidhand://audit`).
- Maps each tool call → relay message → awaits result; enforces **tier + confirmation policy** before forwarding privileged/sensitive calls.
- Config/secrets from env (`DROIDHAND_TOKEN`, `DROIDHAND_RELAY_URL`).

---

## 7. MCP tool catalog

**Tiers:** `standard` (a11y, no special grant) · `privileged` (needs WSS and/or ADB-on-demand) · `sensitive` (touches personal data / comms / money). **Confirm** = requires on-device tap unless a session-scoped allow is active.

### 7.1 Perception
| Tool | Args | Returns | Tier |
|---|---|---|---|
| `screen_snapshot` | `{detail?}` | a11y tree w/ stable refs, bounds, text, roles | standard |
| `screen_capture` | `{format?, quality?}` | PNG (base64/resource) | standard |
| `list_windows` | — | windows, focus, package | standard |
| `get_foreground_app` | — | package, activity | standard |
| `get_notifications` | `{filter?}` | list w/ refs, actions | standard |
| `read_notification` | `{ref}` | full text, actions | standard |
| `list_apps` | `{includeSystem?}` | packages, labels | standard |
| `app_info` | `{package}` | version, perms, state | standard |

### 7.2 Interaction (AccessibilityService)
| Tool | Args | Tier |
|---|---|---|
| `tap` | `{ref} \| {x,y}` | standard |
| `long_press` | `{ref\|xy, durationMs?}` | standard |
| `double_tap` | `{ref\|xy}` | standard |
| `swipe` | `{from, to, durationMs?}` | standard |
| `scroll` | `{ref?, direction, amount?}` | standard |
| `type_text` | `{ref?, text, submit?}` | standard |
| `set_text` / `clear_text` | `{ref, text?}` | standard |
| `drag` | `{from, to}` | standard |
| `key_event` | `{key}` back/home/recents/volume/power | standard |
| `global_action` | `{action}` notifications/quick_settings/clear_all/screenshot | standard |

### 7.3 App & navigation
| Tool | Args | Tier |
|---|---|---|
| `launch_app` | `{package}` | standard |
| `close_app` | `{package}` | standard |
| `open_url` | `{url}` | standard |
| `open_intent` | `{action, data?, extras?}` | standard |
| `go_home` / `open_recents` | — | standard |

### 7.4 Device state & settings
| Tool | Args | Tier |
|---|---|---|
| `get_setting` | `{namespace, key}` | standard |
| `set_setting` | `{namespace, key, value}` | **privileged (WSS)** |
| `toggle_wifi` / `toggle_bluetooth` | `{on}` | privileged |
| `set_volume` / `set_brightness` | `{value}` | privileged |
| `rotate` | `{orientation}` | privileged |
| `get_battery` / `get_device_info` | — | standard |

### 7.5 ADB-on-demand (privileged, confirm)
| Tool | Args | Notes |
|---|---|---|
| `adb_session_begin` | `{ttlSeconds?, reason}` | enables wireless debug, connects local client; auto-ends on TTL |
| `adb_shell` | `{command}` | within an open session; denylist for destructive patterns |
| `adb_install` | `{apkPathOnPhone \| apkRef}` | still shows system install UI unless policy allows |
| `adb_uninstall` | `{package}` | confirm |
| `pm_grant` / `pm_revoke` | `{package, permission}` | confirm |
| `adb_session_end` | — | disables debug; always called in `finally` |

### 7.6 Files & media (privileged/sensitive)
| Tool | Args | Tier |
|---|---|---|
| `list_files` / `read_file` / `write_file` | `{path,…}` | privileged |
| `pull_file` / `push_file` | `{src,dst}` | privileged |
| `take_photo` | `{camera}` | sensitive, confirm |
| `record_screen` | `{durationMs}` | sensitive, confirm |

### 7.7 Comms & personal data (sensitive, confirm)
| Tool | Args | Tier |
|---|---|---|
| `get_sms` / `send_sms` | `{…}` | sensitive, confirm (send) |
| `get_contacts` / `get_call_log` | `{…}` | sensitive |
| `place_call` | `{number}` | sensitive, confirm |
| `get_clipboard` / `set_clipboard` | `{…}` | sensitive |

### 7.8 Session & safety
| Tool | Args | Notes |
|---|---|---|
| `wait_for` | `{condition, timeoutMs}` | poll a11y until predicate |
| `find` | `{query}` | locate node by text/role/id |
| `request_confirmation` | `{prompt}` | explicit on-device approve |
| `get_audit_log` | `{since?}` | read audit |
| `panic_disconnect` | — | sever session + end adb + revoke session allows |

---

### 7.9 Feature-suggestion log (agent self-improvement, default OFF)

While an agent uses the phone it can log ideas — new features, capabilities, harness, optimizations, routines — that would speed up future work. These accumulate for when the operator builds a new version; any fork inherits the same capability.

- **Toggle lives in the app**, **OFF by default**. When OFF, `suggest_feature` is a no-op that returns a clear "enable it in the app" message. The device reports its toggle state to the relay on connect.
- **Storage:** the app owns the on-device log (source of truth); the NUC keeps a readable mirror (`suggestions.jsonl`) so suggestions are easy to list and export. Nothing is auto-committed — `export_suggestions` renders a reviewable `SUGGESTIONS.md`.
- **Tools:** `suggest_feature {title, detail, category, priority?, context?}`, `list_suggestions`, `export_suggestions`.
- **Privacy:** suggestions may reference the operator's workflows, so they stay local (gitignored) until the operator chooses to share them.

### 7.10 Background display ("work in the background" + mini window)

The agent can drive apps on a second display while the operator keeps using the phone, watching it in a small floating window and stepping in when needed (captcha, login). The operator picks the method in the app — **native**, **own window**, or **let the agent decide** (default) — and the choice overrides what the agent asks for.

| | Native (`overlay_display_devices`) | Own window (shell-helper trusted display) |
|---|---|---|
| Needs | WRITE_SECURE_SETTINGS only — **works with debugging OFF** | Shell helper started over ADB (`own_helper_start`); **debugging must stay ON** for the whole session (Android kills shell-user processes when it turns off — measured) |
| Window | Drawn by the system (translucent, fixed look) | Ours: opacity setting (opaque by default), resizable from the corner, draggable |
| Hide while the agent keeps working | ✗ | ✓ "Esconder" → eye bubble to bring it back; the display keeps rendering into an off-screen sink (a display with no surface turns OFF and its apps stop) |
| Close | Drag our handle onto the X bubble; apps move behind the user's apps | Drag the title bar onto the X bubble; the display and its apps are destroyed |
| Operator interaction | Through the system window | Touches injected into the display (INJECT_EVENTS), back button |
| Stop | Small red Stop bubble (shown while the agent acts, pinned during background sessions) cancels the current action only; the agent gets `stopped_by_user` on that/next call; nothing needs re-enabling. Full kill switch = notification Disconnect / app / `panic_disconnect` |

Tools: `bg_open {mode: auto|native|own}`, `bg_status`, `bg_hide`, `bg_show`, `bg_close`, `request_user_help {message}`, `own_helper_start`, `own_helper_stop`; every phone tool takes `display`.

**Rejected alternatives (tested):**
- *App-owned VirtualDisplay* (no ADB): Android only lets a normal app create an untrusted `PUBLIC|OWN_CONTENT_ONLY` display (TRUSTED, SECURE and mirroring are denied). Launching any other app there — even our own non-embedded activity — is a SecurityException. Launched through `adb shell am start --display N` it works, but the first time the app opens another activity (splash→main, Chrome→History) Android moves the whole task to the user's screen.
- *Screenshot-fed window* (~3 fps via a11y screenshots): too slow, rate-limited.
- *One UI pop-up (freeform) window* on the main display: needs no ADB and is native/resizable, but it shares the user's screen and input, an agent tap moves focus away from the user's app, and when minimized the app stops and disappears from accessibility.

Other measured facts: on a background display the IME does not pop up on the user's screen; accessibility global actions (back/home) hit the main display, so keys go through the helper instead; trusted displays keep every activity of the app on the background display.

## 8. ADB-on-demand mechanism (detailed)

Mirrors Shizuku's non-root wireless start, but scoped to short, logged windows and driven by the agent rather than boot.

**One-time setup (operator, with help):** enable wireless debugging once; the app **pairs itself** (stores its own ADB keypair) so future sessions need no computer. Grant `WRITE_SECURE_SETTINGS` once via ADB: `pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS`.

**Per session (`adb_session_begin`):**
1. `Settings.Global.putInt("adb_wifi_enabled", 1)`, `putInt(ADB_ENABLED, 1)`.
2. Discover the `_adb-tls-connect._tcp` port via mDNS (NsdManager).
3. Connect an **in-process ADB client** to `127.0.0.1:<port>` using the stored key (TLS-auth).
4. Run requested shell/install/grant commands; stream output.
5. **`finally`:** `adb_session_end` → `adb_wifi_enabled=0`, `ADB_ENABLED=0`. Also restore `adb_allowed_connection_time` to the OS default (Shizuku had zeroed it; we put the 7-day auto-revoke back).

**Guards:** TTL (default 120s); a session is always wrapped so a crash still disables debug; every begin/end + command is audited; `adb_shell` carries a configurable denylist (`rm -rf`, `wipe`, `format`, `dd`, factory-reset intents) that requires explicit extra confirmation.

**Constraints:** requires Android 11+; requires the phone on a Wi-Fi network; if an MDM compliance poll lands inside a window it may observe debugging — surfaced honestly in docs (§2.1).

> **License note:** DroidHand will **not** copy Shizuku's GPL/Apache code verbatim. The ADB client (key handling, A_AUTH/TLS handshake, mDNS) will be a clean-room implementation or an Apache-2.0/MIT library (e.g. a maintained `adblib`-style dependency), tracked in a THIRD-PARTY-NOTICES file.

---

## 9. Security & privacy model

### 9.1 Threat model
- **Asset:** full control of a personal phone (data, comms, money, identity).
- **Adversary:** anyone who reaches the control plane (stolen token, compromised NUC, malicious agent prompt-injection).
- **Trust boundary:** the phone trusts *only* an authenticated relay session over Tailscale; the agent is semi-trusted (can be prompt-injected), so **destructive/sensitive actions require on-device confirmation**, not just an agent decision.

### 9.2 Controls
- **Transport:** Tailscale-only bind + TLS + bearer token; reject non-tailnet peers; optional ACL tag.
- **Authorization tiers:** standard / privileged / sensitive, enforced server-side *and* app-side (defense in depth).
- **Confirmation gates:** sensitive + destructive actions need an on-device tap; session-scoped "allow for 10 min" possible but never default-on for money/wipe/credential categories.
- **Kill switch:** persistent notification "Disconnect" button + Quick Settings tile + `panic_disconnect` tool; killing ends the relay session and any ADB session.
- **Audit:** append-only JSONL on NUC *and* encrypted local log on phone; secrets redacted; includes actor, args, result, confirmation outcome, latency.
- **PII mode:** optional redaction of SMS bodies/contacts/notification text in snapshots unless a tool explicitly requests full content (inspired by android-remote-control-mcp).
- **Idle timeout & rate limits:** auto-disconnect after inactivity; per-tool rate caps.
- **Minimize-exposure:** debugging off except during audited ADB windows.
- **Work-profile boundary:** app has no device-admin/work-profile scope; documents that a11y may be restricted inside the managed container and does not attempt to circumvent it.

### 9.3 Privacy
- No telemetry. No cloud by default. All data stays NUC↔phone over the tailnet.
- Secrets (token, ADB key) never committed; `.gitignore` + docs enforce `chmod 600`, outside git.

---

## 10. Data & logging
- **Audit record (JSONL):** `{ts, sessionId, actor, tool, tier, argsRedacted, confirm:{required,outcome}, result:{ok,summary}, latencyMs }`.
- **Screen snapshot schema:** `{ windows:[{package, bounds, nodes:[{ref, role, text?, desc?, bounds, actions[], children}]}] }` — refs stable across identical frames.
- **Retention:** rotate audit at N MB; configurable; never auto-deleted silently.

---

## 11. Tech stack
| Layer | Choice | Why |
|---|---|---|
| Portal app | Kotlin + Jetpack Compose | matches existing campainha APK toolchain; modern a11y APIs |
| MCP server | TypeScript + `@modelcontextprotocol/sdk` | best MCP support; Claude Code/Codex spawn via stdio; matches mobile-mcp ecosystem |
| Relay | Node `ws` | same process as MCP server; simple |
| Transport | WSS over Tailscale | identity + encryption for free; outbound-only |
| ADB client | clean-room / Apache-MIT `adblib`-style | license-clean |
| CI | GitHub Actions | build/lint/test/release APK |
| License | Apache-2.0 | patent grant, corporate-friendly |

---

## 12. Agent integration (Claude Code & Codex on the NUC)

Ready-to-paste once the MCP server is built (Phase 1). **Not wired in until the server exists** (registering a dead server would just error).

**Claude Code** — `claude mcp add` or project `.mcp.json` / `~/.claude.json`:
```json
{
  "mcpServers": {
    "droidhand": {
      "command": "node",
      "args": ["/path/to/droidhand/mcp-server/dist/index.js"],
      "env": {
        "DROIDHAND_RELAY_URL": "wss://<NUC_TAILSCALE_IP>:<PORT>",
        "DROIDHAND_TOKEN_FILE": "/home/<you>/.config/droidhand/token"
      }
    }
  }
}
```

**Codex** — `~/.codex/config.toml`:
```toml
[mcp_servers.droidhand]
command = "node"
args = ["/path/to/droidhand/mcp-server/dist/index.js"]
env = { DROIDHAND_RELAY_URL = "wss://<NUC_TAILSCALE_IP>:<PORT>", DROIDHAND_TOKEN_FILE = "/home/<you>/.config/droidhand/token" }
```

---

## 13. Roadmap / milestones

| Phase | Scope | Exit criteria |
|---|---|---|
| **P0** | PRD + repo scaffold (this) | Repo public-ready; PRD reviewed |
| **P1 — MVP** | a11y perception + interaction; WSS relay; MCP server (perception+interaction); kill switch; Claude/Codex config | Agent drives a real app end-to-end with **debugging off** |
| **P2 — Privilege** | ADB-on-demand engine; settings writer; privileged tools; confirmation gates; audit | `adb_install` + `pm_grant` work in a <120s window, fully logged |
| **P3 — Breadth & hardening** | files/media/comms; PII mode; macros; session recording; rate limits; CI + signed releases; F-Droid | Reproducible signed APK from CI; SECURITY policy live |
| **P4 — Stretch** | cloud relay option; multi-device; iOS exploration | — |

---

## 14. Testing & QA
- **Unit:** snapshot serializer, ref stability, settings wrappers, denylist, auth.
- **Instrumented (UIAutomator/Espresso):** gesture dispatch, a11y snapshot vs. known app.
- **MCP contract tests:** each tool's schema + happy/err paths against a fake relay.
- **E2E in CI:** Android emulator (debugging allowed in CI) exercises the full agent→tool→device loop.
- **Security tests:** reject bad token / non-tailnet peer; confirm gate can't be bypassed; kill switch severs mid-action; ADB window always closes on crash.
- **Device matrix:** at least one Samsung/One UI (battery-kill behavior) + one Pixel.

---

## 15. Open-source governance
- **License:** Apache-2.0 (`LICENSE`, `THIRD-PARTY-NOTICES`).
- **Repo layout:** `app/`, `mcp-server/`, `relay/`, `docs/`, `.github/`.
- **Policies:** `README`, `SECURITY.md` (private disclosure), `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, issue/PR templates.
- **Process:** semver, Conventional Commits, signed release APKs, CHANGELOG, CI gates on PRs.
- **Privacy in repo:** no personal names/emails/IPs/keys; placeholders (`<NUC_TAILSCALE_IP>`) everywhere.

---

## 16. Risks & mitigations
| Risk | Severity | Mitigation |
|---|---|---|
| Full-control endpoint is a juicy target | High | Tailscale-only + token + confirm gates + audit + kill switch |
| Agent prompt-injection triggers harmful action | High | On-device confirmation for sensitive/destructive; denylist; PII mode |
| MDM compliance observes an ADB window | Med | Short TTL; honest docs; operator responsibility (§2.1) |
| Samsung kills the foreground service | Med | Battery exemption, self-heal, docs |
| Android version fragmentation | Med | Min SDK 30, device matrix, capability detection |
| License contamination (Shizuku code) | Med | Clean-room ADB client + THIRD-PARTY-NOTICES |
| Play Store rejection | Low | GitHub + F-Droid distribution by design |
| Maintenance burden of a broad tool surface | Med | Phased delivery; stable module boundaries |

---

## 17. Success metrics
- Agent completes a 10-step cross-app task with debugging off: **≥95%** of steps without human touch.
- ADB window median duration **< 5s**; **100%** of windows closed (no leaks) in test.
- **0** actions executed without required confirmation in security tests.
- Reproducible signed APK from CI; SECURITY disclosure path documented.

---

## 18. Open questions
1. Final project **name** (droidhand is provisional).
2. ADB client: clean-room vs. a vetted Apache/MIT library — which dependency?
3. Confirmation UX: per-action tap vs. session-scoped allowlists — defaults per category?
4. Do we want an optional **local VLM** on the NUC for screenshot grounding, or rely on the agent's own vision?
5. PII redaction: on by default, or opt-in?

---

## 19. References
- mobile-next/mobile-mcp · DroidRun/mobilerun · Magi-Labs/mobilemcp · danielealbano/android-remote-control-mcp · CocoCopi/AndroMCP · RikkaApps/Shizuku (mechanism study only)
- Android: AccessibilityService, `takeScreenshot`, NsdManager, `WRITE_SECURE_SETTINGS`, NotificationListenerService
- Model Context Protocol specification
