# DroidHand MCP server + relay

The host side of DroidHand: it lets AI agents (Claude Code, Codex, any MCP client) control an Android phone running the DroidHand app. It is made of two processes:

- **Relay daemon** (`dist/daemon.js`, bin `droidhand-relay`): one per host. The phone dials out to it over WebSocket; it also serves a loopback **control** channel for the adapters. Run it as a user systemd service.
- **MCP adapter** (`dist/index.js`, bin `droidhand-mcp`): one per agent session, over stdio. It binds no port and forwards every tool call to the daemon. If the daemon is down, tools still list and calls return a clear error.

```
agent ──stdio MCP──► adapter ──ws (loopback)──► relay daemon ◄──ws (private network)── phone app
```

The phone always connects to the relay, never the other way round. Both links require the same shared bearer token; with no token configured, everything is rejected (fail closed).

Tool reference: [../docs/tools.md](../docs/tools.md) (generated from the catalog).

## Build

Requires Node.js 20 or newer.

```bash
npm ci
npm run build        # compiles src/ to dist/
```

## Run the relay

```bash
export DROIDHAND_TOKEN_FILE=/path/to/token   # a 0600 file holding a random secret, e.g. `openssl rand -hex 32`
npm run relay                                # node dist/daemon.js
```

By default the daemon listens on `127.0.0.1:8787` (phone) and `127.0.0.1:8788` (control). Expose the phone port to the phone over a private network (for example `tailscale serve --tcp=8787 tcp://127.0.0.1:8787`), not the public internet.

[`../deploy/setup.sh`](../deploy/setup.sh) does all of this on a Linux host: it creates the token and installs the `droidhand-relay` user systemd service.

## Register with an agent

**Claude Code:**

```bash
claude mcp add droidhand --scope user \
  -e DROIDHAND_CONTROL_PORT=8788 \
  -e DROIDHAND_TOKEN_FILE=/path/to/token \
  -- node /path/to/droidhand/mcp-server/dist/index.js
```

**Codex** (`~/.codex/config.toml`):

```toml
[mcp_servers.droidhand]
command = "node"
args = ["/path/to/droidhand/mcp-server/dist/index.js"]
env = { DROIDHAND_CONTROL_PORT = "8788", DROIDHAND_TOKEN_FILE = "/path/to/token" }
```

## Configuration

Read from the environment by [`src/config.ts`](src/config.ts); see also [`.env.example`](.env.example).

| Variable | Default | Used by | Meaning |
| --- | --- | --- | --- |
| `DROIDHAND_TOKEN` | (empty) | both | Shared bearer token. Takes precedence over the file. |
| `DROIDHAND_TOKEN_FILE` | (none) | both | File holding the token (preferred). |
| `DROIDHAND_RELAY_HOST` | `127.0.0.1` | daemon | Interface the phone-facing relay binds to. |
| `DROIDHAND_RELAY_PORT` | `8787` | daemon | Phone-facing port. |
| `DROIDHAND_CONTROL_HOST` | `127.0.0.1` | both | Loopback control channel host. |
| `DROIDHAND_CONTROL_PORT` | `8788` | both | Control channel port. |
| `DROIDHAND_CMD_TIMEOUT_MS` | `15000` | daemon | How long to wait for the phone to answer a command. |
| `DROIDHAND_AUDIT_DIR` | `$XDG_STATE_HOME/droidhand/audit` | daemon | Append-only `audit.jsonl` (secrets redacted). |
| `DROIDHAND_SUGGESTIONS_DIR` | `$XDG_STATE_HOME/droidhand/suggestions` | daemon | Mirror of agent feature suggestions. |
| `DROIDHAND_ADB` | `adb` | daemon | adb binary for `own_helper_start`/`own_helper_stop`. |
| `DROIDHAND_ADB_SERIAL` | (auto) | daemon | Device serial for those tools; otherwise the first attached or mDNS-advertised device. |

`$XDG_STATE_HOME` defaults to `~/.local/state`.

## Source layout

```
src/
  index.ts            entry: stdio MCP adapter (droidhand-mcp)
  daemon.ts           entry: relay daemon (droidhand-relay)
  config.ts           environment → Config
  protocol.ts         phone ⇄ relay wire protocol (frames, close codes)
  catalog.ts          tool catalog (re-exports src/catalog/*, adds the `display` argument)
  catalog/            tool definitions by area
  core/               tool routing, local tools, audit log, suggestions, adb helper
  relay/server.ts     phone-facing WebSocket relay
  control/            daemon ⇄ adapter channel (server, client, frames)
  mcp/server.ts       MCP server built from the catalog
  dev/                mock phone for tests and manual runs
scripts/gen-tools-doc.ts   generates ../docs/tools.md
```

Tool names, input schemas, frame shapes and error strings are a contract with the Android app; changing them breaks the phone side. To add a tool, add it to the right file in `src/catalog/`, implement it in the app (or in `src/core/core.ts` if it is `local`), add its name to `test/catalog.test.ts`, and run `npm run docs:tools`.

## Development

```bash
npm test             # node:test via tsx; no build or phone needed
npm run typecheck    # src, tests and scripts
npm run docs:tools   # regenerate ../docs/tools.md (a test fails if it is stale)
```

Without a phone, a mock one can connect to a running relay:

```bash
DROIDHAND_TOKEN=$(cat /path/to/token) SUGGESTION_LOGGING=1 npm run mock-phone
```
