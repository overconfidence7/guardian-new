import type { HTMLAttributes, ReactNode } from 'react';

export function Card({ as: Tag = 'section', className, children, padded = true, ...rest }: { as?: 'section' | 'article' | 'div' | 'li'; padded?: boolean; children?: ReactNode } & HTMLAttributes<HTMLElement>) {
  return (
    <Tag className={['card', padded ? 'card-padded' : '', className ?? ''].join(' ')} {...rest}>
      {children}
    </Tag>
  );
}

export function CardHeader({ title, description, action, id, level = 2 }: { title: ReactNode; description?: ReactNode; action?: ReactNode; id?: string; level?: 2 | 3 }) {
  const H = level === 2 ? 'h2' : 'h3';
  return (
    <header className="card-header">
      <div className="grow">
        <H id={id} className="card-title">
          {title}
        </H>
        {description ? <p className="card-description">{description}</p> : null}
      </div>
      {action ? <div className="card-action">{action}</div> : null}
    </header>
  );
}
