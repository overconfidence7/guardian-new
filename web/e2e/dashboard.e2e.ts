/*
 * End-to-end against the Firebase Emulator Suite: sign in, create a family, see the
 * setup code, the phone claims it, edit text size, an event appears in Alerts.
 *
 * Run with the emulators already up (auth 9099, firestore 8080, storage 9199, functions 5001):
 *   cd ../firebase && firebase emulators:exec --project demo-guardian "cd ../web && npm run e2e"
 */
import { expect, test } from '@playwright/test';
import { admin, anonymousPhone, callFunction, clearEmulators, createHelperAccount, emulatorsUp, injectEvent } from './emulator';

test.describe.configure({ mode: 'serial' });

test.beforeAll(async () => {
  test.skip(!(await emulatorsUp()), 'Firebase emulators are not running');
  await clearEmulators();
  await createHelperAccount('sarah@example.com', 'password123', 'Sarah');
});

test('helper sets up a family, pairs the phone, edits settings and sees alerts', async ({ page }) => {
  // 1. Dev sign-in (only shown when the app talks to the emulators).
  await page.goto('/');
  await page.getByText('Developer sign-in (emulators only)').click();
  await page.getByLabel('Email', { exact: true }).fill('sarah@example.com');
  await page.getByLabel('Password').fill('password123');
  await page.getByRole('button', { name: 'Sign in with password' }).click();

  // 2. New helper lands on onboarding and creates the family.
  await expect(page.getByRole('heading', { name: /Welcome, Sarah/ })).toBeVisible();
  await page.getByLabel('Family name').fill('The Rivera family');
  await page.getByLabel('What do you call your parent?').fill('Mom');
  await page.getByLabel(/Your phone number/).fill('+1 555 010 1111');
  await page.getByRole('button', { name: 'Create our family' }).click();

  // 3. Setup code screen.
  await expect(page).toHaveURL(/\/f\/[^/]+\/d\/[^/]+\/setup$/);
  const [, familyId, deviceId] = /\/f\/([^/]+)\/d\/([^/]+)\/setup$/.exec(new URL(page.url()).pathname)!;
  await page.getByRole('button', { name: 'Yes, show the code' }).click();
  const codeText = (await page.getByTestId('setup-code').textContent())!.replace(/\s/g, '');
  expect(codeText).toMatch(/^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}$/);
  await expect(page.getByText(/Works for 1[45]:\d\d/)).toBeVisible();

  // 4. The parent's phone claims the code (anonymous user + claimDeviceInvite).
  const phone = await anonymousPhone();
  const claimed = await callFunction<{ familyId: string; deviceId: string }>('claimDeviceInvite', phone.idToken, {
    code: codeText,
    model: 'Pixel 6a',
    androidVersion: '14',
    appVersion: '1.0.0',
  });
  expect(claimed).toEqual({ familyId, deviceId });
  await expect(page.getByRole('heading', { name: 'Connected!' })).toBeVisible({ timeout: 15_000 });

  // 5. Change the text size and save; the config doc is rewritten with attribution.
  await page.goto(`/f/${familyId}/d/${deviceId}/phone`);
  const slider = page.getByRole('slider', { name: 'Text size' });
  await expect(slider).toBeVisible();
  await slider.focus();
  for (let i = 0; i < 3; i++) await page.keyboard.press('ArrowRight');
  const bar = page.getByRole('region', { name: 'Unsaved changes' });
  await expect(bar).toBeVisible();
  await bar.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByText(/Saved\. Mom’s phone will update/)).toBeVisible();

  await expect
    .poll(async () => (await admin().doc(`families/${familyId}/devices/${deviceId}/config/current`).get()).data()?.display?.fontScale, { timeout: 10_000 })
    .toBeCloseTo(1.6, 5);
  const configDoc = (await admin().doc(`families/${familyId}/devices/${deviceId}/config/current`).get()).data()!;
  expect(configDoc.updatedBy).toMatchObject({ name: 'Sarah', kind: 'helper' });
  expect(typeof configDoc.revision).toBe('number');
  expect(configDoc.updatedAt).toBeTruthy();

  // 6. An event from the phone shows up live in Alerts.
  await page.getByRole('tab', { name: 'Alerts' }).click();
  await injectEvent(familyId!, deviceId!, { type: 'PAUSE_SHOWN', severity: 'NOTICE', data: { app: 'Chase', category: 'BANK', duringCall: 'false', minutesSinceCall: '4' } });
  await expect(page.getByText('Mom opened Chase 4 minutes after a call from an unknown number. A pause card was shown.')).toBeVisible({ timeout: 15_000 });

  // 7. Family home shows the phone as connected with that alert.
  await page.getByRole('link', { name: 'Home' }).first().click();
  await expect(page.getByRole('heading', { name: 'Mom’s phone' })).toBeVisible();
  await expect(page.getByText('Mom paused before opening Chase')).toBeVisible();
});
