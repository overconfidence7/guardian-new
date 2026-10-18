import { useEffect, useId, useRef, type ReactNode } from 'react';
import { IconButton } from './Button';

/**
 * Modal dialog on top of the native <dialog> element: focus is trapped and restored
 * by the browser, Esc closes, and the page behind is inert.
 */
export function Dialog({ open, onClose, title, description, children, footer, size = 'md' }: { open: boolean; onClose: () => void; title: ReactNode; description?: ReactNode; children?: ReactNode; footer?: ReactNode; size?: 'sm' | 'md' | 'lg' }) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const descId = useId();

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    if (open && !el.open) {
      if (typeof el.showModal === 'function') el.showModal();
      else el.setAttribute('open', '');
    } else if (!open && el.open) {
      if (typeof el.close === 'function') el.close();
      else el.removeAttribute('open');
    }
  }, [open]);

  return (
    <dialog
      ref={ref}
      className={`dialog dialog-${size}`}
      aria-labelledby={titleId}
      aria-describedby={description ? descId : undefined}
      onCancel={(e) => {
        e.preventDefault();
        onClose();
      }}
      onClick={(e) => {
        // Click on the backdrop (the dialog element itself, outside the panel) closes.
        if (e.target === ref.current) onClose();
      }}
    >
      {open ? (
        <div className="dialog-panel">
          <header className="dialog-header">
            <div className="grow">
              <h2 id={titleId} className="dialog-title">
                {title}
              </h2>
              {description ? (
                <p id={descId} className="dialog-description">
                  {description}
                </p>
              ) : null}
            </div>
            <IconButton icon="x" label="Close" onClick={onClose} />
          </header>
          {children ? <div className="dialog-body">{children}</div> : null}
          {footer ? <footer className="dialog-footer">{footer}</footer> : null}
        </div>
      ) : null}
    </dialog>
  );
}
