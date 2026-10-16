import { describe, expect, it } from "vitest";
import { describeConfigChanges, joinSummary, summarizeConfigChange } from "../../src/lib/configDiff.js";
import type { DeviceConfig } from "../../src/lib/model.js";
import { fixtureConfig } from "./fixture.js";

function edit(mutate: (c: DeviceConfig) => void): { before: DeviceConfig; after: DeviceConfig } {
  const before = fixtureConfig();
  const after = structuredClone(before);
  mutate(after);
  return { before, after };
}

function lines(mutate: (c: DeviceConfig) => void, appLabels?: Record<string, string>): string[] {
  const { before, after } = edit(mutate);
  return describeConfigChanges(before, after, appLabels ? { appLabels } : {});
}

describe("describeConfigChanges", () => {
  it("no change, no lines (revision bumps don't count)", () => {
    expect(lines(() => undefined)).toEqual([]);
    expect(lines((c) => (c.revision += 1))).toEqual([]);
  });

  it("text size", () => {
    expect(lines((c) => (c.display.fontScale = 1.6))).toEqual(["Changed text size"]);
  });

  it("contacts added, removed, updated", () => {
    expect(
      lines((c) => {
        c.contacts = c.contacts.filter((x) => x.id !== "c_june");
        c.contacts.push({ id: "c_pharm", name: "CVS Pharmacy", phone: "+1 555 010 7777", photoUrl: null, note: "" });
        c.contacts[0]!.phone = "+1 555 010 9999";
      }),
    ).toEqual(["Added CVS Pharmacy to contacts", "Removed June next door from contacts", "Updated Dr. Patel in contacts"]);
  });

  it("adding Dr. Patel to an empty contact list", () => {
    const before = fixtureConfig();
    before.contacts = [];
    const after = fixtureConfig();
    after.contacts = after.contacts.filter((c) => c.id === "c_patel");
    expect(describeConfigChanges(before, after)).toEqual(["Added Dr. Patel to contacts"]);
  });

  it("long-call notices off and minutes", () => {
    expect(lines((c) => (c.protection.longCallAlert.enabled = false))).toEqual(["Turned off long-call notices"]);
    expect(lines((c) => (c.protection.longCallAlert.minutes = 30))).toEqual(["Changed long-call notices to 30 minutes"]);
  });

  it("family members and primary helper", () => {
    expect(
      lines((c) => {
        c.family[0]!.isPrimaryHelper = false;
        c.family[1]!.isPrimaryHelper = true;
      }),
    ).toEqual(["Made David the first person to call"]);
    expect(lines((c) => c.family.push({ id: "fam_amy", name: "Amy", relation: "Granddaughter", phone: "+1", photoUrl: null, isPrimaryHelper: false }))).toEqual([
      "Added Amy to family",
    ]);
    expect(lines((c) => (c.family = c.family.slice(0, 1)))).toEqual(["Removed David from family"]);
    expect(lines((c) => (c.family[1]!.phone = "+1 555 000 0000"))).toEqual(["Updated David’s details"]);
    expect(lines((c) => c.family.reverse())).toEqual(["Reordered family"]);
  });

  it("home screen tiles", () => {
    expect(lines((c) => c.home.tiles.push({ id: "t9", kind: "HELP", target: "", label: "" }))).toEqual([
      "Added the Help tile to the home screen",
    ]);
    expect(lines((c) => (c.home.tiles = c.home.tiles.filter((t) => t.id !== "t5")))).toEqual([
      "Removed the Camera tile from the home screen",
    ]);
    expect(lines((c) => (c.home.tiles[0]!.label = "Sarah (daughter)"))).toEqual([
      "Changed the Sarah (daughter) tile",
    ]);
    expect(lines((c) => c.home.tiles.reverse())).toEqual(["Rearranged the home screen"]);
    expect(lines((c) => (c.home.showPhotoCard = false))).toEqual(["Turned off the photo card"]);
    expect(lines((c) => (c.home.columns = 1))).toEqual(["Changed the home screen to one column"]);
    expect(lines((c) => (c.home.columns = 3))).toEqual(["Changed the home screen to 3 columns"]);
  });

  it("display", () => {
    expect(lines((c) => (c.display.highContrast = true))).toEqual(["Turned on high contrast"]);
    expect(lines((c) => (c.display.theme = "DARK"))).toEqual(["Changed the colors to dark"]);
    expect(lines((c) => (c.display.theme = "SYSTEM"))).toEqual(["Set the colors to match the phone"]);
  });

  it("allowed apps use installed-app labels when known", () => {
    expect(lines((c) => c.allowedApps.push("com.spotify.music"), { "com.spotify.music": "Spotify" })).toEqual([
      "Added Spotify to All my apps",
    ]);
    expect(lines((c) => (c.allowedApps = c.allowedApps.filter((a) => a !== "com.facebook.katana")))).toEqual([
      "Removed com.facebook.katana from All my apps",
    ]);
  });

  it("pause tripwires", () => {
    expect(lines((c) => (c.protection.pauseTripwires.enabled = false))).toEqual([
      "Turned off the pause before sending money",
    ]);
    expect(lines((c) => (c.protection.pauseTripwires.windowMinutes = 45))).toEqual([
      "Changed the pause to last 45 minutes after a call",
    ]);
    expect(
      lines((c) => (c.protection.pauseTripwires.extraPackages["com.mybank"] = "BANK"), { "com.mybank": "My Bank" }),
    ).toEqual(["Added My Bank to apps that get a pause card"]);
    expect(lines((c) => (c.protection.pauseTripwires.categories = ["BANK"]))).toEqual([
      "Changed which apps get a pause card",
    ]);
    expect(lines((c) => c.protection.pauseTripwires.categories.reverse())).toEqual([]);
  });

  it("remote access", () => {
    expect(lines((c) => (c.protection.remoteAccessAlarm.enabled = false))).toEqual([
      "Turned off the remote-control app warning",
    ]);
    expect(
      lines((c) => c.protection.remoteAccessAlarm.trustedPackages.push("com.teamviewer.quicksupport.market"), {
        "com.teamviewer.quicksupport.market": "TeamViewer QuickSupport",
      }),
    ).toEqual(["Marked TeamViewer QuickSupport as a trusted remote-control app"]);
    expect(lines((c) => (c.protection.remoteAccessAlarm.warnOnControlCapableInstalls = false))).toEqual([
      "Turned off warnings for new apps that can control the screen",
    ]);
  });

  it("call screening", () => {
    expect(lines((c) => (c.protection.callScreening.mode = "OFF"))).toEqual(["Turned off unknown caller screening"]);
    expect(lines((c) => (c.protection.callScreening.mode = "VOICEMAIL_UNKNOWN"))).toEqual([
      "Set unknown callers to go to voicemail",
    ]);
    expect(
      lines((c) => c.protection.callScreening.allowlist.push({ label: "Dr. Lee", number: "+1 555 010 8888" })),
    ).toEqual(["Added Dr. Lee to numbers that always ring"]);
    expect(lines((c) => c.protection.callScreening.allowlist.pop())).toEqual([
      "Removed St. Mark’s office from numbers that always ring".replace("’", "'"),
    ]);
    expect(lines((c) => (c.protection.callScreening.allowRepeatCallers = false))).toEqual([
      "Stopped letting a second call from the same number ring through",
    ]);
  });

  it("safe word changes never reveal the word", () => {
    const out = lines((c) => (c.protection.safeWord.word = "robin"));
    expect(out).toEqual(["Changed the family safe word"]);
    expect(out.join(" ")).not.toContain("robin");
    expect(out.join(" ")).not.toContain("bluebird");
    expect(lines((c) => (c.protection.safeWord.enabled = false))).toEqual(["Turned off the family safe word card"]);
  });

  it("setting a safe word for the first time", () => {
    const before = fixtureConfig();
    before.protection.safeWord.word = "";
    expect(describeConfigChanges(before, fixtureConfig())).toEqual(["Set the family safe word"]);
  });

  it("check-in", () => {
    expect(lines((c) => (c.checkIn.deadline = "10:00"))).toEqual([
      "Changed the daily “I’m OK” time to 10:00 AM",
    ]);
    expect(lines((c) => (c.checkIn.enabled = false))).toEqual(["Turned off the daily “I’m OK”"]);
    expect(lines((c) => (c.checkIn.timezone = "America/Los_Angeles"))).toEqual([
      "Changed the time zone to Los Angeles",
    ]);
  });

  it("reminders", () => {
    expect(
      lines((c) =>
        c.reminders.push({
          id: "r3",
          title: "Eye drops",
          detail: "",
          kind: "MEDICATION",
          times: ["21:00"],
          daysOfWeek: [],
          date: null,
          notifyFamilyIfMissed: false,
        }),
      ),
    ).toEqual(["Added the reminder “Eye drops”"]);
    expect(lines((c) => (c.reminders = c.reminders.slice(1)))).toEqual(["Removed the reminder “Blood pressure pill”"]);
    expect(lines((c) => (c.reminders[0]!.times = ["09:00"]))).toEqual(["Changed the reminder “Blood pressure pill”"]);
  });

  it("parent name", () => {
    expect(lines((c) => (c.parentName = "Maggie"))).toEqual(["Changed the name on the phone to Maggie"]);
  });

  it("never uses words the parent shouldn't see", () => {
    const { before } = edit(() => undefined);
    const after = structuredClone(before);
    after.protection.longCallAlert.enabled = false;
    after.protection.remoteAccessAlarm.enabled = false;
    after.protection.callScreening.mode = "OFF";
    after.protection.pauseTripwires.enabled = false;
    const text = describeConfigChanges(before, after).join(" ");
    expect(text).not.toMatch(/\b(scam|fraud|danger|alert|alerts|monitor|track)\b/i);
  });
});

