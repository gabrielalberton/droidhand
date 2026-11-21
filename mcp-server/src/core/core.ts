import type { Config } from "../config.js";
import { Relay } from "../relay/server.js";
import { CATALOG_BY_NAME } from "../catalog.js";
import { Audit, redact } from "./audit.js";
import { SuggestionStore, isSuggestionCategory } from "./suggestions.js";
import { startShellHelper, stopShellHelper } from "./adb.js";

/** Outcome of one tool call, as returned to the MCP adapter over the control channel. */
export interface ToolResult {
  ok: boolean;
  data?: unknown;
  error?: string;
}

type ToolArgs = Record<string, unknown>;

/** How many times / how often own_helper_start polls bg_status for the helper to show up. */
const HELPER_POLL_ATTEMPTS = 20;
const HELPER_POLL_INTERVAL_MS = 250;

function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

function ownHelperAvailable(status: unknown): boolean {
  return typeof status === "object" && status !== null && (status as { ownAvailable?: unknown }).ownAvailable === true;
}

/**
 * Core wires the relay, audit log and suggestion store together and routes each
 * tool call either to a local handler (tools with `local: true` in the catalog)
 * or out to the phone.
 */
export class Core {
  readonly relay: Relay;
  readonly audit: Audit;
  readonly suggestions: SuggestionStore;
  private readonly cfg: Config;

  constructor(cfg: Config) {
    this.cfg = cfg;
    this.relay = new Relay(cfg.relayHost, cfg.relayPort, cfg.token, cfg.cmdTimeoutMs);
    this.audit = new Audit(cfg.auditDir);
    this.suggestions = new SuggestionStore(cfg.suggestionsDir);
  }

  /** Start the phone-facing relay and audit every device event. */
  async start(): Promise<void> {
    await this.relay.start();
    this.relay.onEvent((event, payload) => this.audit.record({ kind: "event", event, payload: redact(payload) }));
  }

  async stop(): Promise<void> {
    await this.relay.stop();
  }

  /** Run a catalog tool. Never throws: failures come back as `{ ok: false, error }`. */
  async callTool(name: string, args: ToolArgs): Promise<ToolResult> {
    const def = CATALOG_BY_NAME.get(name);
    if (!def) return { ok: false, error: `unknown tool '${name}'` };

    // Local handlers (never leave the host).
    switch (name) {
      case "suggest_feature":
        return this.suggest(args);
      case "list_suggestions":
        return { ok: true, data: this.suggestions.list() };
      case "export_suggestions":
        return { ok: true, data: this.suggestions.exportMarkdown() };
      case "get_audit_log":
        return { ok: true, data: { path: this.audit.path() } };
      case "own_helper_start":
        return this.startHelper();
      case "own_helper_stop":
        return this.stopHelper();
      case "panic_disconnect":
        return this.panic();
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
      const error = errorMessage(err);
      this.audit.record({ kind: "cmd", tool: name, ok: false, error });
      return { ok: false, error };
    }
  }

  /**
   * Ask the phone to stop its agent service (it stays off until the operator
   * restarts it in the app), then sever the session regardless of the answer.
   */
  private async panic(): Promise<ToolResult> {
    if (this.relay.connected) await this.relay.dispatch("panic", {}).catch(() => undefined);
    this.relay.disconnectPhone();
    this.audit.record({ kind: "safety", tool: "panic_disconnect", ok: true });
    return { ok: true, data: { disconnected: true } };
  }

  /** Start the phone's shell helper over adb, then wait until the app reports it. */
  private async startHelper(): Promise<ToolResult> {
    const tool = "own_helper_start";
    try {
      const { serial } = await startShellHelper(this.cfg.adbPath, this.cfg.adbSerial);
      let available = false;
      for (let i = 0; i < HELPER_POLL_ATTEMPTS && !available; i++) {
        await new Promise((r) => setTimeout(r, HELPER_POLL_INTERVAL_MS));
        if (!this.relay.connected) continue;
        available = ownHelperAvailable(await this.relay.dispatch("bg_status", {}).catch(() => null));
      }
      this.audit.record({ kind: "cmd", tool, ok: available, serial });
      return available
        ? { ok: true, data: { serial, ownAvailable: true } }
        : { ok: false, error: "helper started but the app did not report it (is the agent service connected?)" };
    } catch (err) {
      const error = errorMessage(err);
      this.audit.record({ kind: "cmd", tool, ok: false, error });
      return { ok: false, error };
    }
  }

  private async stopHelper(): Promise<ToolResult> {
    const tool = "own_helper_stop";
    try {
      await stopShellHelper(this.cfg.adbPath, this.cfg.adbSerial);
      this.audit.record({ kind: "safety", tool, ok: true });
      return { ok: true, data: { stopped: true } };
    } catch (err) {
      const error = errorMessage(err);
      this.audit.record({ kind: "cmd", tool, ok: false, error });
      return { ok: false, error };
    }
  }

  /** Mirror a suggestion locally and forward it to the phone (the source of truth). */
  private suggest(args: ToolArgs): ToolResult {
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
      category: isSuggestionCategory(args.category) ? args.category : undefined,
      priority: typeof args.priority === "number" ? args.priority : undefined,
      context: args.context ? String(args.context) : undefined,
      source: "agent",
    });
    // Best-effort on-device persistence; ignore if the app lacks the handler.
    this.relay.dispatch("store_suggestion", { ...entry }).catch(() => {});
    this.audit.record({ kind: "suggestion", id: entry.id, title: entry.title });
    return { ok: true, data: entry };
  }
}
