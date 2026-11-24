import { z } from "zod";
import { settingNamespace, type ToolArea } from "./types.js";

export const device: ToolArea = {
  id: "device",
  title: "Device state & settings",
  summary: "Device info and system settings.",
  tools: [
    { name: "list_devices", tier: "standard", confirm: false, local: true,
      description: "The phones this relay knows, with id, name, connected or not, model, app version, adb mode and 'rules' " +
        "(what to do differently on that phone, from its model and Android version; read them before using background windows). " +
        "When more than one phone is connected, pass device=<id> to every other tool.", input: {} },
    { name: "get_device_info", tier: "standard", confirm: false,
      description: "Model, Android version, battery, network, and the 'rules' for this phone (same as in list_devices).", input: {} },
    { name: "get_setting", tier: "standard", confirm: false,
      description: "Read a system setting.",
      input: { namespace: settingNamespace, key: z.string() } },
    { name: "set_setting", tier: "privileged", confirm: false,
      description: "Write a protected setting (requires WRITE_SECURE_SETTINGS on the phone). Only an allowlist is writable: " +
        "global stay_on_while_plugged_in and the three animation scales. Debugging is not writable: use adb_session_begin.",
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
