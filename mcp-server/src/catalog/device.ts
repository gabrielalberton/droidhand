import { z } from "zod";
import { settingNamespace, type ToolArea } from "./types.js";

export const device: ToolArea = {
  id: "device",
  title: "Device state & settings",
  summary: "Device info and system settings.",
  tools: [
    { name: "get_device_info", tier: "standard", confirm: false,
      description: "Model, Android version, battery, network.", input: {} },
    { name: "get_setting", tier: "standard", confirm: false,
      description: "Read a system setting.",
      input: { namespace: settingNamespace, key: z.string() } },
    { name: "set_setting", tier: "privileged", confirm: true,
      description: "Write a protected setting (requires WRITE_SECURE_SETTINGS on the phone).",
      input: { namespace: settingNamespace, key: z.string(), value: z.string() } },
  ],
};

export const lock: ToolArea = {
  id: "lock",
  title: "Lock screen",
  summary: "Lock state and opt-in PIN unlock (the PIN never leaves the phone).",
  tools: [
    { name: "get_lock_state", tier: "standard", confirm: false,
      description: "Screen on/off, locked, secure lock, and whether PIN unlock by the agent is enabled.", input: {} },
    { name: "unlock_device", tier: "privileged", confirm: false,
      description: "Unlock the phone with the PIN the user saved in the DroidHand app (opt-in, off by default; the PIN never leaves the phone). " +
        "Wakes the screen, opens the PIN pad and enters it. One wrong attempt disables the feature. Only when the task needs the main screen.",
      input: {} },
  ],
};