describe("joinSummary", () => {
  it("joins with commas and 'and'", () => {
    expect(joinSummary([])).toBe("");
    expect(joinSummary(["Changed text size"])).toBe("Changed text size");
    expect(joinSummary(["Changed text size", "Added Dr. Patel to contacts"])).toBe(
      "Changed text size and added Dr. Patel to contacts",
    );
    expect(joinSummary(["Changed text size", "Added Dr. Patel to contacts", "Turned on high contrast"])).toBe(
      "Changed text size, added Dr. Patel to contacts and turned on high contrast",
    );
  });

  it("caps long lists", () => {
    expect(joinSummary(["A one", "B two", "C three", "D four"])).toBe("A one, b two and 2 other changes");
    expect(joinSummary(["A one", "B two", "C three", "D four"], 3)).toBe("A one, b two and 2 other changes");
    expect(joinSummary(["A", "B", "C", "D"], 4)).toBe("A, b, c and d");
    expect(joinSummary(["A", "B", "C", "D", "E"], 4)).toBe("A, b, c and 2 other changes");
    expect(joinSummary(["A", "B", "C"], 2)).toBe("A and 2 other changes");
  });
});

describe("summarizeConfigChange", () => {
  it("returns null when nothing changed", () => {
    const c = fixtureConfig();
    expect(summarizeConfigChange(c, structuredClone(c))).toBeNull();
  });

  it("one-line summary", () => {
    const { before, after } = edit((c) => {
      c.display.fontScale = 1.6;
      c.contacts.push({ id: "c_x", name: "Pastor Jim", phone: "+1", photoUrl: null, note: "" });
    });
    expect(summarizeConfigChange(before, after)).toBe("Added Pastor Jim to contacts and changed text size");
  });
});
