import { useRef, useState } from 'react';
import { friendlyError } from '../../data/api';
import { useData } from '../../data/DataProvider';
import { useMembers } from '../../data/hooks';
import { Avatar } from '../../components/Avatar';
import { Button, IconButton } from '../../components/Button';
import { Card, CardHeader } from '../../components/Card';
import { Dialog } from '../../components/Dialog';
import { Badge, EmptyState, Spinner } from '../../components/EmptyState';
import { Checkbox, Field, Input } from '../../components/Field';
import { useToast } from '../../components/Toast';
import { newId } from '../../lib/ids';
import { CONTACT_PHOTO_MAX_EDGE, resizeImage } from '../../lib/image';
import type { AllowedNumber, Contact, FamilyContact } from '../../lib/model';
import type { Device } from '../../lib/types';
import { useConfigEditor } from './ConfigEditor';

type PersonKind = 'family' | 'contact';
interface PersonDraft {
  id: string;
  name: string;
  phone: string;
  relation: string;
  note: string;
  photoUrl: string | null;
  isPrimaryHelper: boolean;
}

const digits = (s: string) => s.replace(/\D/g, '');

function PersonDialog({ kind, initial, familyId, device, onClose, onSave }: { kind: PersonKind; initial: PersonDraft; familyId: string; device: Device; onClose: () => void; onSave: (p: PersonDraft) => void }) {
  const { api } = useData();
  const toast = useToast();
  const [p, setP] = useState(initial);
  const [uploading, setUploading] = useState(false);
  const [errors, setErrors] = useState<{ name?: string; phone?: string }>({});
  const fileRef = useRef<HTMLInputElement>(null);
  const isNew = !initial.name;

  async function onFile(file: File | undefined) {
    if (!file) return;
    setUploading(true);
    try {
      const { blob } = await resizeImage(file, { maxEdge: CONTACT_PHOTO_MAX_EDGE, square: true });
      const url = await api.uploadContactPhoto(familyId, device.id, blob);
      setP((x) => ({ ...x, photoUrl: url }));
    } catch (e) {
      toast(friendlyError(e), 'error');
    } finally {
      setUploading(false);
    }
  }

  function save() {
    const next: typeof errors = {};
    if (!p.name.trim()) next.name = 'Please add a name.';
    if (kind === 'contact' && digits(p.phone).length < 3) next.phone = 'Please add their phone number.';
    if (p.phone.trim() && digits(p.phone).length < 3) next.phone = 'That number looks too short.';
    setErrors(next);
    if (Object.keys(next).length) return;
    onSave({ ...p, name: p.name.trim(), phone: p.phone.trim(), relation: p.relation.trim(), note: p.note.trim() });
  }

  return (
    <Dialog
      open
      onClose={onClose}
      title={isNew ? (kind === 'family' ? 'Add a family member' : 'Add a contact') : `Edit ${initial.name}`}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" onClick={save} disabled={uploading}>
            {isNew ? 'Add' : 'Done'}
          </Button>
        </>
      }
    >
      <div className="photo-pick">
        <Avatar name={p.name || '?'} src={p.photoUrl} size={88} />
        <div className="stack" style={{ ['--gap' as string]: 'var(--s-2)' }}>
          <input ref={fileRef} type="file" accept="image/*" className="sr-only" tabIndex={-1} onChange={(e) => void onFile(e.target.files?.[0])} />
          <Button variant="secondary" icon="camera" loading={uploading} onClick={() => fileRef.current?.click()}>
            {p.photoUrl ? 'Change photo' : 'Add a photo'}
          </Button>
          {p.photoUrl ? (
            <Button variant="ghost" size="sm" onClick={() => setP({ ...p, photoUrl: null })}>
              Remove photo
            </Button>
          ) : (
            <p className="small subtle">A clear face photo makes the tile easy to recognize.</p>
          )}
        </div>
      </div>
      <Field label="Name" error={errors.name}>
        {(ids) => <Input {...ids} value={p.name} onChange={(e) => setP({ ...p, name: e.target.value })} placeholder={kind === 'family' ? 'Sarah' : 'Dr. Patel'} />}
      </Field>
      {kind === 'family' ? (
        <Field label="Relation" optional>
          {(ids) => <Input {...ids} value={p.relation} onChange={(e) => setP({ ...p, relation: e.target.value })} placeholder="Daughter" />}
        </Field>
      ) : null}
      <Field label="Phone number" optional={kind === 'family'} error={errors.phone}>
        {(ids) => <Input {...ids} type="tel" inputMode="tel" value={p.phone} onChange={(e) => setP({ ...p, phone: e.target.value })} placeholder="+1 555 010 1111" />}
      </Field>
      {kind === 'contact' ? (
        <Field label="Note" optional hint="Shown under their name, like “Family doctor”.">
          {(ids) => <Input {...ids} value={p.note} onChange={(e) => setP({ ...p, note: e.target.value })} />}
        </Field>
      ) : (
        <Checkbox
          checked={p.isPrimaryHelper}
          onChange={(v) => setP({ ...p, isPrimaryHelper: v })}
          label="Primary helper"
          description={`The person every “Call ___ first” button on ${device.parentName}’s phone dials.`}
        />
      )}
    </Dialog>
  );
}

