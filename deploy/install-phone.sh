#!/usr/bin/env bash
# Install/update the DroidHand app on a phone over adb and (re)apply the
# one-time grants. Android disables an app's accessibility service when the app is
# updated, so this script re-enables it every time, appending to (never replacing)
# the services already enabled.
# Usage: deploy/install-phone.sh [adb-serial] [apk]
# The app is installed with the Play Store recorded as its installer (-i com.android.vending): banking
# apps refuse a sideloaded accessibility service but accept one from the Play Store (see CHANGELOG
# 1.3.0 and the "Look like a Play Store install" option in the app). This makes Android report a
# different installer than the real one; set DROIDHAND_INSTALLER= (empty) to install normally.
# Optional: RELAY_URL=ws://<computer-tailnet-ip>:8787 also configures the app
# (address + token from DROIDHAND_TOKEN_FILE, default ~/.config/droidhand/token)
# and turns the agent service on, without touching the phone's screen.
set -euo pipefail
ADB="${ADB:-adb}"
SERIAL="${1:-}"
OUT="$(dirname "$0")/../android/app/build/outputs/apk"
# Default: the signed release build if present, else the debug build.
DEFAULT_APK="$OUT/release/droidhand-release.apk"; [[ -f "$DEFAULT_APK" ]] || DEFAULT_APK="$OUT/debug/droidhand-debug.apk"
APK="${2:-$DEFAULT_APK}"
PKG=work.alberton.droidhand
SVC="$PKG/$PKG.a11y.DroidHandAccessibilityService"
A=("$ADB"); [[ -n "$SERIAL" ]] && A+=(-s "$SERIAL")

INSTALLER="${DROIDHAND_INSTALLER-com.android.vending}"
"${A[@]}" install -r ${INSTALLER:+-i "$INSTALLER"} "$APK"
"${A[@]}" shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS
"${A[@]}" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
if [[ -n "${RELAY_URL:-}" ]]; then
  TOKEN_FILE="${DROIDHAND_TOKEN_FILE:-$HOME/.config/droidhand/token}"
  "${A[@]}" shell am broadcast -n "$PKG/.service.ProvisionReceiver" \
    --es relay "$RELAY_URL" --es token "$(tr -d '\n' < "$TOKEN_FILE")" --ez enable true >/dev/null
  echo "Configured relay $RELAY_URL (token from $TOKEN_FILE)."
fi
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
