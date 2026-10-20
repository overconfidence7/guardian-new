import { useMemo, useState } from 'react';
import { usePhotos, useFileUrl } from '../../data/hooks';
import { Avatar } from '../../components/Avatar';
import { Button, IconButton } from '../../components/Button';
import { Card, CardHeader } from '../../components/Card';
import { Dialog } from '../../components/Dialog';
import { EmptyState, Notice, Spinner } from '../../components/EmptyState';
import { Field, Input, RadioCards } from '../../components/Field';
import { Icon, type IconName } from '../../components/Icon';
import { PhonePreview } from '../../components/PhonePreview';
import { SortableList } from '../../components/SortableList';
import { Switch } from '../../components/Switch';
import { TILE_KINDS, type HomeTile, type TileKind } from '../../lib/model';
import { callablePeople, makeTile, moveItem, removeById, starterTiles, TILE_KIND_INFO, tileLabel, tileProblem } from '../../lib/tiles';
import type { Device } from '../../lib/types';
import { useConfigEditor } from './ConfigEditor';

const KIND_ICON: Record<TileKind, IconName> = {
  CALL_CONTACT: 'user',
  APP: 'apps',
  PHONE: 'phone',
  MESSAGES: 'message',
  CAMERA: 'camera',
  PHOTOS: 'image',
  REMINDERS: 'bell',
  HELP: 'hand',
  ALL_APPS: 'grid',
};

function TileDialog({ open, onClose, device, initial, onSave }: { open: boolean; onClose: () => void; device: Device; initial: HomeTile | null; onSave: (tile: HomeTile) => void }) {
  const { config } = useConfigEditor();
  const [kind, setKind] = useState<TileKind>(initial?.kind ?? 'CALL_CONTACT');
  const [target, setTarget] = useState(initial?.target ?? '');
  const [label, setLabel] = useState(initial?.label ?? '');
  const [query, setQuery] = useState('');
  if (!config) return null;
  const people = callablePeople(config);
  const apps = device.installedApps
    .filter((a) => a.label.toLowerCase().includes(query.trim().toLowerCase()))
    .sort((a, b) => a.label.localeCompare(b.label));
  const needsTarget = TILE_KIND_INFO[kind].needsTarget;
  const canSave = !needsTarget || !!target;

  function save() {
    const base = initial ?? makeTile(kind);
    const defaultLabel =
      kind === 'CALL_CONTACT' ? (people.find((p) => p.id === target)?.name ?? '') : kind === 'APP' ? (device.installedApps.find((a) => a.packageName === target)?.label ?? '') : TILE_KIND_INFO[kind].label;
    onSave({ ...base, kind, target: needsTarget ? target : '', label: label.trim() || defaultLabel });
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      size="lg"
      title={initial ? 'Edit tile' : 'Add a tile'}
      description="Tiles are big buttons on the home screen. Keep it to the few things they use every day."
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" onClick={save} disabled={!canSave}>
            {initial ? 'Done' : 'Add tile'}
          </Button>
        </>
      }
    >
      {!initial ? (
        <RadioCards
          legend="What should the tile do?"
          name="tile-kind"
          value={kind}
          onChange={(k) => {
            setKind(k);
            setTarget('');
          }}
          options={TILE_KINDS.map((k) => ({ value: k, title: TILE_KIND_INFO[k].label, description: TILE_KIND_INFO[k].description }))}
        />
      ) : null}

      {kind === 'CALL_CONTACT' ? (
        people.length === 0 ? (
          <Notice tone="info" title="Add people first">
            Add family or contacts in the People tab, then come back to make a call tile.
          </Notice>
        ) : (
          <fieldset className="picker">
            <legend className="field-label">Who should it call?</legend>
            <div className="picker-grid">
              {people.map((p) => (
                <label key={p.id} className={['pick', target === p.id ? 'is-checked' : ''].join(' ')}>
                  <input type="radio" name="tile-person" checked={target === p.id} onChange={() => setTarget(p.id)} />
                  <Avatar name={p.name} src={p.photoUrl} size={40} />
                  <span className="pick-text">
                    <strong>{p.name}</strong>
                    <span className="small subtle">{p.phone || 'No number yet'}</span>
                  </span>
                </label>
              ))}
            </div>
          </fieldset>
        )
      ) : null}

      {kind === 'APP' ? (
        device.installedApps.length === 0 ? (
          <Notice tone="info" title="No apps reported yet">
            Once the phone is connected, it sends its list of apps so you can pick one here.
          </Notice>
        ) : (
          <fieldset className="picker">
            <legend className="field-label">Which app?</legend>
            <Input type="search" placeholder="Search apps" aria-label="Search apps" value={query} onChange={(e) => setQuery(e.target.value)} />
            <div className="picker-list">
              {apps.map((a) => (
                <label key={a.packageName} className={['pick', target === a.packageName ? 'is-checked' : ''].join(' ')}>
                  <input type="radio" name="tile-app" checked={target === a.packageName} onChange={() => setTarget(a.packageName)} />
                  <span className="app-glyph" aria-hidden="true">
                    {a.label.slice(0, 1)}
                  </span>
                  <span className="pick-text">
                    <strong>{a.label}</strong>
                  </span>
                </label>
              ))}
            </div>
          </fieldset>
        )
      ) : null}

      <Field label="Label on the tile" optional hint="Short and familiar works best, like “Sarah” or “Weather”.">
        {(ids) => <Input {...ids} maxLength={24} placeholder={TILE_KIND_INFO[kind].needsTarget ? 'Uses the name' : TILE_KIND_INFO[kind].label} value={label} onChange={(e) => setLabel(e.target.value)} />}
      </Field>
    </Dialog>
  );
}

