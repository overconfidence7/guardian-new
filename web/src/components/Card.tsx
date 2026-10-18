import type { HTMLAttributes, ReactNode } from 'react';

export function Card({ as: Tag = 'section', className, children, padded = true, ...rest }: { as?: 'section' | 'article' | 'div' | 'li'; padded?: boolean; children?: ReactNode } & HTMLAttributes<HTMLElement>) {
  return (
    <Tag className={['card', padded ? 'card-padded' : '', className ?? ''].join(' ')} {...rest}>
      {children}
    </Tag>
  );
}

/** Title with an optional action on the same row; the description runs full width below. */
export function CardHeader({ title, description, action, id, level = 2 }: { title: ReactNode; description?: ReactNode; action?: ReactNode; id?: string; level?: 2 | 3 }) {
  const H = level === 2 ? 'h2' : 'h3';
  return (
    <header className="card-header">
      <div className="card-header-row">
        <H id={id} className="card-title grow">
          {title}
        </H>
        {action ? <div className="card-action">{action}</div> : null}
      </div>
      {description ? <p className="card-description">{description}</p> : null}
    </header>
  );
}
