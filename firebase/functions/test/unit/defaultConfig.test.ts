import { describe, expect, it } from "vitest";
import { defaultDeviceConfig, familyContactIdFor } from "../../src/lib/defaultConfig.js";
import { parseDeviceConfig } from "../../src/lib/model.js";
import { fixtureJson } from "./fixture.js";

type Shape = string | { [key: string]: Shape } | Shape[];

/** JSON type tree; arrays are described by their first element. */
function shapeOf(value: unknown): Shape {
  if (Array.isArray(value)) return value.length ? [shapeOf(value[0])] : [];
  if (value === null) return "null";
  if (typeof value === "object") {
    return Object.fromEntries(Object.entries(value as Record<string, unknown>).map(([k, v]) => [k, shapeOf(v)]));
  }
  return typeof value;
}

/** Keys present in `a` whose type differs from, or is missing in, `b`. */
function mismatches(a: Shape, b: Shape, path = ""): string[] {
  if (Array.isArray(a) && Array.isArray(b)) {
    return a.length && b.length ? mismatches(a[0]!, b[0]!, `${path}[]`) : [];
  }
  if (typeof a === "object" && typeof b === "object" && !Array.isArray(a) && !Array.isArray(b)) {
    return Object.keys(a).flatMap((k) =>
      k in b ? mismatches(a[k]!, b[k]!, `${path}.${k}`) : [`${path}.${k} missing`],
    );
  }
  if (a === "null" || b === "null") return [];
  return a === b ? [] : [`${path}: ${JSON.stringify(a)} vs ${JSON.stringify(b)}`];
}

const helper = { uid: "uid_sarah", name: "Sarah", phone: "+1 555 010 1111" };

describe("defaultDeviceConfig", () => {
  it("parses with the strict config schema", () => {
    const config = defaultDeviceConfig({ parentName: "Margaret", helper });
    expect(parseDeviceConfig(config)).toEqual(config);
  });

  it("has the same JSON shape as the shared fixture", () => {
    const config = defaultDeviceConfig({ parentName: "Margaret", helper });
    const fixture = fixtureJson();
    // Optional fields the default leaves empty are allowed to be absent (e.g. reminders[].date).
    expect(mismatches(shapeOf(config), shapeOf(fixture)).filter((m) => !m.includes("reminders"))).toEqual([]);
    expect(
      mismatches(shapeOf(fixture), shapeOf(config)).filter(
        (m) => !/reminders|extraPackages\.|allowlist|contacts|family\[\]\.photoUrl/.test(m),
      ),
    ).toEqual([]);
  });

  it("matches Model.kt defaults", () => {
    const c = defaultDeviceConfig({ parentName: "Margaret" });
    expect(c.schemaVersion).toBe(1);
    expect(c.display).toEqual({ fontScale: 1.3, highContrast: false, theme: "LIGHT" });
    expect(c.protection.pauseTripwires).toEqual({
      enabled: true,
      windowMinutes: 30,
      categories: ["PAYMENT", "GIFT_CARD", "CRYPTO", "BANK"],
      extraPackages: {},
    });
    expect(c.protection.callScreening).toEqual({ mode: "SILENCE_UNKNOWN", allowlist: [], allowRepeatCallers: true });
    expect(c.protection.longCallAlert).toEqual({ enabled: true, minutes: 20 });
    expect(c.protection.safeWord).toEqual({ enabled: true, word: "" });
    expect(c.checkIn).toEqual({ enabled: true, deadline: "11:00", timezone: "America/New_York" });
    expect(c.home.columns).toBe(2);
    expect(c.home.showPhotoCard).toBe(true);
    // Equal to what the lenient schema fills in for an empty document, except the starter content.
    const empty = parseDeviceConfig({ parentName: "Margaret" });
    expect({ ...c, home: empty.home }).toEqual(empty);
  });

  it("adds the helper as primary helper with a call tile when they gave a phone", () => {
    const c = defaultDeviceConfig({ parentName: "Margaret", helper });
    expect(c.parentName).toBe("Margaret");
    expect(c.family).toEqual([
      { id: familyContactIdFor("uid_sarah"), name: "Sarah", relation: "", phone: "+1 555 010 1111", photoUrl: null, isPrimaryHelper: true },
    ]);
    expect(c.home.tiles.map((t) => t.kind)).toEqual([
      "CALL_CONTACT",
      "PHONE",
      "MESSAGES",
      "CAMERA",
      "PHOTOS",
      "REMINDERS",
      "HELP",
    ]);
    expect(c.home.tiles[0]).toMatchObject({ target: "fam_uid_sarah", label: "Sarah" });
  });

  it("leaves the family list empty and skips the call tile without a phone", () => {
    const c = defaultDeviceConfig({ parentName: "Margaret", helper: { uid: "u", name: "Sarah", phone: "  " } });
    expect(c.family).toEqual([]);
    expect(c.home.tiles.map((t) => t.kind)).toEqual(["PHONE", "MESSAGES", "CAMERA", "PHOTOS", "REMINDERS", "HELP"]);
  });

  it("tile ids are unique", () => {
    const ids = defaultDeviceConfig({ parentName: "M", helper }).home.tiles.map((t) => t.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it("uses the given time zone", () => {
    expect(defaultDeviceConfig({ parentName: "M", timezone: "America/Chicago" }).checkIn.timezone).toBe("America/Chicago");
  });
});
