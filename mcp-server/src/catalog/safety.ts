import type { ToolArea } from "./types.js";

export const safety: ToolArea = {
  id: "safety",
  title: "Safety",
  summary: "Audit trail and kill switch.",
  tools: [
    { name: "get_audit_log", tier: "standard", confirm: false, local: true,
      description: "Return the path to the append-only audit log.", input: {} },
    { name: "panic_disconnect", tier: "standard", confirm: false, local: true,
      description: "Stop at once: cancel the running action, close the background window, end the ADB session (debugging off, except on adb mode 'always') and drop the phone's connection; the phone reconnects by itself a few seconds later. It never switches the app off (only the user can, on the phone). Without device: every phone.", input: {} },
  ],
};
