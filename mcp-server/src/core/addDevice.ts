import { randomBytes } from "node:crypto";
import { copyFileSync, existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { DEVICE_ID, parseDevices, type DeviceConfig } from "../config.js";

export interface NewDevice {
  id: string;
  name: string;
  role: "admin" | "device";
}

/** Check what an admin phone sent for a new phone; throws a message the app can show. */
export function validateNewDevice(args: Record<string, unknown>, taken: Iterable<string>): NewDevice {
  const id = String(args.id ?? "").trim().toLowerCase();
  if (!DEVICE_ID.test(id)) throw new Error("invalid_id: use lowercase letters, digits, - and _ (up to 32, starting with a letter or digit)");
  if ([...taken].includes(id)) throw new Error(`id_taken: a phone called '${id}' already exists`);
  const name = String(args.name ?? "").trim().slice(0, 60) || id;
  return { id, name, role: args.role === "admin" ? "admin" : "device" };
}

/**
 * Add a phone to the devices file: a fresh token in `devices/<id>.token` (0600, never
 * logged) and a new entry. The file is checked with the same parser the relay uses before
 * it replaces the old one (kept as `devices.json.bak`). Returns the new phone's config.
 */
export function writeNewDevice(devicesFile: string, d: NewDevice): DeviceConfig {
  if (!existsSync(devicesFile)) {
    throw new Error(`no_devices_file: ${devicesFile} does not exist. Several phones need it: see docs/setup.md, "Several phones"`);
  }
  const base = dirname(devicesFile);
  const raw = JSON.parse(readFileSync(devicesFile, "utf8")) as { devices?: Record<string, unknown>[] };
  if (!Array.isArray(raw.devices)) throw new Error("devices file: 'devices' must be an array");
  if (raw.devices.some((x) => x.id === d.id)) throw new Error(`id_taken: a phone called '${d.id}' already exists`);

  const tokDir = join(base, "devices");
  mkdirSync(tokDir, { recursive: true, mode: 0o700 });
  const tokenFile = `devices/${d.id}.token`;
  const token = randomBytes(32).toString("hex");
  writeFileSync(join(base, tokenFile), token + "\n", { mode: 0o600, flag: "wx" }); // never overwrite a token

  raw.devices.push({ id: d.id, name: d.name, tokenFile, role: d.role });
  const text = JSON.stringify(raw, null, 2) + "\n";
  const added = parseDevices(text, base).find((x) => x.id === d.id);
  if (!added) throw new Error("devices file: the new phone did not parse");
  copyFileSync(devicesFile, devicesFile + ".bak");
  const tmp = devicesFile + ".tmp";
  writeFileSync(tmp, text, { mode: 0o600 });
  renameSync(tmp, devicesFile);
  return added;
}
