import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { Core } from "../src/core/core.js";
import { startMockPhone, type MockPhone } from "../src/dev/mock-phone.js";
import type { Config } from "../src/config.js";
import { tmpConfig as tmpCfg, waitFor } from "./helpers/env.js";

async function withCore(
  cfg: Config,
  fn: (core: Core, url: string) => Promise<void>,
): Promise<void> {
  const core = new Core(cfg);
  await core.start();
  const addr = core.relay.address();
  assert.ok(addr, "relay should be listening");
  const url = `ws://127.0.0.1:${addr.port}`;
  try {
    await fn(core, url);
  } finally {
    await core.stop();
  }
}

test("forwards a tool call to the phone and returns its result", async () => {
  const cfg = tmpCfg();
  await withCore(cfg, async (core, url) => {
    const phone = await startMockPhone({ url, token: cfg.token });
    const res = await core.callTool("tap", { x: 10, y: 20 });
    assert.equal(res.ok, true);
    assert.deepEqual(res.data, { tapped: { x: 10, y: 20 } });
    await phone.close();
  });
});

test("suggest_feature is rejected when device logging is OFF (default)", async () => {
  const cfg = tmpCfg();
  await withCore(cfg, async (core, url) => {
    const phone = await startMockPhone({ url, token: cfg.token }); // default: OFF
    const res = await core.callTool("suggest_feature", { title: "x", detail: "y" });
    assert.equal(res.ok, false);
    assert.match(res.error ?? "", /OFF/);
    await phone.close();
  });
});

test("suggest_feature stores and exports when device logging is ON", async () => {
  const cfg = tmpCfg();
  await withCore(cfg, async (core, url) => {
    const phone: MockPhone = await startMockPhone({
      url,
      token: cfg.token,
      device: { suggestionLogging: true },
    });
    const res = await core.callTool("suggest_feature", {
      title: "Add OTP autofill routine",
      detail: "When a login screen is detected, offer to pull the latest SMS OTP.",
      category: "routine",
      priority: 5,
      context: "login flow",
    });
    assert.equal(res.ok, true);

    const list = await core.callTool("list_suggestions", {});
    assert.equal(list.ok, true);
    assert.equal((list.data as unknown[]).length, 1);

    const md = await core.callTool("export_suggestions", {});
    assert.match(md.data as string, /OTP autofill routine/);

    // The server also forwarded the suggestion to the device for on-device storage
    // (best-effort / not awaited by design, so wait for it to propagate).
    await waitFor(() => phone.storedSuggestions.length === 1);
    await phone.close();
  });
});

test("a phone with the wrong token cannot drive tools", async () => {
  const cfg = tmpCfg();
  await withCore(cfg, async (core, url) => {
    await startMockPhone({ url, token: "WRONG" }).catch(() => undefined);
    // Give the relay a moment to reject the bad session.
    await new Promise((r) => setTimeout(r, 150));
    const res = await core.callTool("tap", { x: 1, y: 1 });
    assert.equal(res.ok, false);
    assert.match(res.error ?? "", /no device connected/);
  });
});

test("panic_disconnect severs the session", async () => {
  const cfg = tmpCfg();
  await withCore(cfg, async (core, url) => {
    const phone = await startMockPhone({ url, token: cfg.token });
    assert.equal(core.relay.connected, true);
    const res = await core.callTool("panic_disconnect", {});
    assert.equal(res.ok, true);
    assert.equal(core.relay.connected, false);
    await phone.close().catch(() => undefined);
  });
});

test("toggling suggestion logging on the device takes effect without reconnecting", async () => {
  const cfg = tmpCfg();
  await withCore(cfg, async (core, url) => {
    const phone = await startMockPhone({ url, token: cfg.token }); // starts OFF
    const off = await core.callTool("suggest_feature", { title: "a", detail: "b" });
    assert.equal(off.ok, false);
    phone.sendEvent("device_info", { suggestionLogging: true });
    await waitFor(() => core.relay.deviceInfo?.suggestionLogging === true);
    const on = await core.callTool("suggest_feature", { title: "a", detail: "b" });
    assert.equal(on.ok, true);
    await phone.close();
  });
});

test("unknown tools are rejected without reaching the phone", async () => {
  const cfg = tmpCfg();
  await withCore(cfg, async (core) => {
    const res = await core.callTool("no_such_tool", {});
    assert.deepEqual(res, { ok: false, error: "unknown tool 'no_such_tool'" });
  });
});

test("phone errors are returned verbatim and audited with redacted args", async () => {
  const cfg = tmpCfg();
  await withCore(cfg, async (core, url) => {
    const phone = await startMockPhone({
      url,
      token: cfg.token,
      handlers: {
        type_text: () => {
          throw new Error("field not editable");
        },
      },
    });
    const res = await core.callTool("type_text", { text: "hello", pin: "1234" });
    assert.equal(res.ok, false);
    assert.match(res.error ?? "", /field not editable/);

    const audit = readFileSync(core.audit.path(), "utf8").trim().split("\n").map((l) => JSON.parse(l) as Record<string, unknown>);
    const request = audit.find((e) => e.tool === "type_text" && e.phase === "request");
    assert.ok(request, "request was audited");
    assert.deepEqual(request.args, { text: "hello", pin: "***" });
    assert.ok(audit.some((e) => e.tool === "type_text" && e.ok === false));
    await phone.close();
  });
});

test("suggest_feature ignores an unknown category and clamps priority", async () => {
  const cfg = tmpCfg();
  await withCore(cfg, async (core, url) => {
    const phone = await startMockPhone({ url, token: cfg.token, device: { suggestionLogging: true } });
    const res = await core.callTool("suggest_feature", { title: " t ", detail: " d ", category: "bogus", priority: 9 });
    assert.equal(res.ok, true);
    const entry = res.data as { title: string; category: string; priority: number; source: string };
    assert.equal(entry.title, "t");
    assert.equal(entry.category, "feature");
    assert.equal(entry.priority, 5);
    assert.equal(entry.source, "agent");
    await phone.close();
  });
});
