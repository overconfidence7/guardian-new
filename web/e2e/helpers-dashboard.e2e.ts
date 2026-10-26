/*
 * Track B on the dashboard (qa/antigravity/cases/13-family-linking-e2e.md and 08): what a
 * helper sees while the parent's phone (played through the callables and the Firestore
 * REST API with the phone's own anonymous ID token) pairs, approves helpers, sends events
 * and leaves.
 *
 * Every test makes its own family and accounts and never clears the emulators, so it can
 * run next to dashboard.e2e.ts. Run with the emulators up, like dashboard.e2e.ts.
 */
import { expect, test, type Page } from '@playwright/test';
import { admin, anonymousPhone, callFunction, createHelper, emulatorsUp, phoneCreateEvent, uniqueEmail } from './emulator';

const PASSWORD = 'password123';

test.beforeAll(async () => {
  test.skip(!(await emulatorsUp()), 'Firebase emulators are not running');
});

interface Family {
  sarahEmail: string;
  familyId: string;
  deviceId: string;
  phone: { uid: string; idToken: string };
  sarah: { uid: string; idToken: string };
}

/** Sarah's family on the family plan, with Mom's phone paired. */
async function pairedFamily(): Promise<Family> {
  const sarahEmail = uniqueEmail('sarah');
  const sarah = await createHelper(sarahEmail, PASSWORD, 'Sarah');
  const { familyId, deviceId } = await callFunction<{ familyId: string; deviceId: string }>('createFamily', sarah.idToken, {
    familyName: 'The Rivera family',
    parentName: 'Mom',
    helperPhone: '+1 555 010 1111',
    timezone: 'America/Chicago',
  });
  await admin().doc(`families/${familyId}`).update({ plan: 'family' });
  const { code } = await callFunction<{ code: string }>('createDeviceInvite', sarah.idToken, { familyId, deviceId });
  const phone = await anonymousPhone();
  await callFunction('claimDeviceInvite', phone.idToken, { code, model: 'Pixel 6a', androidVersion: '14', appVersion: '1.0.0' });
  return { sarahEmail, familyId, deviceId, phone, sarah };
}

async function signIn(page: Page, email: string) {
  await page.goto('/');
  await page.getByText('Developer sign-in (emulators only)').click();
  await page.getByLabel('Email', { exact: true }).fill(email);
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Sign in with password' }).click();
  await expect(page.getByText('Developer sign-in (emulators only)')).toHaveCount(0, { timeout: 15_000 });
}

/** A helper accepts an invite link on the dashboard and is left waiting for the phone. */
async function joinAsPendingHelper(page: Page, family: Family, name: string): Promise<{ uid: string }> {
  const email = uniqueEmail(name);
  const helper = await createHelper(email, PASSWORD, name);
  const { code } = await callFunction<{ code: string }>('createHelperInvite', family.sarah.idToken, { familyId: family.familyId });
  await signIn(page, email);
  await page.goto(`/join/${code}`);
  await expect(page.getByRole('heading', { name: 'Join your family' })).toBeVisible();
  await page.getByLabel('Your name').fill(name);
  await page.getByRole('button', { name: 'Join the family' }).click();
  await expect(page.getByRole('heading', { name: 'Waiting for approval on the phone' })).toBeVisible({ timeout: 15_000 });
  return { uid: helper.uid };
}

test('GM-FAM-001 a returning helper who signs in lands on their family, not on "create a family"', async ({ page }) => {
  const family = await pairedFamily();
  await signIn(page, family.sarahEmail);
  await expect(page.getByRole('heading', { name: 'Mom’s phone', exact: true })).toBeVisible({ timeout: 15_000 });
  await expect(page.getByRole('heading', { name: /Welcome, Sarah/ })).toHaveCount(0);
});

test('GM-FAM-020 GM-FAM-021 a second helper waits until the parent says yes on the phone, then sees the phone', async ({ page }) => {
  const family = await pairedFamily();
  const david = await joinAsPendingHelper(page, family, 'David');
  const member = (await admin().doc(`families/${family.familyId}/members/${david.uid}`).get()).data();
  expect(member).toMatchObject({ status: 'pending', displayName: 'David' });
  expect((await admin().doc(`families/${family.familyId}`).get()).get('memberUids')).toEqual([family.sarah.uid]);

  await callFunction('approveHelper', family.phone.idToken, { familyId: family.familyId, uid: david.uid, approve: true });
  await expect(page.getByRole('heading', { name: 'You’re in!' })).toBeVisible({ timeout: 15_000 });
  // Exact: the "David is now helping with Mom’s phone" event heading also contains the name,
  // and shows up whenever the approval event arrives first (daily run 40352114385).
  await expect(page.getByRole('heading', { name: 'Mom’s phone', exact: true })).toBeVisible({ timeout: 15_000 });
});

