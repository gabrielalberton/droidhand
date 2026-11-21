import { readFileSync } from "node:fs";

/** Package version, read from package.json (one level above both src/ and dist/). */
export const VERSION: string = (() => {
  try {
    const pkg: unknown = JSON.parse(readFileSync(new URL("../package.json", import.meta.url), "utf8"));
    const v = typeof pkg === "object" && pkg !== null ? (pkg as { version?: unknown }).version : undefined;
    return typeof v === "string" ? v : "0.0.0";
  } catch {
    return "0.0.0";
  }
})();
