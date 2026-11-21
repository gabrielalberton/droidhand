#!/usr/bin/env bash
# DroidHand relay — one-shot local setup for the NUC (or any Linux host).
# Generates a token, installs the user systemd service, and starts the daemon.
# Idempotent: safe to re-run. No secrets are printed or committed.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOME_DIR="${HOME}"
NODE="$(command -v node || true)"
GH_OWNER="${GH_OWNER:-gabrielalberton}"

if [[ -z "${NODE}" ]]; then
  echo "error: node not found on PATH" >&2
  exit 1
fi

# 1) Token (0600, outside git).
CFG_DIR="${HOME_DIR}/.config/droidhand"
mkdir -p "${CFG_DIR}"
chmod 700 "${CFG_DIR}"
TOKEN_FILE="${CFG_DIR}/token"
if [[ ! -s "${TOKEN_FILE}" ]]; then
  umask 077
  openssl rand -hex 32 > "${TOKEN_FILE}"
  chmod 600 "${TOKEN_FILE}"
  echo "generated token at ${TOKEN_FILE}"
else
  echo "token already present at ${TOKEN_FILE} (kept)"
fi

# 2) Build if needed.
if [[ ! -f "${REPO}/mcp-server/dist/daemon.js" ]]; then
  echo "building mcp-server..."
  ( cd "${REPO}/mcp-server" && npm ci --no-fund --no-audit && npm run build )
fi

# adb for own_helper_start (background own-window method). Override with ADB=/path/to/adb.
ADB_BIN="${ADB:-$(command -v adb || echo "${HOME_DIR}/android-sdk/platform-tools/adb")}"

# 3) Render and install the user service.
UNIT_DIR="${HOME_DIR}/.config/systemd/user"
mkdir -p "${UNIT_DIR}"
sed -e "s#__NODE__#${NODE}#g" \
    -e "s#__REPO__#${REPO}#g" \
    -e "s#__HOME__#${HOME_DIR}#g" \
    -e "s#__GH_OWNER__#${GH_OWNER}#g" \
    -e "s#__ADB__#${ADB_BIN}#g" \
    "${REPO}/deploy/droidhand-relay.service" > "${UNIT_DIR}/droidhand-relay.service"

systemctl --user daemon-reload
systemctl --user enable --now droidhand-relay.service
sleep 1
systemctl --user --no-pager --full status droidhand-relay.service | sed -n '1,8p' || true

echo
echo "DroidHand relay installed."
echo "  control (adapters): 127.0.0.1:8788"
echo "  phone relay:        127.0.0.1:8787  (expose over Tailscale when the app is ready:"
echo "                      tailscale serve --tcp=8787 tcp://127.0.0.1:8787 )"
