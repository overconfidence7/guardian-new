/** The Guardian Mode shield. The same drawing is rendered into the PWA icons (scripts/generate-icons.mjs). */
export function ShieldMark({ size = 32, className }: { size?: number; className?: string }) {
  return (
    <svg className={className} width={size} height={size} viewBox="0 0 64 64" aria-hidden="true" focusable="false">
      <defs>
        <linearGradient id="gm-shield" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#2a7a6f" />
          <stop offset="1" stopColor="#1a5149" />
        </linearGradient>
      </defs>
      <path d="M32 5 10 13v15.5C10 43 19.4 54.2 32 58c12.6-3.8 22-15 22-29.5V13L32 5Z" fill="url(#gm-shield)" />
      <path d="M32 5 10 13v15.5C10 43 19.4 54.2 32 58V5Z" fill="#ffffff" opacity=".08" />
      <path d="M32 44.5s-11-6.7-11-14.6a6.3 6.3 0 0 1 11-4.2 6.3 6.3 0 0 1 11 4.2c0 7.9-11 14.6-11 14.6Z" fill="#f7efe1" />
    </svg>
  );
}

export function Wordmark({ size = 30 }: { size?: number }) {
  return (
    <span className="wordmark">
      <ShieldMark size={size} />
      <span className="wordmark-text">Guardian Mode</span>
    </span>
  );
}