test('GM-FAM-021 GM-SCAM-007 a pending helper opening the family page gets nothing', async ({ page }) => {
  const family = await pairedFamily();
  await joinAsPendingHelper(page, family, 'Support');
  await page.goto(`/f/${family.familyId}`);
  await expect(page.getByText('We couldn’t open this family')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByRole('heading', { name: 'Mom’s phone' })).toHaveCount(0);
});

test('GM-FAM-020 the parent saying no on the phone tells the waiting helper', async ({ page }) => {
  const family = await pairedFamily();
  const emma = await joinAsPendingHelper(page, family, 'Emma');
  await callFunction('approveHelper', family.phone.idToken, { familyId: family.familyId, uid: emma.uid, approve: false });
  await expect(page.getByText('The invite wasn’t approved')).toBeVisible({ timeout: 15_000 });
});

test('GM-FAM-020 Sarah sees the new helper as waiting for approval on the phone', async ({ browser, page }) => {
  const family = await pairedFamily();
  const other = await browser.newContext();
  try {
    await joinAsPendingHelper(await other.newPage(), family, 'David');
  } finally {
    await other.close();
  }
  await signIn(page, family.sarahEmail);
  await page.goto(`/f/${family.familyId}/settings`);
  const row = page.getByRole('listitem').filter({ hasText: 'David' });
  await expect(row.getByText('Waiting for approval')).toBeVisible({ timeout: 15_000 });
  await expect(row.getByText('Mom needs to approve them on the phone')).toBeVisible();
});

test('GM-FAM-040 GM-HELP-006 events the phone sends show in Alerts with the helper wording, urgent ones marked', async ({ page }) => {
  const family = await pairedFamily();
  await signIn(page, family.sarahEmail);
  await page.goto(`/f/${family.familyId}/d/${family.deviceId}/alerts`);
  const at = { familyId: family.familyId, deviceId: family.deviceId };
  const send = (eventId: string, type: string, severity: string, data: Record<string, string>) =>
    phoneCreateEvent(family.phone.idToken, { ...at, eventId }, { type, severity, data });

  await send('e1', 'PAUSE_SHOWN', 'NOTICE', { app: 'Chase', category: 'BANK', duringCall: 'false', minutesSinceCall: '4' });
  await send('e2', 'REMOTE_ACCESS_INSTALLED', 'URGENT', { app: 'AnyDesk', afterUnknownCall: 'true' });
  await send('e3', 'HELP_REQUESTED', 'URGENT', { note: 'Is this real?', path: `families/${family.familyId}/devices/${family.deviceId}/help/e3.jpg` });
  await send('e4', 'LONG_UNKNOWN_CALL', 'NOTICE', { minutes: '20', ongoing: 'true' });

  await expect(page.getByText('Mom opened Chase 4 minutes after a call from an unknown number. A pause card was shown.')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByRole('article', { name: 'Needs you now: AnyDesk was installed on Mom’s phone' })).toBeVisible();
  await expect(page.getByRole('article', { name: 'Needs you now: Mom would like some help' })).toBeVisible();
  await expect(page.getByText('Mom sent you a picture of their screen: “Is this real?”')).toBeVisible();
  await expect(page.getByRole('article', { name: 'Good to know: Mom is on a long call' })).toBeVisible();
  await expect(page.getByText('Mom has been on a call with an unknown number for 20 minutes.')).toBeVisible();
});

test('GM-FAM-060 the dashboard shows the phone gone after "Stop using Guardian Mode"', async ({ page }) => {
  const family = await pairedFamily();
  await signIn(page, family.sarahEmail);
  await page.goto(`/f/${family.familyId}`);
  await expect(page.getByRole('heading', { name: 'Mom’s phone', exact: true })).toBeVisible({ timeout: 15_000 });
  await callFunction('deleteDevice', family.phone.idToken, { familyId: family.familyId, deviceId: family.deviceId });
  await expect(page.getByRole('heading', { name: 'Mom’s phone', exact: true })).toHaveCount(0, { timeout: 15_000 });
  await expect(page.getByText('No phones yet')).toBeVisible();
});

test('GM-SCRN-007 Sarah sets "Send to voicemail" on the dashboard and it reaches the config', async ({ page }) => {
  const family = await pairedFamily();
  await signIn(page, family.sarahEmail);
  await page.goto(`/f/${family.familyId}/d/${family.deviceId}/protection`);
  await page.getByRole('radio', { name: /Send to voicemail/ }).check();
  const bar = page.getByRole('region', { name: 'Unsaved changes' });
  await bar.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByText(/Saved\. Mom’s phone will update/)).toBeVisible();
  await expect
    .poll(async () => (await admin().doc(`families/${family.familyId}/devices/${family.deviceId}/config/current`).get()).get('protection.callScreening.mode'), {
      timeout: 10_000,
    })
    .toBe('VOICEMAIL_UNKNOWN');
});
