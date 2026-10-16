import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { describe, expect, it } from 'vitest';
import { AppRoutes } from '../App';
import { DataProvider } from '../data/DataProvider';
import { createDemoApi } from '../data/demoApi';
import { ToastProvider } from '../components/Toast';
import { DEMO_FAMILY_ID, DEMO_MOM } from '../data/demoFixtures';

function renderAt(path: string) {
  const api = createDemoApi({ latencyMs: 0 });
  render(
    <MemoryRouter initialEntries={[path]}>
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

describe('dashboard (demo data)', () => {
  it('family home shows a card per phone with status and latest alerts', async () => {
    renderAt(`/demo/f/${DEMO_FAMILY_ID}`);
    expect(await screen.findByRole('heading', { name: 'Mom’s phone' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Dad’s phone' })).toBeInTheDocument();
    expect(screen.getByText('Protected')).toBeInTheDocument();
    expect(screen.getByText('Needs attention')).toBeInTheDocument();
    expect(screen.getByText('Screening turned off')).toBeInTheDocument();
    expect(await screen.findByText(/Mom opened Chase 4 minutes after a call from an unknown number/)).toBeInTheDocument();
    expect(screen.getByText(/Aunt Linda is waiting to join/)).toBeInTheDocument();
  });

  it('alerts tab lists every alert newest first, grouped by day', async () => {
    renderAt(`/demo/f/${DEMO_FAMILY_ID}/d/${DEMO_MOM}/alerts`);
    const feed = await screen.findAllByRole('article');
    expect(feed[0]).toHaveAccessibleName(/Mom paused before opening Chase/);
    expect(screen.getByRole('heading', { name: 'Today' })).toBeInTheDocument();
    expect(screen.getAllByRole('article').length).toBe(9);
  });

  it('editing text size shows the save bar and saves the config', async () => {
    const api = renderAt(`/demo/f/${DEMO_FAMILY_ID}/d/${DEMO_MOM}/phone`);
    const slider = await screen.findByRole('slider', { name: 'Text size' });
    expect(slider).toHaveValue('140');
    // Range inputs: set the value the way a user drag would end up.
    const { fireEvent } = await import('@testing-library/react');
    fireEvent.change(slider, { target: { value: '170' } });
    const bar = await screen.findByRole('region', { name: 'Unsaved changes' });
    expect(within(bar).getByText(/1 setting/)).toBeInTheDocument();
    await userEvent.click(within(bar).getByRole('button', { name: 'Save' }));
    expect(await screen.findByText(/Saved. Mom’s phone will update/)).toBeInTheDocument();
    const saved = await new Promise<number>((resolve) => {
      const off = api.watchConfig(DEMO_FAMILY_ID, DEMO_MOM, (c) => {
        resolve(c.config.display.fontScale);
        queueMicrotask(off);
      });
    });
    expect(saved).toBe(1.7);
    expect(screen.queryByRole('region', { name: 'Unsaved changes' })).toBeNull();
  });

  it('tile editor adds a tile and can discard', async () => {
    renderAt(`/demo/f/${DEMO_FAMILY_ID}/d/${DEMO_MOM}/home`);
    const list = await screen.findByRole('list', { name: 'Home screen tiles' });
    const before = within(list).getAllByRole('listitem').length;
    await userEvent.click(screen.getByRole('button', { name: 'Move Phone up' }));
    expect(await screen.findByRole('region', { name: 'Unsaved changes' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Discard' }));
    expect(screen.queryByRole('region', { name: 'Unsaved changes' })).toBeNull();
    expect(within(list).getAllByRole('listitem').length).toBe(before);
  });
});
