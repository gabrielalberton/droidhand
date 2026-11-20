import { startMockPhone } from "./mock-phone.js";

/** Manual runner: DROIDHAND_RELAY_* + DROIDHAND_TOKEN env, optional SUGGESTION_LOGGING=1. */
const host = process.env.DROIDHAND_RELAY_HOST ?? "127.0.0.1";
const port = process.env.DROIDHAND_RELAY_PORT ?? "8787";
const token = process.env.DROIDHAND_TOKEN ?? "";
const suggestionLogging = process.env.SUGGESTION_LOGGING === "1";

startMockPhone({ url: `ws://${host}:${port}`, token, device: { suggestionLogging } })
  .then(() => process.stderr.write(`[mock-phone] connected to ws://${host}:${port} (suggestionLogging=${suggestionLogging})\n`))
  .catch((err) => {
    process.stderr.write(`[mock-phone] failed: ${err}\n`);
    process.exit(1);
  });
