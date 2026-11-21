import { test } from "node:test";
import assert from "node:assert/strict";
import { Core } from "../src/core/core.js";
import { startControlServer } from "../src/control/server.js";
import { ControlClient } from "../src/control/client.js";
import { startMockPhone } from "../src/dev/mock-phone.js";
import { tmpConfig } from "./helpers/env.js";

const tmpCfg = () => tmpConfig({ token: "daemon-token" });

test("adapter → daemon → phone round-trip works, and the control token is enforced", async () => {
  const cfg = tmpCfg();
  const core = new Core(cfg);
  await core.start();
  const ctrl = await startControlServer(core, cfg.controlHost, cfg.controlPort, cfg.token);

  const relayPort = core.relay.address()!.port;
  const controlPort = ctrl.address()!.port;

  const phone = await startMockPhone({
    url: `ws://127.0.0.1:${relayPort}`,
    token: cfg.token,
    device: { suggestionLogging: true },
  });

  try {
    // Correct token: full round-trip.
    const good = new ControlClient(`ws://127.0.0.1:${controlPort}`, cfg.token);
    const tap = await good.call("tap", { x: 5, y: 6 });
    assert.equal(tap.ok, true);
    assert.deepEqual(tap.data, { tapped: { x: 5, y: 6 } });

    const sugg = await good.call("suggest_feature", {
      title: "Batch-dismiss by app",
      detail: "A routine to clear all notifications from one package.",
      category: "routine",
    });
    assert.equal(sugg.ok, true);
    good.close();

    // Wrong token: the control channel rejects the adapter.
    const bad = new ControlClient(`ws://127.0.0.1:${controlPort}`, "WRONG");
    await assert.rejects(() => bad.call("tap", { x: 0, y: 0 }));
    bad.close();
  } finally {
    await phone.close();
    await ctrl.close();
    await core.stop();
  }
});
