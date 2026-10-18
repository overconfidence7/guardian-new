/*
 * Guards against drift between the Kotlin model, the TypeScript mirror, the shared
 * fixture and the security rules.
 */
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import {
  APP_CATEGORIES,
  EVENT_TYPES,
  REMINDER_KINDS,
  SCREENING_MODES,
  SEVERITIES,
  THEMES,
  TILE_KINDS,
  parseDeviceConfig,
  readDeviceConfig,
} from "../../src/lib/model.js";
import { fixtureJson, REPO_ROOT } from "./fixture.js";

const kotlin = readFileSync(`${REPO_ROOT}core/src/main/kotlin/app/guardianmode/core/Model.kt`, "utf8");
const rules = readFileSync(`${REPO_ROOT}firebase/firestore.rules`, "utf8");

function kotlinEnum(name: string): string[] {
  const match = new RegExp(`enum class ${name}\\s*\\{([^}]*)\\}`).exec(kotlin);
  if (!match) throw new Error(`enum ${name} not found in Model.kt`);
  return match[1]!
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/\/\/.*$/gm, "")
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
}

describe("TypeScript enums match Model.kt", () => {
  it.each([
    ["EventType", EVENT_TYPES],
    ["Severity", SEVERITIES],
    ["TileKind", TILE_KINDS],
    ["ThemePreference", THEMES],
    ["ScreeningMode", SCREENING_MODES],
    ["ReminderKind", REMINDER_KINDS],
    ["AppCategory", APP_CATEGORIES],
  ] as const)("%s", (name, values) => {
    expect([...values]).toEqual(kotlinEnum(name));
  });
});

describe("firestore.rules event allowlist", () => {
  it("lists every EventType the phone may send (all except CHECK_IN_MISSED)", () => {
    const block = /function validEventType\(t\) \{[\s\S]*?\[([\s\S]*?)\]/.exec(rules)![1]!;
    const listed = [...block.matchAll(/'([A-Z_]+)'/g)].map((m) => m[1]);
    expect(listed).toEqual(EVENT_TYPES.filter((t) => t !== "CHECK_IN_MISSED"));
  });

  it("lists every Severity", () => {
    const block = /function validSeverity\(s\) \{[\s\S]*?\[([\s\S]*?)\]/.exec(rules)![1]!;
    expect([...block.matchAll(/'([A-Z_]+)'/g)].map((m) => m[1])).toEqual([...SEVERITIES]);
  });
});

describe("DeviceConfig schema", () => {
  it("parses the shared fixture without losing anything", () => {
    const parsed = parseDeviceConfig(fixtureJson());
    const fixture = fixtureJson() as Record<string, unknown>;
    expect(parsed.revision).toBe(42);
    expect(parsed.parentName).toBe("Margaret");
    expect(parsed.contacts[0]).toEqual({ ...(fixture.contacts as object[])[0], photoUrl: null });
    expect(parsed.protection.pauseTripwires.extraPackages).toEqual({ "org.example.creditunion": "BANK" });
    expect(parsed.checkIn).toEqual({ enabled: true, deadline: "11:00", timezone: "America/Chicago" });
  });

  it("fills defaults for an empty document, like the phone's decoder", () => {
    const c = parseDeviceConfig({});
    expect(c.protection.longCallAlert.minutes).toBe(20);
    expect(c.display.fontScale).toBe(1.3);
  });

  it("coerces unknown enum values to their defaults, like coerceInputValues", () => {
    const c = parseDeviceConfig({ display: { theme: "SEPIA" }, protection: { callScreening: { mode: "MAGIC" } } });
    expect(c.display.theme).toBe("LIGHT");
    expect(c.protection.callScreening.mode).toBe("SILENCE_UNKNOWN");
  });

  it("ignores unknown fields", () => {
    expect(parseDeviceConfig({ futureField: 1 })).not.toHaveProperty("futureField");
  });

  it("readDeviceConfig returns null instead of throwing", () => {
    expect(readDeviceConfig({ contacts: [{ name: "no id" }] })).toBeNull();
    expect(readDeviceConfig(undefined)).not.toBeNull();
  });
});
