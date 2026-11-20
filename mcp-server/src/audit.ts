import { appendFileSync, mkdirSync } from "node:fs";
import { join } from "node:path";

const SECRET_KEYS = /token|secret|password|passwd|pin|otp|cred|apikey|api_key|key/i;

/** Mask obvious secrets and truncate long blobs before anything is written to the audit log. */
export function redact(v: unknown): unknown {
  if (Array.isArray(v)) return v.map(redact);
  if (v && typeof v === "object") {
    const out: Record<string, unknown> = {};
    for (const [k, val] of Object.entries(v as Record<string, unknown>)) {
      out[k] = SECRET_KEYS.test(k) ? "***" : redact(val);
    }
    return out;
  }
  if (typeof v === "string" && v.length > 500) return v.slice(0, 500) + "…";
  return v;
}

/** Append-only JSONL audit sink. */
export class Audit {
  private readonly file: string;
  constructor(dir: string) {
    mkdirSync(dir, { recursive: true });
    this.file = join(dir, "audit.jsonl");
  }
  record(entry: Record<string, unknown>): void {
    const line = JSON.stringify({ ts: new Date().toISOString(), ...entry }) + "\n";
    appendFileSync(this.file, line);
  }
  path(): string {
    return this.file;
  }
}
