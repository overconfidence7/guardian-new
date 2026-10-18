import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { parseDeviceConfig, type DeviceConfig } from "../../src/lib/model.js";

export const REPO_ROOT = fileURLToPath(new URL("../../../../", import.meta.url));

export function fixtureJson(): unknown {
  return JSON.parse(readFileSync(`${REPO_ROOT}shared/fixtures/device-config.example.json`, "utf8")) as unknown;
}

export function fixtureConfig(): DeviceConfig {
  return parseDeviceConfig(fixtureJson());
}
