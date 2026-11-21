import { z } from "zod";
import type { ToolArea } from "./types.js";

export const adb: ToolArea = {
  id: "adb",
  title: "ADB on demand",
  summary: "Short-lived, user-confirmed ADB sessions for privileged work.",
  tools: [
    { name: "adb_session_begin", tier: "privileged", confirm: true,
      description: "Enable wireless debugging and open a short-lived local ADB session. Auto-closes on TTL.",
      input: { reason: z.string(), ttlSeconds: z.number().min(10).max(1800).optional() } },
    { name: "adb_shell", tier: "privileged", confirm: true,
      description: "Run a shell command inside an open ADB session.",
      input: { command: z.string() } },
    { name: "adb_install", tier: "privileged", confirm: true,
      description: "Install an APK already present on the phone.",
      input: { apkPath: z.string() } },
    { name: "pm_grant", tier: "privileged", confirm: true,
      description: "Grant a runtime permission to a package.",
      input: { package: z.string(), permission: z.string() } },
    { name: "adb_session_end", tier: "privileged", confirm: false,
      description: "Close the ADB session and disable wireless debugging.", input: {} },
  ],
};
