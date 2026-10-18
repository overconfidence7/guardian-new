/* Soft illustrated "photos" for the demo, so /demo needs no network or binary assets. */

function svg(body: string, w = 1200, h = 900): string {
  const markup = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${w} ${h}" width="${w}" height="${h}">${body}</svg>`;
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(markup)}`;
}

export const DEMO_PHOTOS = {
  sunset: svg(`
    <defs><linearGradient id="s" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#f6c48c"/><stop offset=".55" stop-color="#f2a07b"/><stop offset="1" stop-color="#c9787a"/></linearGradient>
    <linearGradient id="w" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#7a6f8f"/><stop offset="1" stop-color="#3f4a6b"/></linearGradient></defs>
    <rect width="1200" height="900" fill="url(#s)"/><circle cx="600" cy="520" r="120" fill="#fde3b0" opacity=".95"/>
    <path d="M0 520 C200 470 330 500 470 470 S800 430 1200 500 V900 H0Z" fill="#6d5a7a" opacity=".55"/>
    <rect y="560" width="1200" height="340" fill="url(#w)"/>
    <g stroke="#fde3b0" stroke-width="6" stroke-linecap="round" opacity=".7"><path d="M520 600h160"/><path d="M480 640h240" opacity=".7"/><path d="M540 690h120" opacity=".5"/></g>
    <path d="M140 560 l40 -90 l40 90z M960 560 l30 -70 l30 70z" fill="#3d3150" opacity=".5"/>`),
  garden: svg(`
    <rect width="1200" height="900" fill="#dcebd8"/><rect y="560" width="1200" height="340" fill="#7fa36f"/>
    <circle cx="980" cy="170" r="90" fill="#f8e7a6"/>
    ${[160, 330, 520, 700, 880, 1050]
      .map(
        (x, i) =>
          `<path d="M${x} 900 C${x - 10} 760 ${x + 12} 680 ${x} ${600 - (i % 2) * 40}" stroke="#4f7a46" stroke-width="10" fill="none"/>` +
          `<g transform="translate(${x} ${600 - (i % 2) * 40})">${[0, 72, 144, 216, 288]
            .map((a) => `<ellipse rx="34" ry="62" transform="rotate(${a}) translate(0 -52)" fill="${['#f4a6b8', '#f7c59f', '#e88fa6', '#fbd38d', '#f0b7c9', '#f6a68c'][i]}"/>`)
            .join('')}<circle r="26" fill="#f6d365"/></g>`,
      )
      .join('')}`),
  school: svg(`
    <rect width="1200" height="900" fill="#cfe3f0"/><rect y="640" width="1200" height="260" fill="#9cc58a"/>
    <rect x="300" y="300" width="600" height="360" rx="8" fill="#d9776b"/><path d="M270 310 L600 140 L930 310Z" fill="#8c4a46"/>
    <rect x="540" y="500" width="120" height="160" rx="60" fill="#5a3a35"/>
    ${[360, 470, 730, 840].map((x) => `<rect x="${x}" y="370" width="80" height="80" rx="6" fill="#fdf2d0"/>`).join('')}
    <circle cx="600" cy="250" r="40" fill="#fdf2d0"/><path d="M600 250 v-24 M600 250 h18" stroke="#5a3a35" stroke-width="6" stroke-linecap="round"/>
    <g transform="translate(180 560)"><circle r="40" fill="#f2c6a0"/><rect x="-40" y="40" width="80" height="100" rx="30" fill="#f5b041"/><rect x="-30" y="60" width="60" height="70" rx="10" fill="#3b7dd8"/></g>`),
  dog: svg(`
    <rect width="1200" height="900" fill="#f3e6d3"/><rect y="680" width="1200" height="220" fill="#d8c3a5"/>
    <g transform="translate(600 520)">
      <ellipse rx="230" ry="150" cy="120" fill="#b7835a"/>
      <circle r="150" cy="-60" fill="#c8956a"/>
      <ellipse cx="-140" cy="-80" rx="55" ry="110" fill="#8a5a3b" transform="rotate(18 -140 -80)"/>
      <ellipse cx="140" cy="-80" rx="55" ry="110" fill="#8a5a3b" transform="rotate(-18 140 -80)"/>
      <ellipse cy="0" rx="80" ry="60" fill="#f1dcc4"/>
      <circle cx="-55" cy="-90" r="16" fill="#2d2420"/><circle cx="55" cy="-90" r="16" fill="#2d2420"/>
      <ellipse cy="-20" rx="30" ry="22" fill="#2d2420"/><path d="M0 0 v24 M-30 30 q30 26 60 0" stroke="#2d2420" stroke-width="7" fill="none" stroke-linecap="round"/>
      <path d="M-20 34 q20 50 40 0" fill="#e57b84"/>
    </g>`),
};