export function HomeScreenTab({ familyId, device }: { familyId: string; device: Device }) {
  const { config, update, loading } = useConfigEditor();
  const photos = usePhotos(familyId, device.id);
  const latest = photos.data?.[0];
  const latestUrl = useFileUrl(latest?.storagePath);
  const [dialog, setDialog] = useState<{ tile: HomeTile | null } | null>(null);
  const [previewOpen, setPreviewOpen] = useState(false);
  const [highlight, setHighlight] = useState<string | null>(null);

  const preview = useMemo(
    () => (config ? <PhonePreview config={config} apps={device.installedApps} photo={latest ? { url: latestUrl, fromName: latest.fromName, caption: latest.caption } : null} highlightTileId={highlight} /> : null),
    [config, device.installedApps, latest, latestUrl, highlight],
  );

  if (loading || !config) return <Spinner />;
  const tiles = config.home.tiles;

  return (
    <div className="split">
      <div className="split-main stack" style={{ ['--gap' as string]: 'var(--s-5)' }}>
        <Card>
          <CardHeader
            title="Tiles"
            description={`Top to bottom, left to right, as ${device.parentName} sees them.`}
            action={
              <Button variant="primary" icon="plus" onClick={() => setDialog({ tile: null })}>
                Add tile
              </Button>
            }
          />
          {tiles.length === 0 ? (
            <EmptyState
              icon="grid"
              title="No tiles yet"
              action={
                <Button variant="quiet" icon="sparkle" onClick={() => update((c) => void (c.home.tiles = starterTiles(c)))}>
                  Start with a simple layout
                </Button>
              }
            >
              We suggest the people they call most, then Phone, Messages, Camera and Photos.
            </EmptyState>
          ) : (
            <SortableList
              label="Home screen tiles"
              items={tiles}
              getId={(t) => t.id}
              getLabel={(t) => tileLabel(t, config, device.installedApps)}
              onReorder={(from, to) => update((c) => void (c.home.tiles = moveItem(c.home.tiles, from, to)))}
              renderItem={(tile) => {
                const problem = tileProblem(tile, config, device.installedApps);
                const person = tile.kind === 'CALL_CONTACT' ? callablePeople(config).find((p) => p.id === tile.target) : undefined;
                return (
                  <div className="tile-row" onMouseEnter={() => setHighlight(tile.id)} onMouseLeave={() => setHighlight(null)}>
                    {person ? (
                      <Avatar name={person.name} src={person.photoUrl} size={40} />
                    ) : (
                      <span className="tile-row-icon" aria-hidden="true">
                        <Icon name={KIND_ICON[tile.kind]} size={20} />
                      </span>
                    )}
                    <div className="grow">
                      <div className="tile-row-label">{tile.kind === 'CALL_CONTACT' ? `Call ${tileLabel(tile, config, device.installedApps)}` : tileLabel(tile, config, device.installedApps)}</div>
                      <div className={problem ? 'tile-row-problem' : 'tile-row-kind'}>{problem ?? (tile.kind === 'APP' || tile.kind === 'CALL_CONTACT' || tileLabel(tile, config, device.installedApps) !== TILE_KIND_INFO[tile.kind].label ? TILE_KIND_INFO[tile.kind].label : TILE_KIND_INFO[tile.kind].description)}</div>
                    </div>
                    <IconButton icon="edit" size="sm" label={`Edit ${tileLabel(tile, config, device.installedApps)}`} onClick={() => setDialog({ tile })} />
                    <IconButton icon="trash" size="sm" variant="danger" label={`Remove ${tileLabel(tile, config, device.installedApps)}`} onClick={() => update((c) => void (c.home.tiles = removeById(c.home.tiles, tile.id)))} />
                  </div>
                );
              }}
            />
          )}
        </Card>

        <Card>
          <CardHeader title="Layout" />
          <div className="stack" style={{ ['--gap' as string]: 'var(--s-5)' }}>
            <Switch
              label="Photo card"
              description="Shows the newest photo the family sent, at the top of the home screen."
              checked={config.home.showPhotoCard}
              onChange={(v) => update((c) => void (c.home.showPhotoCard = v))}
            />
            <RadioCards
              legend="Tile size"
              name="columns"
              value={String(config.home.columns) as '2' | '3'}
              onChange={(v) => update((c) => void (c.home.columns = Number(v)))}
              options={[
                { value: '2', title: 'Big tiles, 2 across', description: 'Easiest to see and tap. Best for most people.', badge: 'Recommended' },
                { value: '3', title: 'Smaller tiles, 3 across', description: 'More fits on one screen, but each tile is smaller.' },
              ]}
            />
          </div>
        </Card>

        <div className="mobile-only">
          <Button variant="secondary" size="lg" block icon="smartphone" onClick={() => setPreviewOpen(true)}>
            Preview {device.parentName}’s home screen
          </Button>
        </div>
      </div>

      <aside className="split-side desktop-only" aria-label="Live preview">
        <div className="sticky-preview">
          <p className="preview-caption">
            <Icon name="eye" size={18} /> Live preview · scroll to see more
          </p>
          {preview}
        </div>
      </aside>

      <Dialog open={previewOpen} onClose={() => setPreviewOpen(false)} title={`${device.parentName}’s home screen`} description="Roughly how it will look on the phone, with your unsaved changes.">
        {preview}
      </Dialog>

      {dialog ? (
        <TileDialog
          key={dialog.tile?.id ?? 'new'}
          open
          onClose={() => setDialog(null)}
          device={device}
          initial={dialog.tile}
          onSave={(tile) => {
            update((c) => {
              const i = c.home.tiles.findIndex((t) => t.id === tile.id);
              if (i >= 0) c.home.tiles[i] = tile;
              else c.home.tiles.push(tile);
            });
            setDialog(null);
          }}
        />
      ) : null}
    </div>
  );
}
