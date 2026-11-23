import { randomBytes } from "node:crypto";

/**
 * One-time setup links: `http://<relay>/setup/<code>` opens a page on the new phone
 * with the download link, the steps and an "Open in DroidHand" button that hands
 * the app its relay address and token. The code is random (128 bits), lives in
 * memory only (a relay restart forgets it), expires after [ttlMs] and is spent as
 * soon as the phone it was made for connects with its token.
 */
export interface SetupEntry {
  device: string;
  name: string;
  token: string;
  relayUrl: string;
  expiresAt: number;
}

export const SETUP_TTL_MS = 24 * 3600_000;
export const SETUP_CODE = /^[A-Za-z0-9_-]{16,64}$/;

export class SetupLinks {
  private readonly entries = new Map<string, SetupEntry>();

  constructor(private readonly ttlMs = SETUP_TTL_MS, private readonly now = () => Date.now()) {}

  create(e: Omit<SetupEntry, "expiresAt">): { code: string; expiresAt: string } {
    this.sweep();
    const code = randomBytes(16).toString("base64url");
    const expiresAt = this.now() + this.ttlMs;
    this.entries.set(code, { ...e, expiresAt });
    return { code, expiresAt: new Date(expiresAt).toISOString() };
  }

  get(code: string): SetupEntry | null {
    if (!SETUP_CODE.test(code)) return null;
    const e = this.entries.get(code);
    if (!e) return null;
    if (this.now() >= e.expiresAt) { this.entries.delete(code); return null; }
    return e;
  }

  /** [device] connected with its token: its links are no longer needed. */
  spend(device: string): void {
    for (const [c, e] of this.entries) if (e.device === device) this.entries.delete(c);
  }

  private sweep(): void {
    const t = this.now();
    for (const [c, e] of this.entries) if (t >= e.expiresAt) this.entries.delete(c);
  }
}
