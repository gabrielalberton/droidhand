import { appendFileSync, mkdirSync, readFileSync, existsSync } from "node:fs";
import { randomUUID } from "node:crypto";
import { join } from "node:path";

/** Allowed values of `suggest_feature.category`. */
export const SUGGESTION_CATEGORIES = ["feature", "capability", "harness", "optimization", "routine", "other"] as const;

export type SuggestionCategory = (typeof SUGGESTION_CATEGORIES)[number];

export function isSuggestionCategory(v: unknown): v is SuggestionCategory {
  return typeof v === "string" && (SUGGESTION_CATEGORIES as readonly string[]).includes(v);
}

/** One logged suggestion (a line of `suggestions.jsonl`). */
export interface Suggestion {
  id: string;
  ts: string;
  title: string;
  detail: string;
  category: SuggestionCategory;
  /** 1 (nice-to-have) .. 5 (high impact). */
  priority: number;
  /** Where it came from, e.g. the agent/task name. */
  source?: string;
  /** Free-form context: the app/screen/task in flight when the idea came up. */
  context?: string;
}

/** Input to {@link SuggestionStore.add}; defaults fill the rest. */
export interface NewSuggestion {
  title: string;
  detail: string;
  category?: SuggestionCategory;
  priority?: number;
  source?: string;
  context?: string;
}

/**
 * Host-side mirror of the feature-suggestion log. The on-device app is the source
 * of truth and owns the default-OFF toggle; this mirror makes suggestions easy to
 * read and export (and gives forkers the same benefit out of the box).
 */
export class SuggestionStore {
  private readonly file: string;
  constructor(dir: string) {
    mkdirSync(dir, { recursive: true });
    this.file = join(dir, "suggestions.jsonl");
  }

  /** Append a suggestion (default category "feature", priority clamped to 1..5, default 3). */
  add(s: NewSuggestion): Suggestion {
    const entry: Suggestion = {
      id: randomUUID(),
      ts: new Date().toISOString(),
      title: s.title.trim(),
      detail: s.detail.trim(),
      category: s.category ?? "feature",
      priority: clampPriority(s.priority),
      source: s.source,
      context: s.context,
    };
    appendFileSync(this.file, JSON.stringify(entry) + "\n");
    return entry;
  }

  /** All logged suggestions, oldest first. */
  list(): Suggestion[] {
    if (!existsSync(this.file)) return [];
    return readFileSync(this.file, "utf8")
      .split("\n")
      .filter((l) => l.trim().length > 0)
      .map((l) => JSON.parse(l) as Suggestion);
  }

  /** Render a shareable Markdown digest the operator can review and commit. */
  exportMarkdown(): string {
    const items = this.list().sort((a, b) => b.priority - a.priority || a.ts.localeCompare(b.ts));
    const lines: string[] = [
      "# DroidHand — Feature suggestions",
      "",
      "> Auto-collected by agents while using the device. Review before acting; nothing here is committed automatically.",
      "",
    ];
    if (items.length === 0) {
      lines.push("_No suggestions logged yet._");
      return lines.join("\n");
    }
    const byCat = new Map<string, Suggestion[]>();
    for (const s of items) {
      const arr = byCat.get(s.category) ?? [];
      arr.push(s);
      byCat.set(s.category, arr);
    }
    for (const [cat, arr] of byCat) {
      lines.push(`## ${cat}`, "");
      for (const s of arr) {
        lines.push(`- **[P${s.priority}] ${s.title}** — ${s.detail}` + (s.context ? `  _(context: ${s.context})_` : ""));
      }
      lines.push("");
    }
    return lines.join("\n");
  }
}

function clampPriority(p?: number): number {
  if (typeof p !== "number" || Number.isNaN(p)) return 3;
  return Math.max(1, Math.min(5, Math.round(p)));
}
