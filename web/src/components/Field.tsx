import { useId, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from 'react';

export interface FieldIds {
  id: string;
  'aria-describedby'?: string;
  'aria-invalid'?: true;
}

/** Label + control + hint + error, wired together with ids. */
export function Field({ label, hint, error, optional, children, className }: { label: ReactNode; hint?: ReactNode; error?: string | null; optional?: boolean; className?: string; children: (ids: FieldIds) => ReactNode }) {
  const id = useId();
  const hintId = hint ? `${id}-hint` : undefined;
  const errorId = error ? `${id}-error` : undefined;
  const describedBy = [hintId, errorId].filter(Boolean).join(' ') || undefined;
  return (
    <div className={['field', error ? 'field-invalid' : '', className ?? ''].join(' ')}>
      <label htmlFor={id} className="field-label">
        {label}
        {optional ? <span className="field-optional"> (optional)</span> : null}
      </label>
      {hint ? (
        <p id={hintId} className="field-hint">
          {hint}
        </p>
      ) : null}
      {children({ id, 'aria-describedby': describedBy, 'aria-invalid': error ? true : undefined })}
      {error ? (
        <p id={errorId} className="field-error" role="alert">
          {error}
        </p>
      ) : null}
    </div>
  );
}

export function Input(props: InputHTMLAttributes<HTMLInputElement>) {
  return <input {...props} className={['input', props.className ?? ''].join(' ')} />;
}

export function Textarea(props: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea rows={3} {...props} className={['input', 'textarea', props.className ?? ''].join(' ')} />;
}

export function Select(props: SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <span className="select-wrap">
      <select {...props} className={['input', 'select', props.className ?? ''].join(' ')} />
    </span>
  );
}

/** Checkbox row with a large hit area. */
export function Checkbox({ checked, onChange, label, description, disabled }: { checked: boolean; onChange: (v: boolean) => void; label: ReactNode; description?: ReactNode; disabled?: boolean }) {
  const id = useId();
  return (
    <div className={['check-row', disabled ? 'is-disabled' : ''].join(' ')}>
      <input id={id} type="checkbox" className="checkbox" checked={checked} disabled={disabled} onChange={(e) => onChange(e.target.checked)} aria-describedby={description ? `${id}-d` : undefined} />
      <label htmlFor={id} className="check-text">
        <span className="check-label">{label}</span>
        {description ? (
          <span id={`${id}-d`} className="check-description">
            {description}
          </span>
        ) : null}
      </label>
    </div>
  );
}

/** Range slider with a visible value. */
export function Slider({ label, value, min, max, step = 1, onChange, format = String, hint }: { label: string; value: number; min: number; max: number; step?: number; onChange: (v: number) => void; format?: (v: number) => string; hint?: ReactNode }) {
  const id = useId();
  const pct = ((value - min) / (max - min)) * 100;
  return (
    <div className="slider-field">
      <div className="row spread nowrap">
        <label htmlFor={id} className="field-label">
          {label}
        </label>
        <output htmlFor={id} className="slider-value" aria-live="polite">
          {format(value)}
        </output>
      </div>
      {hint ? <p className="field-hint">{hint}</p> : null}
      <input
        id={id}
        type="range"
        className="slider"
        min={min}
        max={max}
        step={step}
        value={value}
        aria-valuetext={format(value)}
        style={{ ['--pct' as string]: `${pct}%` }}
        onChange={(e) => onChange(Number(e.target.value))}
      />
      <div className="slider-scale" aria-hidden="true">
        <span>{format(min)}</span>
        <span>{format(max)}</span>
      </div>
    </div>
  );
}

/** Radio group drawn as cards with a title and a plain description. */
export function RadioCards<T extends string>({ legend, value, options, onChange, name }: { legend: ReactNode; value: T; name: string; onChange: (v: T) => void; options: { value: T; title: ReactNode; description?: ReactNode; badge?: ReactNode }[] }) {
  return (
    <fieldset className="radio-cards">
      <legend className="field-label">{legend}</legend>
      {options.map((o) => (
        <label key={o.value} className={['radio-card', value === o.value ? 'is-checked' : ''].join(' ')}>
          <input type="radio" name={name} value={o.value} checked={value === o.value} onChange={() => onChange(o.value)} />
          <span className="radio-dot" aria-hidden="true" />
          <span className="radio-text">
            <span className="radio-title">
              {o.title}
              {o.badge ? <span className="badge badge-brand">{o.badge}</span> : null}
            </span>
            {o.description ? <span className="radio-description">{o.description}</span> : null}
          </span>
        </label>
      ))}
    </fieldset>
  );
}
