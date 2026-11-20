# DroidHand — Relay

Transport between the MCP server and the Portal app. **In P1 the relay is co-located with the MCP server** (`mcp-server/src/relay/`); this directory holds shared protocol docs and, later, a standalone relay if we split it out.

## Protocol (draft)
- Phone is the **WSS client** (outbound-only). NUC relay listens on the **Tailscale IP only**.
- Frames: `{id, type: "cmd"|"result"|"event", tool?, args?, payload?}`; `id` correlates cmd↔result.
- Auth: bearer token on connect; reject non-tailnet peers; one active phone session.
- Events: `notification`, `foreground_changed`, `adb_session_state`, pushed to subscribed consumers.
- Heartbeat + reconnect with backoff.

See [../docs/PRD.md](../docs/PRD.md) §5–6, §10.
