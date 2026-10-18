import { useEffect, useRef, useState } from 'react';
import { friendlyError } from '../../data/api';
import { useData, useUser } from '../../data/DataProvider';
import { useFileUrl, useNow, usePhotos } from '../../data/hooks';
import { Button, IconButton } from '../../components/Button';
import { Card } from '../../components/Card';
import { Dialog } from '../../components/Dialog';
import { EmptyState, Spinner } from '../../components/EmptyState';
import { Field, Input } from '../../components/Field';
import { Icon } from '../../components/Icon';
import { useToast } from '../../components/Toast';
import { PHOTO_MAX_EDGE, resizeImage, type ResizedImage } from '../../lib/image';
import { relativeTime } from '../../lib/time';
import type { Device, PhotoDoc } from '../../lib/types';

function PhotoTile({ photo, now, onDelete, isNewest }: { photo: PhotoDoc; now: number; onDelete: () => void; isNewest: boolean }) {
  const url = useFileUrl(photo.storagePath);
  return (
    <li className="photo">
      <div className="photo-img">
        {url ? <img src={url} alt={photo.caption || `Photo from ${photo.fromName}`} loading="lazy" /> : <span className="photo-skeleton" aria-hidden="true" />}
        {isNewest ? <span className="badge badge-brand photo-badge">On the home screen</span> : null}
      </div>
      <div className="photo-meta">
        <div className="grow">
          {photo.caption ? <p className="photo-caption">{photo.caption}</p> : null}
          <p className="small subtle">
            From {photo.fromName} · {relativeTime(photo.createdAt, now)}
          </p>
        </div>
        <IconButton icon="trash" size="sm" variant="danger" label="Delete photo" onClick={onDelete} />
      </div>
    </li>
  );
}

export function PhotosTab({ familyId, device }: { familyId: string; device: Device }) {
  const { api } = useData();
  const user = useUser();
  const toast = useToast();
  const photos = usePhotos(familyId, device.id);
  const now = useNow(60_000);
  const fileRef = useRef<HTMLInputElement>(null);
  const [pending, setPending] = useState<{ image: ResizedImage; preview: string } | null>(null);
  const [caption, setCaption] = useState('');
  const [busy, setBusy] = useState<'prep' | 'send' | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<PhotoDoc | null>(null);
  const [dragOver, setDragOver] = useState(false);

  useEffect(() => () => (pending ? URL.revokeObjectURL(pending.preview) : undefined), [pending]);

  async function choose(file: File | undefined) {
    if (!file) return;
    if (!file.type.startsWith('image/') && !/\.(heic|heif)$/i.test(file.name)) {
      toast('Please choose a photo.', 'error');
      return;
    }
    setBusy('prep');
    try {
      const image = await resizeImage(file, { maxEdge: PHOTO_MAX_EDGE });
      setCaption('');
      setPending({ image, preview: URL.createObjectURL(image.blob) });
    } catch {
      toast('We couldn’t open that photo. Try a JPEG or PNG.', 'error');
    } finally {
      setBusy(null);
      if (fileRef.current) fileRef.current.value = '';
    }
  }

  async function send() {
    if (!pending) return;
    setBusy('send');
    try {
      await api.uploadPhoto(familyId, device.id, pending.image.blob, caption.trim(), { uid: user.uid, name: user.displayName });
      setPending(null);
      toast(`Sent. It’s on ${device.parentName}’s home screen now.`);
    } catch (e) {
      toast(friendlyError(e), 'error');
    } finally {
      setBusy(null);
    }
  }

  async function remove(photo: PhotoDoc) {
    setConfirmDelete(null);
    try {
      await api.deletePhoto(familyId, device.id, photo);
      toast('Photo deleted.');
    } catch (e) {
      toast(friendlyError(e), 'error');
    }
  }

  const list = photos.data ?? [];

  return (
    <div className="stack" style={{ ['--gap' as string]: 'var(--s-5)' }}>
      <Card className={['upload-card', dragOver ? 'is-over' : ''].join(' ')}>
        <div
          className="upload-drop"
          onDragOver={(e) => {
            e.preventDefault();
            setDragOver(true);
          }}
          onDragLeave={() => setDragOver(false)}
          onDrop={(e) => {
            e.preventDefault();
            setDragOver(false);
            void choose(e.dataTransfer.files[0]);
          }}
        >
          <span className="upload-icon" aria-hidden="true">
            <Icon name="image" size={28} />
          </span>
          <div className="grow">
            <h2 className="card-title">Send {device.parentName} a photo</h2>
            <p className="muted">The newest photo shows on the home screen for a day, then rotates with the last 20.</p>
          </div>
          <input ref={fileRef} type="file" accept="image/*,.heic,.heif" className="sr-only" tabIndex={-1} aria-hidden="true" onChange={(e) => void choose(e.target.files?.[0])} data-testid="photo-input" />
          <Button variant="primary" size="lg" icon="upload" loading={busy === 'prep'} onClick={() => fileRef.current?.click()}>
            Choose photo
          </Button>
        </div>
      </Card>

      {photos.loading ? (
        <Spinner />
      ) : list.length === 0 ? (
        <EmptyState icon="heart" title="No photos yet">
          Grandkids, pets, the garden. A new photo is a lovely reason to pick up the phone.
        </EmptyState>
      ) : (
        <ul className="photo-grid" aria-label="Photos sent">
          {list.map((p, i) => (
            <PhotoTile key={p.id} photo={p} now={now} isNewest={i === 0} onDelete={() => setConfirmDelete(p)} />
          ))}
        </ul>
      )}

      <Dialog
        open={!!pending}
        onClose={() => setPending(null)}
        title="Add a caption"
        description={`${device.parentName} will see “Today from ${user.displayName.split(' ')[0]}” with your words underneath.`}
        footer={
          <>
            <Button variant="ghost" onClick={() => setPending(null)}>
              Cancel
            </Button>
            <Button variant="primary" icon="heart" loading={busy === 'send'} onClick={() => void send()}>
              Send photo
            </Button>
          </>
        }
      >
        {pending ? <img className="upload-preview" src={pending.preview} alt="The photo you chose" /> : null}
        <Field label="Caption" optional>
          {(ids) => <Input {...ids} maxLength={120} placeholder="Emma’s first day of school!" value={caption} onChange={(e) => setCaption(e.target.value)} />}
        </Field>
        {pending ? (
          <p className="xsmall subtle">
            Resized to {pending.image.width} × {pending.image.height} for a quick download on their phone.
          </p>
        ) : null}
      </Dialog>

      <Dialog
        open={!!confirmDelete}
        onClose={() => setConfirmDelete(null)}
        size="sm"
        title="Delete this photo?"
        description={`It will disappear from ${device.parentName}’s phone too.`}
        footer={
          <>
            <Button variant="ghost" onClick={() => setConfirmDelete(null)}>
              Keep it
            </Button>
            <Button variant="danger" className="btn-solid" onClick={() => confirmDelete && void remove(confirmDelete)}>
              Delete
            </Button>
          </>
        }
      />
    </div>
  );
}
