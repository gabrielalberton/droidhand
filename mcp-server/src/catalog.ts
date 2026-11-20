import { z, type ZodRawShape } from "zod";
import type { Tier } from "./protocol.js";

/**
 * Declarative tool catalog. Most tools are forwarded verbatim to the phone; the
 * `local` ones (suggestions, audit, panic) are handled on the NUC by the Core.
 *
 * `confirm: true` means the action requires an on-device confirmation tap (the
 * app enforces it); the server records the requirement in the audit log.
 */
export interface ToolDef {
  name: string;
  tier: Tier;
  confirm: boolean;
  /** Handled on the NUC (true) vs forwarded to the phone (false/undefined). */
  local?: boolean;
  description: string;
  input: ZodRawShape;
}

const ref = z.string().describe("stable element ref from screen_snapshot, e.g. '@4'");
const xy = { x: z.number(), y: z.number() };

export const CATALOG: ToolDef[] = [
  // ---- Perception (standard) ----
  { name: "screen_snapshot", tier: "standard", confirm: false,
    description: "Accessibility tree of the foreground app with stable @refs, roles, text and bounds.",
    input: { detail: z.enum(["compact", "full"]).optional() } },
  { name: "screen_capture", tier: "standard", confirm: false,
    description: "PNG screenshot of the current screen (base64).",
    input: { quality: z.number().min(1).max(100).optional() } },
  { name: "get_foreground_app", tier: "standard", confirm: false,
    description: "Package and activity currently in the foreground.", input: {} },
  { name: "get_notifications", tier: "standard", confirm: false,
    description: "List active notifications with refs and actions.",
    input: { package: z.string().optional() } },
  { name: "list_apps", tier: "standard", confirm: false,
    description: "Installed launchable apps (personal profile).",
    input: { includeSystem: z.boolean().optional() } },

  // ---- Interaction (standard, via AccessibilityService) ----
  { name: "tap", tier: "standard", confirm: false,
    description: "Tap an element by ref, or raw x,y.",
    input: { ref: ref.optional(), ...partial(xy) } },
  { name: "long_press", tier: "standard", confirm: false,
    description: "Long-press an element or point.",
    input: { ref: ref.optional(), ...partial(xy), durationMs: z.number().optional() } },
  { name: "swipe", tier: "standard", confirm: false,
    description: "Swipe from one point to another.",
    input: { fromX: z.number(), fromY: z.number(), toX: z.number(), toY: z.number(), durationMs: z.number().optional() } },
  { name: "scroll", tier: "standard", confirm: false,
    description: "Scroll a scrollable container in a direction.",
    input: { direction: z.enum(["up", "down", "left", "right"]), ref: ref.optional() } },
  { name: "type_text", tier: "standard", confirm: false,
    description: "Type text into the focused field (or a ref), optionally pressing enter.",
    input: { text: z.string(), ref: ref.optional(), submit: z.boolean().optional() } },
  { name: "key_event", tier: "standard", confirm: false,
    description: "Press a hardware/navigation key.",
    input: { key: z.enum(["back", "home", "recents", "volume_up", "volume_down", "power", "enter"]) } },
  { name: "global_action", tier: "standard", confirm: false,
    description: "Perform a global accessibility action.",
    input: { action: z.enum(["notifications", "quick_settings", "clear_all_notifications", "screenshot", "lock_screen"]) } },

  // ---- App & navigation ----
  { name: "launch_app", tier: "standard", confirm: false,
    description: "Launch an app by package name.", input: { package: z.string() } },
  { name: "open_url", tier: "standard", confirm: false,
    description: "Open a URL in the default browser.", input: { url: z.string().url() } },
  { name: "go_home", tier: "standard", confirm: false, description: "Go to the home screen.", input: {} },

  // ---- Device state & settings ----
  { name: "get_device_info", tier: "standard", confirm: false,
    description: "Model, Android version, battery, network.", input: {} },
  { name: "get_setting", tier: "standard", confirm: false,
    description: "Read a system setting.",
    input: { namespace: z.enum(["system", "secure", "global"]), key: z.string() } },
  { name: "set_setting", tier: "privileged", confirm: true,
    description: "Write a protected setting (requires WRITE_SECURE_SETTINGS on the phone).",
    input: { namespace: z.enum(["system", "secure", "global"]), key: z.string(), value: z.string() } },

  // ---- ADB-on-demand (privileged, confirm) ----
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

  // ---- Feature suggestions (local, default-OFF on device) ----
  { name: "suggest_feature", tier: "standard", confirm: false, local: true,
    description:
      "Log an idea for a future DroidHand feature/capability/harness/optimization/routine that would speed up future work. " +
      "No-op unless the operator enabled suggestion logging in the app (OFF by default).",
    input: {
      title: z.string(),
      detail: z.string(),
      category: z.enum(["feature", "capability", "harness", "optimization", "routine", "other"]).optional(),
      priority: z.number().min(1).max(5).optional(),
      context: z.string().optional(),
    } },
  { name: "list_suggestions", tier: "standard", confirm: false, local: true,
    description: "List logged feature suggestions.", input: {} },
  { name: "export_suggestions", tier: "standard", confirm: false, local: true,
    description: "Render the suggestions as a Markdown digest for review.", input: {} },

  // ---- Safety (local) ----
  { name: "get_audit_log", tier: "standard", confirm: false, local: true,
    description: "Return the path to the append-only audit log.", input: {} },
  { name: "panic_disconnect", tier: "standard", confirm: false, local: true,
    description: "Immediately sever the phone session and any ADB session.", input: {} },
];

export const CATALOG_BY_NAME = new Map(CATALOG.map((t) => [t.name, t]));

/** Make x/y optional while keeping the shape typed. */
function partial(shape: typeof xy): ZodRawShape {
  return { x: shape.x.optional(), y: shape.y.optional() };
}
