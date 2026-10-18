import { describe, expect, it, vi } from "vitest";
import type { Firestore } from "firebase-admin/firestore";
import { allocateInviteCode, generateInviteCode, MAX_CODE_ATTEMPTS, normalizeInviteCode } from "../../src/lib/codes.js";
import { INVITE_ALPHABET } from "../../src/lib/model.js";

describe("generateInviteCode", () => {
  it("is 6 characters from the unambiguous alphabet", () => {
    for (let i = 0; i < 2000; i++) {
      expect(generateInviteCode()).toMatch(/^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}$/);
    }
  });

  it("never uses look-alike characters", () => {
    expect(INVITE_ALPHABET).toHaveLength(31);
    for (const c of "01ILO") expect(INVITE_ALPHABET).not.toContain(c);
  });

  it("maps each random index to the alphabet", () => {
    let i = 0;
    expect(generateInviteCode(() => i++)).toBe("ABCDEF");
    expect(generateInviteCode((max) => max - 1)).toBe("999999");
  });

  it("covers the whole alphabet roughly evenly", () => {
    const counts = new Map<string, number>();
    const draws = 3100;
    for (let i = 0; i < draws / 6; i++) {
      for (const c of generateInviteCode()) counts.set(c, (counts.get(c) ?? 0) + 1);
    }
    expect(counts.size).toBe(31);
    for (const n of counts.values()) expect(n).toBeGreaterThan(40); // expected ~100
  });
});

describe("normalizeInviteCode", () => {
  it.each([
    ["abc234", "ABC234"],
    [" ABC 234 ", "ABC234"],
    ["abc-234", "ABC234"],
  ])("%s → %s", (input, output) => {
    expect(normalizeInviteCode(input)).toBe(output);
  });

  it.each(["ABC23", "ABC2345", "ABC10O", "ABCIL2", "ABC$23", ""])("rejects %s", (input) => {
    expect(normalizeInviteCode(input)).toBeNull();
  });
});

function fakeFirestore(taken: Set<string>) {
  const created: string[] = [];
  const firestore = {
    collection: () => ({
      doc: (code: string) => ({
        create: vi.fn(() => {
          if (taken.has(code)) return Promise.reject(Object.assign(new Error("exists"), { code: 6 }));
          taken.add(code);
          created.push(code);
          return Promise.resolve();
        }),
      }),
    }),
  } as unknown as Firestore;
  return { firestore, created };
}

describe("allocateInviteCode", () => {
  it("retries when a code is already taken", async () => {
    const { firestore, created } = fakeFirestore(new Set(["AAAAAA", "BBBBBB"]));
    const sequence = ["AAAAAA", "BBBBBB", "CCCCCC"];
    let call = 0;
    const random = () => INVITE_ALPHABET.indexOf(sequence[Math.floor(call++ / 6)]![0]!);
    const { code } = await allocateInviteCode(firestore, { kind: "device" }, random);
    expect(code).toBe("CCCCCC");
    expect(created).toEqual(["CCCCCC"]);
  });

  it("gives up after a bounded number of attempts", async () => {
    const { firestore } = fakeFirestore(new Set(["AAAAAA"]));
    await expect(allocateInviteCode(firestore, {}, () => 0)).rejects.toMatchObject({ code: "unavailable" });
    expect(MAX_CODE_ATTEMPTS).toBeGreaterThan(1);
  });

  it("does not swallow other errors", async () => {
    const firestore = {
      collection: () => ({ doc: () => ({ create: () => Promise.reject(new Error("boom")) }) }),
    } as unknown as Firestore;
    await expect(allocateInviteCode(firestore, {})).rejects.toThrow("boom");
  });
});
