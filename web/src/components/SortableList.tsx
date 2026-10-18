/*
 * A vertical list that can be reordered three ways: dragging the grip (mouse, pen or
 * touch, via pointer events, so it works in iOS Safari), the up/down buttons, or the
 * keyboard on the grip (Arrow Up/Down). Moves are announced to screen readers.
 */
import { useRef, useState, type KeyboardEvent, type PointerEvent, type ReactNode } from 'react';
import { dropIndex, moveItem } from '../lib/tiles';
import { IconButton } from './Button';
import { Icon } from './Icon';

interface Props<T> {
  items: readonly T[];
  getId: (item: T) => string;
  getLabel: (item: T) => string;
  onReorder: (from: number, to: number) => void;
  renderItem: (item: T, index: number) => ReactNode;
  label: string;
}

export function SortableList<T>({ items, getId, getLabel, onReorder, renderItem, label }: Props<T>) {
  const listRef = useRef<HTMLOListElement>(null);
  const [drag, setDrag] = useState<{ id: string; from: number; to: number; dy: number; startY: number; mids: number[]; height: number } | null>(null);
  const [announcement, setAnnouncement] = useState('');

  const ordered = drag ? moveItem(items, drag.from, drag.to) : items.slice();

  function announce(item: T, to: number) {
    setAnnouncement(`${getLabel(item)} moved to position ${to + 1} of ${items.length}.`);
  }

  function move(from: number, to: number) {
    if (to < 0 || to >= items.length || from === to) return;
    onReorder(from, to);
    announce(items[from]!, to);
  }

  function onPointerDown(e: PointerEvent<HTMLButtonElement>, index: number) {
    if (e.button !== 0) return;
    const list = listRef.current;
    if (!list) return;
    const rows = Array.from(list.querySelectorAll<HTMLElement>(':scope > li'));
    const mids = rows.map((r) => {
      const b = r.getBoundingClientRect();
      return b.top + b.height / 2;
    });
    const height = rows[index]?.getBoundingClientRect().height ?? 0;
    e.currentTarget.setPointerCapture(e.pointerId);
    e.preventDefault();
    setDrag({ id: getId(items[index]!), from: index, to: index, dy: 0, startY: e.clientY, mids, height });
  }

  function onPointerMove(e: PointerEvent<HTMLButtonElement>) {
    if (!drag) return;
    const dy = e.clientY - drag.startY;
    const centre = (drag.mids[drag.from] ?? 0) + dy;
    setDrag({ ...drag, dy, to: dropIndex(drag.mids, centre, drag.from) });
  }

  function onPointerUp() {
    if (!drag) return;
    const { from, to } = drag;
    setDrag(null);
    if (from !== to) move(from, to);
  }

  function onGripKey(e: KeyboardEvent<HTMLButtonElement>, index: number) {
    if (e.key === 'ArrowUp') {
      e.preventDefault();
      move(index, index - 1);
    } else if (e.key === 'ArrowDown') {
      e.preventDefault();
      move(index, index + 1);
    }
  }

  return (
    <>
      <ol ref={listRef} className={['sortable', drag ? 'is-dragging' : ''].join(' ')} aria-label={label}>
        {ordered.map((item) => {
          const id = getId(item);
          const index = items.findIndex((i) => getId(i) === id);
          const dragging = drag?.id === id;
          // The dragged row follows the pointer; the others are already in their new order.
          const offset = dragging ? drag.dy - ((drag.mids[drag.to] ?? 0) - (drag.mids[drag.from] ?? 0)) : 0;
          return (
            <li key={id} className={['sortable-item', dragging ? 'is-active' : ''].join(' ')} style={dragging ? { transform: `translateY(${offset}px)` } : undefined}>
              <button
                type="button"
                className="grip"
                aria-label={`Reorder ${getLabel(item)}. Use arrow keys or drag.`}
                aria-describedby={undefined}
                onPointerDown={(e) => onPointerDown(e, index)}
                onPointerMove={onPointerMove}
                onPointerUp={onPointerUp}
                onPointerCancel={() => setDrag(null)}
                onKeyDown={(e) => onGripKey(e, index)}
              >
                <Icon name="grip" size={20} />
              </button>
              <div className="sortable-content">{renderItem(item, index)}</div>
              <div className="sortable-moves">
                <IconButton icon="arrowUp" size="sm" label={`Move ${getLabel(item)} up`} disabled={index === 0} onClick={() => move(index, index - 1)} />
                <IconButton icon="arrowDown" size="sm" label={`Move ${getLabel(item)} down`} disabled={index === items.length - 1} onClick={() => move(index, index + 1)} />
              </div>
            </li>
          );
        })}
      </ol>
      <p className="sr-only" aria-live="assertive">
        {announcement}
      </p>
    </>
  );
}
