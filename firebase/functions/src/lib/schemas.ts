import { z } from "zod";
import { isValidTimeZone } from "./checkIn.js";

export const idSchema = z
  .string()
  .regex(/^[A-Za-z0-9_-]{1,128}$/, "Not a valid id.");

export const nameSchema = (max: number) => z.string().trim().min(1, "Please enter a name.").max(max);

/** Optional phone number; an empty string means "not given". */
export const optionalPhoneSchema = z
  .string()
  .trim()
  .max(32)
  .refine((s) => s === "" || /^\+?[0-9][0-9 ()\-.]{2,30}$/.test(s), "That doesn't look like a phone number.")
  .nullish()
  .transform((s) => (s === "" || s === null ? undefined : s));

export const codeInputSchema = z.string().trim().min(1).max(20);

export const timezoneSchema = z.string().refine(isValidTimeZone, "Unknown time zone.");

export const createFamilyInput = z.object({
  familyName: nameSchema(80),
  parentName: nameSchema(60),
  helperPhone: optionalPhoneSchema,
  /** Extension: the parent's IANA time zone for the daily check-in. */
  timezone: timezoneSchema.optional(),
});

export const addDeviceInput = z.object({
  familyId: idSchema,
  parentName: nameSchema(60),
  timezone: timezoneSchema.optional(),
});

export const familyDeviceInput = z.object({ familyId: idSchema, deviceId: idSchema });

export const familyInput = z.object({ familyId: idSchema });

export const previewInviteInput = z.object({ code: codeInputSchema });

export const claimDeviceInviteInput = z.object({
  code: codeInputSchema,
  model: z.string().trim().max(100).default(""),
  androidVersion: z.string().trim().max(20).default(""),
  appVersion: z.string().trim().max(40).default(""),
});

export const acceptHelperInviteInput = z.object({
  code: codeInputSchema,
  displayName: nameSchema(60),
  phone: optionalPhoneSchema,
});

export const removeHelperInput = z.object({ familyId: idSchema, uid: z.string().min(1).max(128) });

export const approveHelperInput = z.object({
  familyId: idSchema,
  uid: z.string().min(1).max(128),
  approve: z.boolean(),
});
