# Setup guide

About 10 minutes. You need:

- a **computer** that stays on (Linux or macOS; a home server is ideal) with **Node.js 20+** and **git**;
- an **Android 11+** phone;
- a **private network** between them — [Tailscale](https://tailscale.com) is easiest and works away from home; a home LAN also works;
- optional: **adb** ([platform-tools](https://developer.android.com/tools/releases/platform-tools)) for the one-time secure-settings grant and the own-window background method.

```
  computer                                         phone
  ┌──────────────────────────────┐                 ┌─────────────────────┐
  │ agent ─► MCP adapter ─► relay │ ◄── WebSocket ── │ DroidHand app       │
  │                        :8787 │   (phone dials)  │                     │
  └──────────────────────────────┘                 └─────────────────────┘
```

## 1. Relay on the computer

```bash
git clone https://github.com/gabrielalberton/droidhand.git
cd droidhand
deploy/setup.sh
```

`setup.sh` is idempotent. It:

1. creates a random token in `~/.config/droidhand/token` (`0600`, never printed);
2. builds the server (`mcp-server/dist/`);
3. installs and starts the `droidhand-relay` **user** systemd service.

The relay listens on `127.0.0.1:8787` (phone) and `127.0.0.1:8788` (agents). Let the phone reach port 8787 over your private network:

```bash
# Tailscale (recommended): serve the port on your tailnet IP only
tailscale serve --tcp=8787 tcp://127.0.0.1:8787
```

On a LAN without Tailscale, set `DROIDHAND_RELAY_HOST` to the computer's LAN address in the service instead (`systemctl --user edit droidhand-relay`). Never expose the port to the internet.

Check it: `systemctl --user status droidhand-relay`.

> **No systemd?** Run it in the foreground: `cd mcp-server && DROIDHAND_TOKEN_FILE=~/.config/droidhand/token npm run relay`.

## 2. App on the phone

1. Download **`droidhand-<version>.apk`** from the [latest release](https://github.com/gabrielalberton/droidhand/releases/latest) and open it (allow installing from your browser/files app when Android asks). Verify it if you like: `sha256sum` against `SHA256SUMS`, and the signing certificate against `signing-certificate.txt` in the release.
2. Open **DroidHand**. The home screen shows the connection status and a permissions panel.

<p align="center"><img src="images/screenshots/home.png" alt="DroidHand home screen: status card and permissions panel" width="300"></p>

3. **Connection** → enter the relay address, `ws://<computer-tailnet-ip>:8787`, and paste the token (`cat ~/.config/droidhand/token` on the computer). It saves automatically and reconnects when you leave the screen.
4. **Permissions** panel → tap each row:
   - **Accessibility** → *Installed apps* → *DroidHand* → on. This is what lets the agent read and use the screen.
     On Android 13+ a sideloaded app's accessibility switch may be greyed out as a *restricted setting*: open *App info → ⋮ → Allow restricted settings*, then try again.
   - **Notifications** → allow (the always-on status with a Disconnect button).
   - **Unrestricted battery** → allow, so the connection survives the screen being off.
   - **Secure settings** (optional) → from the computer, once:

     ```bash
     adb shell pm grant work.alberton.droidhand android.permission.WRITE_SECURE_SETTINGS
     ```

     Needed for the native background window and for the agent to toggle debugging/animation settings.

When the status card says **Connected**, the phone side is done.

> **Samsung / One UI:** also add DroidHand to *Settings → Battery → Background usage limits → Never sleeping apps*.

## 3. Register with your agent

**Claude Code**

```bash
claude mcp add droidhand --scope user \
  -e DROIDHAND_TOKEN_FILE=$HOME/.config/droidhand/token \
  -- node /path/to/droidhand/mcp-server/dist/index.js
```

**Codex** — `~/.codex/config.toml`:

```toml
[mcp_servers.droidhand]
command = "node"
args = ["/path/to/droidhand/mcp-server/dist/index.js"]
env = { DROIDHAND_TOKEN_FILE = "/home/<you>/.config/droidhand/token" }
```

**Any other MCP client:** run `node mcp-server/dist/index.js` over stdio with `DROIDHAND_TOKEN_FILE` set.

Try it: *"What's on my phone's screen?"* — the agent calls `screen_snapshot`. The full list is in [tools.md](tools.md).

## 4. Optional

### Background window, own-window method

The native method works out of the box (with the secure-settings grant). The **own window** method — hideable, resizable, works while the phone is locked — needs the computer to reach the phone over adb:

1. On the phone: *Developer options → Wireless debugging* → on, and pair the computer once (`adb pair <ip:port>` with the pairing code).
2. The agent calls `own_helper_start`; the relay starts the helper over adb. It keeps running until debugging is turned off.

In the app, **Background window** shows which method the agent may use and the helper status.

### Privacy & safety settings

<p align="center"><img src="images/screenshots/privacy.png" alt="Privacy & safety screen" width="300"></p>

- **Ask before using the main screen** (on by default): while you are using the phone, the agent asks before tapping or typing on your screen.
- **Feature suggestions** (off by default): lets the agent log ideas for DroidHand on your computer (`suggest_feature`).
- **Unlock by the agent** (off by default, dangerous): saves your PIN encrypted with a key in Android Keystore so the agent can unlock the phone with `unlock_device`. Saving and showing the PIN require your fingerprint/face/PIN; one wrong attempt turns the feature off. Read the warning in the app before enabling it.

### Appearance

<p align="center"><img src="images/screenshots/appearance.png" alt="Appearance screen: theme, language, accent colors" width="300"></p>

System/light/dark theme, English or Portuguese, wallpaper colors or one of five accents.

### Kill switches

Any of these stops the agent at once and closes its background window:

- **Disconnect** in the DroidHand notification;
- the **DroidHand** Quick Settings tile (*edit* your quick settings to add it);
- `panic_disconnect` from the agent side.

The small red **Stop** button only cancels the current action; the agent is told and the connection stays up.

## Updating

- **App:** install the newer APK over the old one (same signing key — no data is lost). Android turns accessibility services off on update; `deploy/install-phone.sh` re-enables it when you install over adb.
- **Relay:** `git pull && cd mcp-server && npm ci && npm run build && systemctl --user restart droidhand-relay`.

## Troubleshooting

| Symptom | Fix |
|---|---|
| Status **Token rejected** | The token in the app differs from `~/.config/droidhand/token`. Paste it again. |
| Status stays **Connecting…** | The phone can't reach port 8787: check `tailscale serve status`, that both devices are on the tailnet, and the address starts with `ws://`. |
| Agent says `accessibility service not enabled` | Turn the accessibility service on again (it is turned off after app updates and force-stops). |
| `awaiting_user` / `user_declined` | You were using the phone; answer the prompt, or let the agent use `bg_open`. |
| `stopped_by_user` | You tapped Stop. The agent should check with you before continuing. |
| `indicators_not_visible` | The background window's controls could not be drawn, so the session was closed. Re-open it; if it repeats, restart the accessibility service. |
| `own_helper_start` fails | Wireless debugging is off or the computer is not paired (`adb devices` must list the phone). |
| Disconnects with the screen off | Allow unrestricted battery; on Samsung add DroidHand to *Never sleeping apps*. |

Logs: the relay's audit log is `~/.local/state/droidhand/audit/audit.jsonl`; the service log is `journalctl --user -u droidhand-relay`.
