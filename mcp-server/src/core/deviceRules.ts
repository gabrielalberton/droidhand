/**
 * What an agent must do differently on each phone, decided only from the model and
 * Android version the phone reports in its hello (no guessing, no network).
 *
 * Every rule comes from a test on a real phone (docs/devices.md). Versions nobody tested
 * get the cautious rule and say so in `basis`, so an agent never relies on behaviour
 * that was only seen on another version.
 */

export interface DeviceRule {
  /** Stable id, so an agent or a test can look a rule up. */
  id: string;
  /** What to do (or avoid), in one or two sentences. */
  rule: string;
  /** Where the rule comes from: "tested …" or "untested on SDK n: assumed …". */
  basis: string;
}

/** Newest SDK where moving an app between screens was seen to recreate it (Galaxy S20). */
const RECREATES_UP_TO = 33;
/** Oldest SDK where moving was seen to keep the app running (Galaxy S24 Ultra). */
const KEEPS_FROM = 37;

const S20 = "tested on Galaxy S20 SM-G980F, SDK 33";
const S24 = "tested on Galaxy S24 Ultra SM-S928B, SDK 37";

/** Samsung models report `SM-…` as Build.MODEL. */
export function isSamsung(model: string | undefined): boolean {
  return /^SM-/i.test(model ?? "");
}

export function deviceRules(model: string | undefined, sdk: number | undefined): DeviceRule[] {
  const rules: DeviceRule[] = [];
  const known = typeof sdk === "number" && sdk > 0;

  // Moving between the background display and the main screen.
  if (known && sdk >= KEEPS_FROM) {
    rules.push({
      id: "move_keeps_app",
      rule: "launch_app move=true (either direction) and bg_close keep the app running: open forms and pages survive the move.",
      basis: `${S24} (Settings, Calendar, Contacts form, Calculator; 2026-11-21/23)`,
    });
  } else {
    rules.push({
      id: "move_recreates_app",
      rule: "Bringing an app from the background display to the main screen (launch_app move=true display=0, bg_close) recreates its screen, " +
        "and so does moving one from the main screen to the own window: background displays report no touchscreen, and this Android " +
        "restarts the activity when that changes. Plain text fields usually " +
        "survive; web pages, WebView forms and unsaved custom screens can be lost. Finish or save a form on the screen where it was started, " +
        "or tell the user before moving it. A bigger or smaller background display does not avoid this.",
      basis: known && sdk <= RECREATES_UP_TO
        ? `${S20} (6 of 6 apps recreated, native and own window, 2026-11-23)`
        : `untested on ${known ? `SDK ${sdk}` : "an unknown Android version"}: assumed like SDK ${RECREATES_UP_TO}, the cautious case`,
    });
  }

  if (known && sdk <= RECREATES_UP_TO) {
    rules.push({
      id: "native_window_not_accessible",
      rule: "Use bg_open mode='own' (or 'auto' with ADB available). On the native window only launch_app, open_url and screen_capture work; " +
        "screen_snapshot, tap, type_text and the other screen tools answer native_window_not_accessible.",
      basis: `${S20}: Android 13 leaves overlay displays out of accessibility`,
    });
    if (isSamsung(model)) {
      rules.push({
        id: "background_apps_freeform",
        rule: "Apps on a background display open as pop-up (freeform) windows with a caption bar, not full screen.",
        basis: S20,
      });
    }
  }

  rules.push({
    id: "native_window_debug_text",
    rule: "The native background window always shows a debug title ('Overlay #1: WxH, dpi') over the top of the app; " +
      "read it with screen_snapshot instead of relying on the top of a screen_capture.",
    basis: `${S20}; ${S24}`,
  });

  if (isSamsung(model)) {
    rules.push({
      id: "native_close_lands_on_top",
      rule: "bg_close on a native window moves its apps to the main screen on top of the user's app: close or move them first unless the user wants them.",
      basis: `${S20}; ${S24}`,
    });
  }
  return rules;
}
