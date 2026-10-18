import { forwardRef, type AnchorHTMLAttributes, type ButtonHTMLAttributes, type ReactNode } from 'react';
import { Link, type LinkProps } from 'react-router';
import { Icon, type IconName } from './Icon';

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'quiet';
type Size = 'md' | 'lg' | 'sm';

interface Common {
  variant?: Variant;
  size?: Size;
  icon?: IconName;
  iconAfter?: IconName;
  block?: boolean;
  children?: ReactNode;
}

function classes({ variant = 'secondary', size = 'md', block }: Common, extra?: string) {
  return ['btn', `btn-${variant}`, `btn-${size}`, block ? 'btn-block' : '', extra ?? ''].filter(Boolean).join(' ');
}

function Inner({ icon, iconAfter, children, loading }: Common & { loading?: boolean }) {
  return (
    <>
      {loading ? <span className="spinner" aria-hidden="true" /> : icon ? <Icon name={icon} size={20} /> : null}
      {children != null ? <span className="btn-label">{children}</span> : null}
      {iconAfter ? <Icon name={iconAfter} size={20} /> : null}
    </>
  );
}

export const Button = forwardRef<HTMLButtonElement, Common & ButtonHTMLAttributes<HTMLButtonElement> & { loading?: boolean }>(function Button(
  { variant, size, icon, iconAfter, block, loading, children, className, disabled, type = 'button', ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      type={type}
      className={classes({ variant, size, block }, className)}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      {...rest}
    >
      <Inner icon={icon} iconAfter={iconAfter} loading={loading}>
        {children}
      </Inner>
    </button>
  );
});

export function ButtonLink({ variant, size, icon, iconAfter, block, children, className, ...rest }: Common & LinkProps) {
  return (
    <Link className={classes({ variant, size, block }, className)} {...rest}>
      <Inner icon={icon} iconAfter={iconAfter}>
        {children}
      </Inner>
    </Link>
  );
}

/** For tel:, mailto: and external links. */
export function ButtonA({ variant, size, icon, iconAfter, block, children, className, ...rest }: Common & AnchorHTMLAttributes<HTMLAnchorElement>) {
  return (
    <a className={classes({ variant, size, block }, className)} {...rest}>
      <Inner icon={icon} iconAfter={iconAfter}>
        {children}
      </Inner>
    </a>
  );
}

export function IconButton({ icon, label, className, size = 'md', variant = 'ghost', ...rest }: { icon: IconName; label: string; size?: Size; variant?: Variant } & ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    <button type="button" className={['icon-btn', `btn-${variant}`, `icon-btn-${size}`, className ?? ''].join(' ')} aria-label={label} title={label} {...rest}>
      <Icon name={icon} size={size === 'sm' ? 18 : 22} />
    </button>
  );
}
