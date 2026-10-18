import { useId, type ReactNode } from 'react';

/** An on/off switch with a visible label and optional description. */
export function Switch({ checked, onChange, label, description, disabled, hideLabel }: { checked: boolean; onChange: (next: boolean) => void; label: ReactNode; description?: ReactNode; disabled?: boolean; hideLabel?: boolean }) {
  const id = useId();
  return (
    <div className={['switch-row', disabled ? 'is-disabled' : ''].join(' ')}>
      <div className={hideLabel ? 'sr-only' : 'switch-text'}>
        <label htmlFor={id} className="switch-label">
          {label}
        </label>
        {description ? (
          <p id={`${id}-d`} className="switch-description">
            {description}
          </p>
        ) : null}
      </div>
      <button
        id={id}
        type="button"
        role="switch"
        aria-checked={checked}
        aria-describedby={description ? `${id}-d` : undefined}
        disabled={disabled}
        className="switch"
        onClick={() => onChange(!checked)}
      >
        <span className="switch-thumb" aria-hidden="true" />
        <span className="switch-state" aria-hidden="true">
          {checked ? 'On' : 'Off'}
        </span>
      </button>
    </div>
  );
}
