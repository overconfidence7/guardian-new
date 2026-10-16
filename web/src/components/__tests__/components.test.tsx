import { useState } from 'react';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useParams } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { Switch } from '../Switch';
import { Tabs, TabPanel } from '../Tabs';
import { Dialog } from '../Dialog';
import { SortableList } from '../SortableList';
import { Avatar } from '../Avatar';
import { EventItem } from '../EventItem';
import { Field, Input } from '../Field';
import { PhonePreview } from '../PhonePreview';
import { initials } from '../../lib/initials';
import { parseDeviceConfig } from '../../lib/model';
import fixture from '../../../../shared/fixtures/device-config.example.json';
import { DataProvider } from '../../data/DataProvider';
import { createDemoApi } from '../../data/demoApi';

describe('Switch', () => {
  it('is a labelled switch that toggles', async () => {
    function Harness() {
      const [on, setOn] = useState(false);
      return <Switch label="Pause cards" description="Shows a calm card" checked={on} onChange={setOn} />;
    }
    render(<Harness />);
    const sw = screen.getByRole('switch', { name: 'Pause cards' });
    expect(sw).toHaveAttribute('aria-checked', 'false');
    expect(sw).toHaveAccessibleDescription('Shows a calm card');
    await userEvent.click(sw);
    expect(sw).toHaveAttribute('aria-checked', 'true');
    await userEvent.click(screen.getByText('Pause cards'));
    expect(sw).toHaveAttribute('aria-checked', 'false');
  });
});

describe('Tabs', () => {
  it('marks the current tab and moves with arrow keys', async () => {
    function Page() {
      const { tab = 'a' } = useParams();
      return (
        <>
          <Tabs
            label="Sections"
            current={tab}
            items={[
              { id: 'a', label: 'Alpha', to: '/t/a' },
              { id: 'b', label: 'Beta', to: '/t/b' },
              { id: 'c', label: 'Gamma', to: '/t/c' },
            ]}
          />
          <TabPanel current={tab}>Panel {tab}</TabPanel>
        </>
      );
    }
    render(
      <MemoryRouter initialEntries={['/t/a']}>
        <Routes>
          <Route path="/t/:tab" element={<Page />} />
        </Routes>
      </MemoryRouter>,
    );
    const alpha = screen.getByRole('tab', { name: 'Alpha' });
    expect(alpha).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tabpanel')).toHaveTextContent('Panel a');
    alpha.focus();
    await userEvent.keyboard('{ArrowRight}');
    expect(screen.getByRole('tab', { name: 'Beta' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tabpanel')).toHaveTextContent('Panel b');
    await userEvent.keyboard('{End}');
    expect(screen.getByRole('tab', { name: 'Gamma' })).toHaveFocus();
    await userEvent.keyboard('{ArrowRight}');
    expect(screen.getByRole('tab', { name: 'Alpha' })).toHaveAttribute('aria-selected', 'true');
  });
});

describe('Dialog', () => {
  it('renders title and content only while open, and closes', async () => {
    const onClose = vi.fn();
    const { rerender } = render(
      <Dialog open={false} onClose={onClose} title="Remove phone?">
        Body text
      </Dialog>,
    );
    expect(screen.queryByText('Body text')).toBeNull();
    rerender(
      <Dialog open onClose={onClose} title="Remove phone?" description="This can’t be undone.">
        Body text
      </Dialog>,
    );
    expect(screen.getByText('Body text')).toBeInTheDocument();
    const dialog = screen.getByRole('dialog', { name: 'Remove phone?' });
    expect(dialog).toHaveAccessibleDescription('This can’t be undone.');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Close' }));
    expect(onClose).toHaveBeenCalled();
  });
});

describe('SortableList', () => {
  it('reorders with buttons and the keyboard, and announces moves', async () => {
    function Harness() {
      const [items, setItems] = useState(['Sarah', 'David', 'Phone']);
      return (
        <SortableList
          label="Tiles"
          items={items}
          getId={(s) => s}
          getLabel={(s) => s}
          onReorder={(from, to) =>
            setItems((list) => {
              const copy = list.slice();
              const [x] = copy.splice(from, 1);
              copy.splice(to, 0, x!);
              return copy;
            })
          }
          renderItem={(s) => <span>{s}</span>}
        />
      );
    }
    render(<Harness />);
    const order = () => screen.getAllByRole('listitem').map((li) => li.textContent);
    expect(screen.getByRole('button', { name: 'Move Sarah up' })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: 'Move Sarah down' }));
    expect(order()).toEqual(['David', 'Sarah', 'Phone']);
    expect(screen.getByText('Sarah moved to position 2 of 3.')).toBeInTheDocument();
    screen.getByRole('button', { name: /Reorder Phone/ }).focus();
    await userEvent.keyboard('{ArrowUp}{ArrowUp}');
    expect(order()).toEqual(['Phone', 'David', 'Sarah']);
  });
});

describe('Avatar & Field', () => {
  it('initials', () => {
    expect(initials('Sarah Rivera')).toBe('SR');
    expect(initials('Dr. Patel')).toBe('DP');
    expect(initials('  ')).toBe('?');
    render(<Avatar name="Mom" label="Mom" />);
    expect(screen.getByRole('img', { name: 'Mom' })).toHaveTextContent('M');
  });
  it('wires label, hint and error', () => {
    render(<Field label="Name" hint="As they know you" error="Required">{(ids) => <Input {...ids} />}</Field>);
    const input = screen.getByLabelText('Name');
    expect(input).toHaveAccessibleDescription('As they know you Required');
    expect(input).toHaveAttribute('aria-invalid', 'true');
  });
});

describe('PhonePreview', () => {
  it('renders the parent home screen from config', () => {
    const config = parseDeviceConfig(fixture);
    render(<PhonePreview config={config} photo={{ fromName: 'Sarah', caption: 'Hi Mom' }} apps={[{ packageName: 'com.whatsapp', label: 'WhatsApp' }]} />);
    expect(screen.getByText('Call Sarah')).toBeInTheDocument();
    expect(screen.getByText('WhatsApp')).toBeInTheDocument();
    expect(screen.getByText('Today from Sarah')).toBeInTheDocument();
    expect(screen.getByText(/Protected/)).toBeInTheDocument();
  });
});

describe('EventItem', () => {
  it('shows urgent events calmly with a suggestion', async () => {
    const api = createDemoApi({ latencyMs: 0 });
    render(
      <DataProvider mode="demo" base="/demo" api={api}>
        <EventItem
          now={Date.now()}
          parentName="Mom"
          event={{ id: 'e', type: 'PAUSE_SHOWN', severity: 'URGENT', at: Date.now() - 60_000, data: { app: 'Zelle', duringCall: 'true' }, createdAt: Date.now(), source: 'device', deliveredTo: [] }}
        />
      </DataProvider>,
    );
    const article = await screen.findByRole('article', { name: /Needs you now: Mom paused before opening Zelle/ });
    expect(article).toHaveClass('event-urgent');
    expect(within(article).getByText(/A quick call to Mom usually helps/)).toBeInTheDocument();
  });
});
