# DroidHand — MCP server + relay (TypeScript)

Runs on the NUC. Two processes:

- **Relay daemon** (`dist/daemon.js`, bin `droidhand-relay`) — one per host. Owns the phone-facing relay (dial-in over Tailscale) and a loopback **control** channel. Run as a user systemd service.
- **MCP adapter** (`dist/index.js`, bin `droidhand-mcp`) — spawned per agent session (Claude Code, Codex…). Binds nothing; forwards tool calls to the daemon over the control channel. Degrades gracefully if the daemon is down.

```
Claude Code / Codex ──stdio MCP──► adapter ──ws(loopback)──► relay daemon ──ws(Tailscale)──► phone
```

Status: **built & tested.** 30 MCP tools (perception, interaction, app/nav, settings, ADB-on-demand, suggestions, safety). See [../docs/PRD.md](../docs/PRD.md) §6–7.

## Install / run

```bash
npm ci
npm run build
npm test          # 7 tests: core round-trip, suggestion gate, token auth, panic, stdio boot

# One-shot local deploy (token + user systemd service):
GH_OWNER=<you> ../deploy/setup.sh
```

The daemon listens on `127.0.0.1:8787` (phone) and `127.0.0.1:8788` (control). Expose the phone port over Tailscale when the app is ready:
```bash
tailscale serve --tcp=8787 tcp://127.0.0.1:8787
```

## Config (env)
See [.env.example](.env.example). Key vars: `DROIDHAND_TOKEN_FILE`, `DROIDHAND_RELAY_HOST/PORT`, `DROIDHAND_CONTROL_HOST/PORT`.

## Register with agents

**Claude Code** (user scope):
```bash
claude mcp add droidhand --scope user \
  -e DROIDHAND_CONTROL_PORT=8788 \
  -e DROIDHAND_TOKEN_FILE=$HOME/.config/droidhand/token \
  -- node $PWD/dist/index.js
```

**Codex** (`~/.codex/config.toml`):
```toml
[mcp_servers.droidhand]
command = "node"
args = ["/abs/path/droidhand/mcp-server/dist/index.js"]
env = { DROIDHAND_CONTROL_PORT = "8788", DROIDHAND_TOKEN_FILE = "/home/you/.config/droidhand/token" }
```

## Manual phone mock (before the Android app exists)
```bash
DROIDHAND_TOKEN=$(cat ~/.config/droidhand/token) SUGGESTION_LOGGING=1 npm run mock-phone
```
