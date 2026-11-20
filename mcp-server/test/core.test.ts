import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Core } from "../src/core.js";
import { startMockPhone, type MockPhone } from "../src/mock-phone.js";
import type { Config } from "../src/config.js";

async function waitFor(predicate: () => boolean, timeoutMs = 1000): Promise<void> {
  const start = Date.now();
  while (!predicate()) {
    if (Date.now() - start > timeoutMs) throw new Error("waitFor timed out");
    await new Promise((r) => setTimeout(r, 10));
  }
}

function tmpCfg(overrides: Partial<Config> = {}): Config {
  const dir = mkdtempSync(join(tmpdir(), "droidhand-test-"));
  return {
    relayHost: "127.0.0.1",
    relayPort: 0, // ephemeral
    token: "test-token",
    auditDir: join(dir, "audit"),
    suggestionsDir: join(dir, "suggestions"),
    cmdTimeoutMs: 3000,
    ...overrides,
  };
}

async function withCore(
  cfg: Config,
  fn: (core: Core, url: string) => Promise<void>,
): Promise<void> {
  const core = new Core(cfg);
  await core.start();
  const addr = core.relay.address();
  assert.ok(addr, "relay should be listening");
  const url = `ws://127.0.0.1:${addr!.port}`;
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
