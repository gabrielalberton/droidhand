import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { z } from "zod";
import { CATALOG } from "../src/catalog.js";
import { describeType, renderToolsDoc, TOOLS_DOC_PATH } from "../scripts/gen-tools-doc.js";

test("describeType renders zod schemas readably", () => {
  assert.equal(describeType(z.string()), "string");
  assert.equal(describeType(z.string().url()), "string (URL)");
  assert.equal(describeType(z.number().min(1).max(5).optional()), "number 1–5");
  assert.equal(describeType(z.number().int().min(0)), "integer ≥ 0");
  assert.equal(describeType(z.boolean()), "boolean");
  assert.equal(describeType(z.enum(["a", "b"])), '"a" | "b"');
  assert.equal(describeType(z.array(z.number()).length(4)), "number[4]");
});

test("docs/tools.md is generated from the current catalog (run npm run docs:tools)", () => {
  const doc = renderToolsDoc();
  for (const t of CATALOG) assert.ok(doc.includes(`| \`${t.name}\` |`), `${t.name} documented`);
  assert.equal(readFileSync(TOOLS_DOC_PATH, "utf8"), doc);
});
