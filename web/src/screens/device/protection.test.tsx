/*
 * Track B dashboard parts of GM-SCRN-007 (voicemail mode) and GM-FAM-042 (protection
 * settings from the dashboard), on the Protection tab with the demo API. Saving through
 * the real Firebase API is covered by the e2e suite.
 */
import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { describe, expect, it } from 'vitest';
import { AppRoutes } from '../../App';
import { DataProvider } from '../../data/DataProvider';
import { createDemoApi } from '../../data/demoApi';
import { DEMO_FAMILY_ID, DEMO_MOM } from '../../data/demoFixtures';
import { ToastProvider } from '../../components/Toast';
import type { DeviceConfig } from '../../lib/model';
import { SCREENING_MODES } from '../../lib/model';

function renderProtection() {
  const api = createDemoApi({ latencyMs: 0 });
  render(
    <MemoryRouter initialEntries={[`/demo/f/${DEMO_FAMILY_ID}/d/${DEMO_MOM}/protection`]}>
      <ToastProvider>
        <Routes>
          <Route
            path="/demo/*"
            element={
              <DataProvider mode="demo" base="/demo" api={api}>
                <AppRoutes />
              </DataProvider>
            }
          />
        </Routes>
      </ToastProvider>
    </MemoryRouter>,
  );
  return api;
}

function savedConfig(api: ReturnType<typeof createDemoApi>): Promise<DeviceConfig> {
  return new Promise((resolve) => {
    const off = api.watchConfig(DEMO_FAMILY_ID, DEMO_MOM, (c) => {
      resolve(c.config);
      queueMicrotask(off);
    });
  });
}

async function save() {
  const bar = await screen.findByRole('region', { name: 'Unsaved changes' });
  await userEvent.click(within(bar).getByRole('button', { name: 'Save' }));
  expect(await screen.findByText(/Saved\. Mom’s phone will update/)).toBeInTheDocument();
}

describe('Protection tab (Track B, dashboard side)', () => {
  it('GM-SCRN-007 the dashboard offers "Send to voicemail" and saves it as VOICEMAIL_UNKNOWN', async () => {
    const api = renderProtection();
    const voicemail = await screen.findByRole('radio', { name: /Send to voicemail/ });
    expect(voicemail).not.toBeChecked();
    await userEvent.click(voicemail);
    expect(voicemail).toBeChecked();
    await save();
    const config = await savedConfig(api);
    expect(config.protection.callScreening.mode).toBe('VOICEMAIL_UNKNOWN');
    // A valid value of the shared contract (core/Model.kt ScreeningMode), not coerced away.
    expect(SCREENING_MODES).toContain(config.protection.callScreening.mode);
  });

  it('GM-FAM-042 window, long-call minutes and the safe word are saved as typed', async () => {
    const api = renderProtection();
    fireEvent.change(await screen.findByRole('slider', { name: 'Stay alert after a call for' }), { target: { value: '10' } });
    fireEvent.change(screen.getByRole('slider', { name: 'Tell me after' }), { target: { value: '10' } });
    const word = screen.getByLabelText(/Our safe word/);
    await userEvent.clear(word);
    await userEvent.type(word, 'bluebird');
    expect(word).toHaveAttribute('type', 'password');
    await save();
    const config = await savedConfig(api);
    expect(config.protection.pauseTripwires.windowMinutes).toBe(10);
    expect(config.protection.longCallAlert.minutes).toBe(10);
    expect(config.protection.safeWord.word).toBe('bluebird');
  });
});
