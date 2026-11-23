/**
 * The agent may turn things off, but never in a way it cannot turn back on: the app is
 * the agent's only way back in (it reconnects to the relay and turns debugging on again).
 * adb_shell commands that would cut that path are refused. Only the user, on the phone,
 * may switch the agent off for good. A heuristic for honest mistakes, not a sandbox.
 */

const APP = "work.alberton.droidhand";
/** Apps the connection depends on (the relay is reached over the tailnet). */
const LIFELINES = [APP, "com.tailscale.ipn"];

const RULES: Array<[RegExp, string]> = [
  [new RegExp(`\\b(force-stop|kill|crash|disable(-user)?|uninstall|clear|suspend|hide|set-inactive|set-standby-bucket|revoke|reset-permissions)\\b[^;&|]*\\b(${LIFELINES.map((p) => p.replace(/\./g, "\\.")).join("|")})\\b`),
    "stops, disables, removes or strips permissions from the DroidHand app or Tailscale"],
  [new RegExp(`\\bappops\\s+set\\b[^;&|]*\\b${APP.replace(/\./g, "\\.")}\\b[^;&|]*\\b(ignore|deny|errored)\\b`),
    "denies an app-op to the DroidHand app"],
  [/\bsettings\s+(put|delete)\s+global\s+(adb_enabled|adb_wifi_enabled|development_settings_enabled)\b/,
    "turns debugging off behind the relay's back (use adb_session_end, which follows the phone's adb mode)"],
  [/\bsettings\s+(put|delete)\s+secure\s+(enabled_accessibility_services|accessibility_enabled)\b/,
    "changes the enabled accessibility services (DroidHand runs as one)"],
  [/\bsettings\s+put\s+global\s+airplane_mode_on\s+1\b|\bcmd\s+connectivity\s+airplane-mode\s+enable\b/,
    "turns airplane mode on"],
  [/\bsvc\s+(wifi|data)\s+disable\b|\bcmd\s+wifi\s+set-wifi-enabled\s+disabled\b/,
    "turns the network off"],
  [/(^|[;&|]\s*|\bsu\s+-c\s+['"]?)\s*(reboot|shutdown)\b|\bsvc\s+power\s+(reboot|shutdown)\b|\bsetprop\s+sys\.powerctl\b/,
    "reboots or shuts the phone down (after a reboot the app waits for the user to unlock)"],
];

/** Why [command] would lock the agent out of the phone, or null when it is fine. */
export function lockoutReason(command: string): string | null {
  const c = command.toLowerCase();
  for (const [re, why] of RULES) if (re.test(c)) return why;
  return null;
}

export function lockoutError(why: string): string {
  return `lockout_guard: refused, this command ${why}, which would leave the agent no way back to the phone. ` +
    "Only the user can switch the agent off for good (on the phone). If the user explicitly asked for it, tell them to do it on the phone.";
}
