// Renders the PWA icons from the inline shield SVG (same drawing as src/components/Logo.tsx).
// Usage: npm run icons
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import sharp from 'sharp';

const out = join(dirname(fileURLToPath(import.meta.url)), '..', 'public', 'icons');

const shield = (fill = 'url(#g)') => `
  <path d="M32 5 10 13v15.5C10 43 19.4 54.2 32 58c12.6-3.8 22-15 22-29.5V13L32 5Z" fill="${fill}"/>
  <path d="M32 5 10 13v15.5C10 43 19.4 54.2 32 58V5Z" fill="#ffffff" opacity=".08"/>
  <path d="M32 44.5s-11-6.7-11-14.6a6.3 6.3 0 0 1 11-4.2 6.3 6.3 0 0 1 11 4.2c0 7.9-11 14.6-11 14.6Z" fill="#f7efe1"/>`;
const defs = `<defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#2a7a6f"/><stop offset="1" stop-color="#1a5149"/></linearGradient></defs>`;

// Transparent-background mark (favicon, "any" purpose icons).
const markSvg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">${defs}${shield()}</svg>`;

// Full-bleed tile for maskable and Apple icons: warm paper background, shield in the safe zone.
const tileSvg = (scale) => `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">${defs}
  <rect width="64" height="64" fill="#f7f4ee"/>
  <g transform="translate(32 32) scale(${scale}) translate(-32 -31.5)">${shield()}</g></svg>`;

// Monochrome badge for Android notification trays.
const badgeSvg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">
  <path d="M32 5 10 13v15.5C10 43 19.4 54.2 32 58c12.6-3.8 22-15 22-29.5V13L32 5Z" fill="#fff"/>
  <path d="M32 44.5s-11-6.7-11-14.6a6.3 6.3 0 0 1 11-4.2 6.3 6.3 0 0 1 11 4.2c0 7.9-11 14.6-11 14.6Z" fill="#000" fill-opacity="0"/></svg>`;

const render = (svg, size) => sharp(Buffer.from(svg), { density: 1200 }).resize(size, size).png({ compressionLevel: 9 }).toBuffer();

await mkdir(out, { recursive: true });
await writeFile(join(out, 'icon.svg'), markSvg);
const jobs = [
  ['favicon-32.png', markSvg, 32],
  ['icon-192.png', tileSvg(0.82), 192],
  ['icon-512.png', tileSvg(0.82), 512],
  ['icon-maskable-512.png', tileSvg(0.62), 512],
  ['apple-touch-icon.png', tileSvg(0.78), 180],
  ['badge-96.png', badgeSvg, 96],
];
for (const [name, svg, size] of jobs) {
  await writeFile(join(out, name), await render(svg, size));
  console.log('wrote', name);
}
