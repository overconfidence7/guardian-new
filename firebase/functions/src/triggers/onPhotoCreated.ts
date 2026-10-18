import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { paths, stringField } from "../lib/families.js";
import { db } from "../lib/firebase.js";
import { messenger } from "../lib/messaging.js";
import { pingPhone } from "../lib/push.js";

/** Wakes the phone with { type: "photo" } so a new photo shows without waiting for a sync. */
export const onPhotoCreated = onDocumentCreated(
  "families/{familyId}/devices/{deviceId}/photos/{photoId}",
  async (event) => {
    const { familyId, deviceId } = event.params;
    const deviceRef = paths.device(db(), familyId, deviceId);
    const device = await deviceRef.get();
    if (!device.exists) return;
    await pingPhone(deviceRef, stringField(device.data(), "fcmToken") || null, "photo", messenger());
  },
);
