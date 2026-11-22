import type { ToolArea } from "./types.js";

export const safety: ToolArea = {
  id: "safety",
  title: "Safety",
  summary: "Audit trail and kill switch.",
  tools: [
    { name: "get_audit_log", tier: "standard", confirm: false, local: true,
      description: "Return the path to the append-only audit log.", input: {} },
    { name: "panic_disconnect", tier: "standard", confirm: false, local: true,
      description: "Immediately sever the phone session and any ADB session (debugging is turned off, even on adb mode 'always') and stop the agent service on the phone; it stays off until someone reopens it in the DroidHand app on the phone. Without device: every phone.", input: {} },
  ],
};
