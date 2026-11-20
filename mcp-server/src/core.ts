import type { Config } from "./config.js";
import { Relay } from "./relay/server.js";
import { Audit, redact } from "./audit.js";
import { SuggestionStore } from "./suggestions.js";
import { CATALOG_BY_NAME } from "./catalog.js";

export interface ToolResult {
  ok: boolean;
  data?: unknown;
  error?: string;
}

/**
 * Core wires the relay, audit log and suggestion store together and routes each
 * MCP tool call either to a local handler or out to the phone.
 */
export class Core {
  readonly relay: Relay;
  readonly audit: Audit;
  readonly suggestions: SuggestionStore;

  constructor(cfg: Config) {
    this.relay = new Relay(cfg.relayHost, cfg.relayPort, cfg.token, cfg.cmdTimeoutMs);
    this.audit = new Audit(cfg.auditDir);
    this.suggestions = new SuggestionStore(cfg.suggestionsDir);
  }

  async start(): Promise<void> {
    await this.relay.start();
    this.relay.onEvent((event, payload) => this.audit.record({ kind: "event", event, payload: redact(payload) }));
  }

  async stop(): Promise<void> {
    await this.relay.stop();
  }

  async callTool(name: string, args: Record<string, unknown>): Promise<ToolResult> {
    const def = CATALOG_BY_NAME.get(name);
    if (!def) return { ok: false, error: `unknown tool '${name}'` };

    // Local handlers (never leave the NUC).
    switch (name) {
      case "suggest_feature":
        return this.suggest(args);
      case "list_suggestions":
        return { ok: true, data: this.suggestions.list() };
      case "export_suggestions":
        return { ok: true, data: this.suggestions.exportMarkdown() };
      case "get_audit_log":
        return { ok: true, data: { path: this.audit.path() } };
      case "panic_disconnect":
        // Ask the phone to stop its agent service (stays off until the operator restarts
        // it in the app), then sever the session regardless of the answer.
        if (this.relay.connected) await this.relay.dispatch("panic", {}).catch(() => undefined);
        this.relay.disconnectPhone();
        this.audit.record({ kind: "safety", tool: name, ok: true });
        return { ok: true, data: { disconnected: true } };
    }

    // Everything else is forwarded to the phone.
    this.audit.record({
      kind: "cmd",
      tool: name,
      tier: def.tier,
      confirmRequired: def.confirm,
      args: redact(args),
      phase: "request",
    });
    try {
      const data = await this.relay.dispatch(name, args);
      this.audit.record({ kind: "cmd", tool: name, ok: true });
      return { ok: true, data };
    } catch (err) {
      const error = err instanceof Error ? err.message : String(err);
      this.audit.record({ kind: "cmd", tool: name, ok: false, error });
      return { ok: false, error };
    }
  }

  private suggest(args: Record<string, unknown>): ToolResult {
    if (!this.relay.connected) {
      return { ok: false, error: "no device connected" };
    }
    if (!this.relay.deviceInfo?.suggestionLogging) {
      return {
        ok: false,
        error:
          "Suggestion logging is OFF. The operator can enable it in the DroidHand app ('Allow the agent to log feature suggestions'); it is off by default.",
      };
    }
    const title = String(args.title ?? "").trim();
    const detail = String(args.detail ?? "").trim();
    if (!title || !detail) return { ok: false, error: "title and detail are required" };

    const entry = this.suggestions.add({
      title,
      detail,
      category: args.category as never,
      priority: typeof args.priority === "number" ? args.priority : undefined,
      context: args.context ? String(args.context) : undefined,
      source: "agent",
    });
    // Best-effort on-device persistence (source of truth); ignore if the app lacks the handler.
    this.relay.dispatch("store_suggestion", entry as unknown as Record<string, unknown>).catch(() => {});
    this.audit.record({ kind: "suggestion", id: entry.id, title: entry.title });
    return { ok: true, data: entry };
  }
}
