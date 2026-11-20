# DroidHand — MCP server + relay (TypeScript)

Runs on the NUC. Exposes the phone's capabilities to Claude Code / Codex as MCP tools, and hosts the relay the phone dials into. **Scaffold stage — no code yet.** See [../docs/PRD.md](../docs/PRD.md) §6.2–6.3, §7.

## Planned shape
```
mcp-server/
  src/
    index.ts        # MCP stdio entrypoint (@modelcontextprotocol/sdk)
    tools/          # one file per tool group (perception, interaction, adb, …)
    relay/          # WSS server bound to Tailscale IP, token+TLS auth
    audit/          # append-only JSONL sink
    policy.ts       # tier + confirmation enforcement
  package.json
  tsconfig.json
```

## Config (env)
- `DROIDHAND_RELAY_URL` — `wss://<NUC_TAILSCALE_IP>:<PORT>`
- `DROIDHAND_TOKEN_FILE` — path to the bearer token (chmod 600, outside git)

## Run (once built)
```
npm i && npm run build
node dist/index.js    # spawned by the agent over stdio
```

## Register with agents
See PRD §12 for ready-to-paste Claude Code (`.mcp.json`) and Codex (`~/.codex/config.toml`) stanzas.
