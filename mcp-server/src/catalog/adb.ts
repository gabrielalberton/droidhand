import { z } from "zod";
import type { ToolArea } from "./types.js";

export const adb: ToolArea = {
  id: "adb",
  title: "ADB on demand",
  summary: "Debugging stays off; short, time-limited ADB sessions for privileged work.",
  tools: [
    { name: "adb_session_begin", tier: "privileged", confirm: false, local: true,
      description:
        "Turn USB debugging on and connect this computer to the phone over the tailnet (works on mobile data). " +
        "Debugging is OFF the rest of the time: open a session only when a task really needs adb (e.g. `pm grant`, " +
        "`pm install`, `dumpsys`) and ALWAYS call adb_session_end as soon as you are done. Fallback only: the session " +
        "closes by itself 10 minutes after your last tool call (each call renews it), so a lost connection never leaves " +
        "debugging on. The phone shows a 'Turn ADB off' notification meanwhile. The first session after a reboot needs " +
        "the phone on any Wi-Fi (error 'adb_not_armed' otherwise). Test phones set to adb mode \"always\" (see list_devices) " +
        "only connect: their debugging is never turned off.",
      input: { reason: z.string().describe("why adb is needed, shown in the audit log") } },
    { name: "adb_shell", tier: "privileged", confirm: false, local: true,
      description: "Run a shell command (as the adb shell user) in the open ADB session. Returns stdout, stderr and exitCode. Commands that would leave the agent no way back to the phone (stopping, disabling or removing DroidHand or Tailscale, turning debugging, accessibility or the network off, rebooting) are refused with lockout_guard: use adb_session_end to turn debugging off.",
      input: { command: z.string(), timeoutSeconds: z.number().min(1).max(300).optional() } },
    { name: "adb_session_end", tier: "standard", confirm: false, local: true,
      description: "Close the ADB session and turn debugging off on the phone right away (also stops the own-window helper). " +
        "Call it as soon as the adb work is done.", input: {} },
  ],
};