function AllowDialog({ initial, onClose, onSave }: { initial: AllowedNumber | null; onClose: () => void; onSave: (n: AllowedNumber) => void }) {
  const [label, setLabel] = useState(initial?.label ?? '');
  const [number, setNumber] = useState(initial?.number ?? '');
  const ok = label.trim() && digits(number).length >= 3;
  return (
    <Dialog
      open
      size="sm"
      onClose={onClose}
      title={initial ? 'Edit number' : 'Add a number that always rings'}
      description="For places that call from numbers that aren’t in their contacts, like the pharmacy, doctor’s office or church."
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" disabled={!ok} onClick={() => onSave({ label: label.trim(), number: number.trim() })}>
            {initial ? 'Done' : 'Add'}
          </Button>
        </>
      }
    >
      <Field label="Who is it?">{(ids) => <Input {...ids} value={label} onChange={(e) => setLabel(e.target.value)} placeholder="CVS Pharmacy" />}</Field>
      <Field label="Phone number">{(ids) => <Input {...ids} type="tel" inputMode="tel" value={number} onChange={(e) => setNumber(e.target.value)} placeholder="+1 555 010 5555" />}</Field>
    </Dialog>
  );
}

export function PeopleTab({ familyId, device }: { familyId: string; device: Device }) {
  const { config, update, loading } = useConfigEditor();
  const members = useMembers(familyId);
  const [editing, setEditing] = useState<{ kind: PersonKind; person: PersonDraft } | null>(null);
  const [allow, setAllow] = useState<{ index: number; value: AllowedNumber | null } | null>(null);

  if (loading || !config) return <Spinner />;

  const blank = (): PersonDraft => ({ id: '', name: '', phone: '', relation: '', note: '', photoUrl: null, isPrimaryHelper: false });
  const fromFamily = (f: FamilyContact): PersonDraft => ({ ...blank(), ...f, photoUrl: f.photoUrl ?? null, note: '' });
  const fromContact = (c: Contact): PersonDraft => ({ ...blank(), ...c, photoUrl: c.photoUrl ?? null });

  const helpersNotListed = (members.data ?? []).filter((m) => m.status === 'active' && !config.family.some((f) => f.name.toLowerCase() === m.displayName.toLowerCase()));

  function savePerson(kind: PersonKind, p: PersonDraft) {
    update((c) => {
      if (kind === 'family') {
        const entry: FamilyContact = { id: p.id || newId('fam'), name: p.name, relation: p.relation, phone: p.phone, photoUrl: p.photoUrl, isPrimaryHelper: p.isPrimaryHelper };
        if (entry.isPrimaryHelper) c.family = c.family.map((f) => ({ ...f, isPrimaryHelper: false }));
        const i = c.family.findIndex((f) => f.id === entry.id);
        if (i >= 0) c.family[i] = entry;
        else c.family.push(entry);
      } else {
        const entry: Contact = { id: p.id || newId('c'), name: p.name, phone: p.phone, photoUrl: p.photoUrl, note: p.note };
        const i = c.contacts.findIndex((x) => x.id === entry.id);
        if (i >= 0) c.contacts[i] = entry;
        else c.contacts.push(entry);
      }
    });
    setEditing(null);
  }

  function removePerson(kind: PersonKind, id: string) {
    update((c) => {
      if (kind === 'family') c.family = c.family.filter((f) => f.id !== id);
      else c.contacts = c.contacts.filter((x) => x.id !== id);
      // A call tile pointing at someone who is gone would do nothing; remove it too.
      c.home.tiles = c.home.tiles.filter((t) => !(t.kind === 'CALL_CONTACT' && t.target === id));
    });
  }

  const primary = config.family.find((f) => f.isPrimaryHelper && f.phone.trim()) ?? config.family.find((f) => f.phone.trim());

  return (
    <div className="stack" style={{ ['--gap' as string]: 'var(--s-5)' }}>
      <Card>
        <CardHeader
          title="Family"
          description={primary ? `“Call ${primary.name} first” buttons on ${device.parentName}’s phone dial ${primary.name}. Family calls always ring through.` : 'Add at least one person with a phone number so “Call first” buttons work.'}
          action={
            <Button variant="primary" icon="plus" onClick={() => setEditing({ kind: 'family', person: blank() })}>
              Add
            </Button>
          }
        />
        {config.family.length === 0 ? (
          <EmptyState icon="users" title="No family yet">
            Family members can have call tiles and always ring through screening. The pause card also suggests calling them.
          </EmptyState>
        ) : (
          <ul className="people">
            {config.family.map((f) => (
              <li key={f.id} className="person">
                <Avatar name={f.name} src={f.photoUrl} size={52} />
                <div className="grow">
                  <div className="person-name">
                    {f.name}
                    {primary?.id === f.id ? <Badge tone="brand">Primary helper</Badge> : null}
                  </div>
                  <div className="person-meta">{[f.relation, f.phone || 'No number'].filter(Boolean).join(' · ')}</div>
                </div>
                <IconButton icon="edit" label={`Edit ${f.name}`} onClick={() => setEditing({ kind: 'family', person: fromFamily(f) })} />
                <IconButton icon="trash" variant="danger" label={`Remove ${f.name}`} onClick={() => removePerson('family', f.id)} />
              </li>
            ))}
          </ul>
        )}
        {helpersNotListed.length > 0 ? (
          <div className="suggest">
            <span className="small muted">Add a helper:</span>
            {helpersNotListed.map((m) => (
              <Button
                key={m.uid}
                size="sm"
                variant="quiet"
                icon="plus"
                onClick={() => setEditing({ kind: 'family', person: { ...blank(), name: m.displayName, phone: m.phone, photoUrl: m.photoUrl } })}
              >
                {m.displayName}
              </Button>
            ))}
          </div>
        ) : null}
      </Card>

      <Card>
        <CardHeader
          title="Contacts"
          description="Friends, neighbors and doctors. They ring through screening and can have a call tile."
          action={
            <Button variant="secondary" icon="plus" onClick={() => setEditing({ kind: 'contact', person: blank() })}>
              Add
            </Button>
          }
        />
        {config.contacts.length === 0 ? (
          <p className="muted">No contacts yet.</p>
        ) : (
          <ul className="people">
            {config.contacts.map((c) => (
              <li key={c.id} className="person">
                <Avatar name={c.name} src={c.photoUrl} size={52} />
                <div className="grow">
                  <div className="person-name">{c.name}</div>
                  <div className="person-meta">{[c.note, c.phone].filter(Boolean).join(' · ')}</div>
                </div>
                <IconButton icon="edit" label={`Edit ${c.name}`} onClick={() => setEditing({ kind: 'contact', person: fromContact(c) })} />
                <IconButton icon="trash" variant="danger" label={`Remove ${c.name}`} onClick={() => removePerson('contact', c.id)} />
              </li>
            ))}
          </ul>
        )}
      </Card>

      <Card>
        <CardHeader
          title="Numbers that always ring"
          description="When unknown callers are screened, these still ring normally."
          action={
            <Button variant="secondary" icon="plus" onClick={() => setAllow({ index: -1, value: null })}>
              Add
            </Button>
          }
        />
        {config.protection.callScreening.allowlist.length === 0 ? (
          <p className="muted">None yet. The pharmacy and doctor’s office are good ones to add.</p>
        ) : (
          <ul className="people">
            {config.protection.callScreening.allowlist.map((n, i) => (
              <li key={`${n.number}-${i}`} className="person">
                <span className="tile-row-icon" aria-hidden="true">
                  <span>{n.label.slice(0, 1)}</span>
                </span>
                <div className="grow">
                  <div className="person-name">{n.label}</div>
                  <div className="person-meta">{n.number}</div>
                </div>
                <IconButton icon="edit" label={`Edit ${n.label}`} onClick={() => setAllow({ index: i, value: n })} />
                <IconButton
                  icon="trash"
                  variant="danger"
                  label={`Remove ${n.label}`}
                  onClick={() => update((c) => void c.protection.callScreening.allowlist.splice(i, 1))}
                />
              </li>
            ))}
          </ul>
        )}
      </Card>

      {editing ? (
        <PersonDialog kind={editing.kind} initial={editing.person} familyId={familyId} device={device} onClose={() => setEditing(null)} onSave={(p) => savePerson(editing.kind, p)} />
      ) : null}
      {allow ? (
        <AllowDialog
          initial={allow.value}
          onClose={() => setAllow(null)}
          onSave={(n) => {
            update((c) => {
              const list = c.protection.callScreening.allowlist;
              if (allow.index >= 0) list[allow.index] = n;
              else list.push(n);
            });
            setAllow(null);
          }}
        />
      ) : null}
    </div>
  );
}
