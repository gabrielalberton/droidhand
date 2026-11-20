#!/usr/bin/env bash
# Install/update the DroidHand Portal app on a phone over adb and (re)apply the
# one-time grants. Android disables an app's accessibility service when the app is
# updated, so this script re-enables it every time, appending to (never replacing)
# the services already enabled.
# Usage: deploy/install-phone.sh [adb-serial] [apk]
set -euo pipefail
ADB="${ADB:-adb}"
SERIAL="${1:-}"
APK="${2:-$(dirname "$0")/../app/portal/build/outputs/apk/debug/portal-debug.apk}"
PKG=work.alberton.droidhand
SVC="$PKG/$PKG.a11y.DroidHandAccessibilityService"
A=("$ADB"); [[ -n "$SERIAL" ]] && A+=(-s "$SERIAL")

"${A[@]}" install -r "$APK"
"${A[@]}" shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS
"${A[@]}" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
# An update kills the bound service and Android marks it "crashed" (never rebound).
# Remove it from the enabled list, then add it back, keeping the other services.
cur="$("${A[@]}" shell settings get secure enabled_accessibility_services | tr -d '\r')"
[[ "$cur" == "null" ]] && cur=""
others="$(printf '%s' "$cur" | tr ':' '\n' | grep -v 'DroidHandAccessibilityService' | grep -v '^$' | paste -sd: - || true)"
"${A[@]}" shell settings put secure enabled_accessibility_services "${others:-null}"
sleep 1
"${A[@]}" shell settings put secure enabled_accessibility_services "${others:+$others:}$SVC"
"${A[@]}" shell settings put secure accessibility_enabled 1
# The relay service starts with the accessibility service, so the app is only
# opened on request (OPEN=1), to avoid interrupting whatever is on screen.
if [[ "${OPEN:-0}" == "1" ]]; then "${A[@]}" shell am start -n "$PKG/.ui.MainActivity" >/dev/null; fi
echo "DroidHand installed; accessibility enabled."
