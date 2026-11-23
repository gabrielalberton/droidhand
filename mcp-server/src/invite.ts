#!/usr/bin/env node
import { execFileSync } from "node:child_process";
import { existsSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import QRCode from "qrcode";
import { loadConfig } from "./config.js";
import { ControlClient } from "./control/client.js";

/**
 * Entry point `droidhand-invite` (dist/invite.js): prints a one-time setup link and
 * its QR code for a phone, so the phone needs nothing else from this computer.
 * The phone opens the link (or scans the QR with its camera or with DroidHand) and
 * gets the download link, the steps and its connection.
 *
 *   droidhand-invite                       the only configured phone (first install)
 *   droidhand-invite --existing s24        a configured phone
 *   droidhand-invite --id tablet --name "Tablet" [--admin]   adds a new phone
 *   --relay-url ws://100.x.y.z:8787        address phones dial (default: this host's tailnet IP)
 */
function argv(): Record<string, string | boolean> {
  const out: Record<string, string | boolean> = {};
  const a = process.argv.slice(2);
  for (let i = 0; i < a.length; i++) {
    const k = a[i]!;
    if (!k.startsWith("--")) continue;
    const key = k.slice(2);
    const next = a[i + 1];
    if (key === "admin" || key === "help" || next === undefined || next.startsWith("--")) out[key] = true;
    else { out[key] = next; i++; }
  }
  return out;
}

function tailnetIp(): string {
  try {
    return execFileSync("tailscale", ["ip", "-4"], { encoding: "utf8", timeout: 5000 }).split("\n")[0]!.trim();
  } catch {
    return "";
  }
}

async function main(): Promise<void> {
  const a = argv();
  if (a.help) {
    process.stdout.write("usage: droidhand-invite [--existing <id> | --id <new-id> --name <name> [--admin]] [--relay-url ws://host:port]\n");
    return;
  }
  const env = { ...process.env };
  const defToken = join(homedir(), ".config", "droidhand", "token");
  if (!env.DROIDHAND_TOKEN && !env.DROIDHAND_TOKEN_FILE && existsSync(defToken)) env.DROIDHAND_TOKEN_FILE = defToken;
  const cfg = loadConfig(env, false);
  let relayUrl = typeof a["relay-url"] === "string" ? a["relay-url"] : env.DROIDHAND_PUBLIC_URL ?? "";
  if (!relayUrl) {
    const ip = tailnetIp();
    if (!ip) throw new Error("could not find this computer's tailnet IP (is Tailscale up?); pass --relay-url ws://<address>:8787");
    relayUrl = `ws://${ip}:${cfg.relayPort}`;
  }
  const control = new ControlClient(`ws://${cfg.controlHost}:${cfg.controlPort}`, cfg.token);
  const args: Record<string, unknown> = { relayUrl };
  if (typeof a.existing === "string") args.existing = a.existing;
  if (typeof a.id === "string") { args.id = a.id; args.name = typeof a.name === "string" ? a.name : a.id; if (a.admin) args.role = "admin"; }
  let r;
  try {
    r = await control.call("relay_invite", args, 10_000);
  } catch (err) {
    throw new Error(`relay daemon unreachable at ${cfg.controlHost}:${cfg.controlPort} (is droidhand-relay running?): ${err instanceof Error ? err.message : String(err)}`);
  } finally {
    control.close();
  }
  if (!r.ok) throw new Error(r.error ?? "invite failed");
  const d = r.data as { device: string; name: string; setupPath: string; expiresAt: string };
  const url = relayUrl.replace(/^ws/, "http") + d.setupPath;
  const qr = await QRCode.toString(url, { type: "terminal", small: true, errorCorrectionLevel: "M" });
  const until = new Date(d.expiresAt).toLocaleString();
  process.stdout.write([
    "",
    `DroidHand — configurar o celular "${d.name}" (${d.device})`,
    "",
    "No celular:",
    "  1. Instale o Tailscale e entre na mesma tailnet deste computador.",
    "  2. Aponte a câmera para o QR code (ou abra o link). A página tem o download do app,",
    "     o botão que conecta o app e os próximos passos. No app, \"Ler QR code\" também serve.",
    "",
    qr,
    `  ${url}`,
    "",
    `Vale uma vez: expira quando o celular conectar, ao reiniciar o relay, ou em ${until}. Não compartilhe.`,
    "",
  ].join("\n"));
}

main().catch((err) => {
  process.stderr.write(`droidhand-invite: ${err instanceof Error ? err.message : String(err)}\n`);
  process.exit(1);
});
