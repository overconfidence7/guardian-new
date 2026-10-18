/* Screenshots of key screens from the fixture-backed demo (no backend). */
import { test } from '@playwright/test';

const OUT = 'docs-screenshots';
const F = 'demo-family';
const MOM = 'mom-phone';

const SHOTS: { name: string; path: string; full?: boolean }[] = [
  // The real app's landing page: signed out (auth state resolves locally, no backend needed).
  { name: 'landing', path: '/' },
  { name: 'family-home', path: `/demo/f/${F}` },
  { name: 'alerts', path: `/demo/f/${F}/d/${MOM}/alerts` },
  { name: 'home-screen-editor', path: `/demo/f/${F}/d/${MOM}/home` },
  { name: 'people', path: `/demo/f/${F}/d/${MOM}/people` },
  { name: 'photos', path: `/demo/f/${F}/d/${MOM}/photos` },
  { name: 'protection', path: `/demo/f/${F}/d/${MOM}/protection` },
  { name: 'reminders', path: `/demo/f/${F}/d/${MOM}/reminders` },
  { name: 'phone-settings', path: `/demo/f/${F}/d/${MOM}/phone` },
  { name: 'recent-changes', path: `/demo/f/${F}/d/${MOM}/changes` },
  { name: 'family-settings', path: `/demo/f/${F}/settings` },
  { name: 'onboarding', path: '/demo/welcome' },
];

for (const width of [390, 1280]) {
  test.describe(`at ${width}px`, () => {
    test.use({ viewport: { width, height: width < 600 ? 844 : 900 }, deviceScaleFactor: width < 600 ? 2 : 1 });
    for (const shot of SHOTS) {
      test(shot.name, async ({ page }) => {
        await page.goto(shot.path);
        await page.waitForLoadState('networkidle');
        await page.evaluate(() => document.fonts.ready);
        await page.waitForTimeout(400);
        await page.screenshot({ path: `${OUT}/${shot.name}-${width}.png`, fullPage: shot.full ?? true });
      });
    }
    test('setup-code', async ({ page }) => {
      await page.goto(`/demo/welcome`);
      await page.getByLabel('Family name').fill('The Rivera family');
      await page.getByLabel('What do you call your parent?').fill('Mom');
      await page.getByRole('button', { name: 'Create our family' }).click();
      await page.getByRole('button', { name: 'Yes, show the code' }).click();
      await page.getByTestId('setup-code').waitFor();
      await page.evaluate(() => window.scrollTo(0, 0));
      await page.waitForTimeout(300);
      await page.screenshot({ path: `${OUT}/setup-code-${width}.png`, fullPage: true });
    });
    test('dark-alerts', async ({ page }) => {
      await page.emulateMedia({ colorScheme: 'dark' });
      await page.goto(`/demo/f/${F}/d/${MOM}/alerts`);
      await page.waitForLoadState('networkidle');
      await page.evaluate(() => document.fonts.ready);
      await page.screenshot({ path: `${OUT}/alerts-dark-${width}.png`, fullPage: true });
    });
  });
}
