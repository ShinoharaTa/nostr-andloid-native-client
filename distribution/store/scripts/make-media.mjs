// ストア用スクリーンショットのデモ素材（架空ユーザーのアイコン・バナー・投稿画像・カスタム絵文字）を作る。
//   node distribution/store/scripts/make-media.mjs
// 出力: distribution/store/demo-media/{avatars,banners,photos,emoji}/*.png
// 絵は SVG の図形だけで描く（実在の写真・人物・他者の絵文字グリフを使わない）。SVG → PNG は rsvg-convert。
// カスタム絵文字は Web の文字絵文字の描画処理（web/server/emoji/render.ts。/api/emoji.png と同じ）で作る。
import { execFileSync } from "node:child_process";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { registerHooks } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const repo = dirname(dirname(root));
const out = join(root, "demo-media");
for (const d of ["avatars", "banners", "photos", "emoji", "svg"]) mkdirSync(join(out, d), { recursive: true });

// 決まった乱数（毎回同じ絵になる）
function rng(seed) {
  let s = seed >>> 0;
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0;
    return s / 4294967296;
  };
}

function png(name, svg, w, h) {
  const svgPath = join(out, "svg", `${name.replace(/\//g, "_")}.svg`);
  writeFileSync(svgPath, svg);
  execFileSync("rsvg-convert", ["-w", String(w), "-h", String(h), "-o", join(out, `${name}.png`), svgPath]);
}

const grad = (id, a, b, x2 = "1", y2 = "1") =>
  `<linearGradient id="${id}" x1="0" y1="0" x2="${x2}" y2="${y2}"><stop offset="0" stop-color="${a}"/><stop offset="1" stop-color="${b}"/></linearGradient>`;

// ---- アイコン（512×512。アプリが丸く切り抜く） ----
const avatars = {
  mio: { bg: ["#b9a7ff", "#ff9ec7"], art: `<path d="M300 150a130 130 0 1 0 70 230a110 110 0 1 1-70-230z" fill="#fff"/><circle cx="350" cy="170" r="14" fill="#fff"/><circle cx="380" cy="230" r="8" fill="#fff" opacity=".8"/>` },
  hiyori: { bg: ["#ffcf8a", "#ff7a6b"], art: `<rect x="126" y="186" width="260" height="180" rx="34" fill="#fff"/><rect x="186" y="156" width="90" height="44" rx="14" fill="#fff"/><circle cx="256" cy="276" r="62" fill="#ff8f6b"/><circle cx="256" cy="276" r="38" fill="#fff" opacity=".85"/><circle cx="346" cy="222" r="12" fill="#ff8f6b"/>` },
  nekomaru: { bg: ["#9be7d4", "#3fb6a8"], art: `<path d="M150 210 L180 120 L232 180 Z M362 210 L332 120 L280 180 Z" fill="#fff"/><ellipse cx="256" cy="280" rx="126" ry="110" fill="#fff"/><ellipse cx="210" cy="270" rx="12" ry="18" fill="#2c4a52"/><ellipse cx="302" cy="270" rx="12" ry="18" fill="#2c4a52"/><path d="M244 310 q12 12 24 0" stroke="#2c4a52" stroke-width="8" fill="none" stroke-linecap="round"/><path d="M150 300 h-50 M150 320 l-46 14 M362 300 h50 M362 320 l46 14" stroke="#fff" stroke-width="7" stroke-linecap="round"/>` },
  kaito: { bg: ["#4c6fff", "#1d2a6b"], art: `<path d="M200 170 L120 256 L200 342" stroke="#fff" stroke-width="30" fill="none" stroke-linecap="round" stroke-linejoin="round"/><path d="M312 170 L392 256 L312 342" stroke="#fff" stroke-width="30" fill="none" stroke-linecap="round" stroke-linejoin="round"/><path d="M276 150 L236 362" stroke="#8fe3ff" stroke-width="26" stroke-linecap="round"/>` },
  aoi: { bg: ["#f4e2c8", "#c98b5a"], art: `<path d="M150 220 h190 v70 a95 95 0 0 1 -190 0 z" fill="#fff"/><path d="M340 240 a38 38 0 1 1 0 76" stroke="#fff" stroke-width="18" fill="none"/><path d="M200 170 q-14-24 0-48 M245 170 q-14-24 0-48 M290 170 q-14-24 0-48" stroke="#fff" stroke-width="12" fill="none" stroke-linecap="round"/><rect x="130" y="380" width="252" height="16" rx="8" fill="#fff" opacity=".8"/>` },
  sora: { bg: ["#2a2f7a", "#7b4fd6"], art: `<circle cx="256" cy="256" r="80" fill="#ffd27a"/><ellipse cx="256" cy="256" rx="160" ry="38" fill="none" stroke="#fff" stroke-width="14" transform="rotate(-18 256 256)"/><circle cx="130" cy="140" r="8" fill="#fff"/><circle cx="390" cy="120" r="10" fill="#fff"/><circle cx="400" cy="390" r="7" fill="#fff"/><circle cx="110" cy="380" r="6" fill="#fff"/>` },
  mochi: { bg: ["#ffd6e5", "#ff8fb8"], art: `<ellipse cx="206" cy="160" rx="34" ry="96" fill="#fff"/><ellipse cx="306" cy="160" rx="34" ry="96" fill="#fff"/><ellipse cx="206" cy="160" rx="16" ry="70" fill="#ffc2d8"/><ellipse cx="306" cy="160" rx="16" ry="70" fill="#ffc2d8"/><ellipse cx="256" cy="310" rx="130" ry="112" fill="#fff"/><circle cx="214" cy="300" r="12" fill="#5a3442"/><circle cx="298" cy="300" r="12" fill="#5a3442"/><ellipse cx="190" cy="336" rx="18" ry="11" fill="#ffc2d8"/><ellipse cx="322" cy="336" rx="18" ry="11" fill="#ffc2d8"/>` },
  yuzuki: { bg: ["#ffe27a", "#ff9f43"], art: `<path d="M140 300 v-40 a116 116 0 0 1 232 0 v40" stroke="#fff" stroke-width="26" fill="none" stroke-linecap="round"/><rect x="112" y="280" width="64" height="110" rx="26" fill="#fff"/><rect x="336" y="280" width="64" height="110" rx="26" fill="#fff"/><path d="M236 150 v110 a26 22 0 1 1 -10 -18" stroke="#fff" stroke-width="14" fill="none" opacity=".0"/>` },
  riku: { bg: ["#7ad3ff", "#2f7bd6"], art: `<path d="M110 340 L200 220 L260 290 L320 200 L410 340 Z" fill="#fff"/><circle cx="350" cy="150" r="34" fill="#fff" opacity=".9"/>` },
  nana: { bg: ["#c3f08a", "#4fae5a"], art: `<path d="M256 380 C256 300 256 260 256 200" stroke="#fff" stroke-width="18" stroke-linecap="round"/><path d="M256 260 C200 250 170 210 170 160 C230 165 256 200 256 260 Z M256 230 C310 220 340 180 340 130 C280 135 256 170 256 230 Z" fill="#fff"/>` },
};
for (const [name, a] of Object.entries(avatars)) {
  png(
    `avatars/${name}`,
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512"><defs>${grad("g", a.bg[0], a.bg[1])}</defs><rect width="512" height="512" fill="url(#g)"/>${a.art}</svg>`,
    256,
    256,
  );
}

// ---- 風景（投稿画像・バナー共通の描き方） ----
function sunsetSea(w, h) {
  const r = rng(7);
  let waves = "";
  for (let i = 0; i < 26; i++) {
    const y = h * 0.62 + i * (h * 0.38) / 26;
    const x = r() * w * 0.2 + w * 0.3;
    const len = w * (0.06 + r() * 0.25) * (1 + i / 20);
    waves += `<rect x="${w / 2 - len / 2 + (x - w * 0.4) * 0.3}" y="${y}" width="${len}" height="${2 + i * 0.25}" rx="2" fill="#ffd9a0" opacity="${0.75 - i / 40}"/>`;
  }
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${w} ${h}"><defs>
    <linearGradient id="sky" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#2b2a6b"/><stop offset=".45" stop-color="#c2507e"/><stop offset=".62" stop-color="#ffb26b"/></linearGradient>
    <linearGradient id="sea" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#5a3f7a"/><stop offset="1" stop-color="#1c1d44"/></linearGradient>
    <radialGradient id="glow" cx=".5" cy=".62" r=".35"><stop offset="0" stop-color="#ffe2a8" stop-opacity=".9"/><stop offset="1" stop-color="#ffe2a8" stop-opacity="0"/></radialGradient></defs>
    <rect width="${w}" height="${h}" fill="url(#sky)"/><rect width="${w}" height="${h}" fill="url(#glow)"/>
    <circle cx="${w / 2}" cy="${h * 0.6}" r="${h * 0.11}" fill="#ffe7b0"/>
    <rect y="${h * 0.62}" width="${w}" height="${h * 0.38}" fill="url(#sea)"/>${waves}
    <path d="M0 ${h * 0.62} L${w * 0.12} ${h * 0.55} L${w * 0.22} ${h * 0.6} L${w * 0.3} ${h * 0.62} Z" fill="#241c3f"/>
    <path d="M${w} ${h * 0.62} L${w * 0.86} ${h * 0.52} L${w * 0.76} ${h * 0.59} L${w * 0.7} ${h * 0.62} Z" fill="#241c3f"/></svg>`;
}

function nightSky(w, h) {
  const r = rng(42);
  let stars = "";
  for (let i = 0; i < 260; i++) {
    const s = r() < 0.08 ? 2.6 : 0.6 + r() * 1.4;
    stars += `<circle cx="${(r() * w).toFixed(1)}" cy="${(r() * h * 0.78).toFixed(1)}" r="${s.toFixed(2)}" fill="#fff" opacity="${(0.4 + r() * 0.6).toFixed(2)}"/>`;
  }
  // オリオン座っぽい並び
  const o = [[0.46, 0.18], [0.6, 0.2], [0.5, 0.36], [0.53, 0.37], [0.56, 0.38], [0.45, 0.56], [0.62, 0.54]].map(([x, y]) => [x * w, y * h]);
  const lines = [[0, 2], [1, 4], [2, 3], [3, 4], [2, 5], [4, 6]].map(([a, b]) => `<line x1="${o[a][0]}" y1="${o[a][1]}" x2="${o[b][0]}" y2="${o[b][1]}" stroke="#9fb4ff" stroke-width="1.6" opacity=".55"/>`).join("");
  const big = o.map(([x, y]) => `<circle cx="${x}" cy="${y}" r="5" fill="#fff"/><circle cx="${x}" cy="${y}" r="14" fill="#cfe0ff" opacity=".25"/>`).join("");
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${w} ${h}"><defs>
    <linearGradient id="n" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#070b24"/><stop offset=".7" stop-color="#1d2a6b"/><stop offset="1" stop-color="#3b3f8f"/></linearGradient></defs>
    <rect width="${w}" height="${h}" fill="url(#n)"/>${stars}${lines}${big}
    <path d="M0 ${h} L0 ${h * 0.8} L${w * 0.15} ${h * 0.72} L${w * 0.3} ${h * 0.8} L${w * 0.45} ${h * 0.7} L${w * 0.62} ${h * 0.82} L${w * 0.78} ${h * 0.74} L${w} ${h * 0.82} L${w} ${h} Z" fill="#060816"/></svg>`;
}

function mountains(w, h) {
  const layer = (y, amp, col, seed) => {
    const r = rng(seed);
    let d = `M0 ${h} L0 ${y}`;
    for (let x = 0; x <= w; x += w / 10) d += ` L${x} ${y - r() * amp}`;
    return `<path d="${d} L${w} ${h} Z" fill="${col}"/>`;
  };
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${w} ${h}"><defs>
    <linearGradient id="s" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#a8d8ff"/><stop offset="1" stop-color="#fdf1e3"/></linearGradient></defs>
    <rect width="${w}" height="${h}" fill="url(#s)"/><circle cx="${w * 0.75}" cy="${h * 0.25}" r="${h * 0.08}" fill="#fff6d8"/>
    ${layer(h * 0.55, h * 0.25, "#9bb7d9", 3)}${layer(h * 0.66, h * 0.2, "#6f8fbf", 5)}${layer(h * 0.78, h * 0.16, "#45618f", 9)}${layer(h * 0.9, h * 0.1, "#2b3f63", 11)}</svg>`;
}

function catNap(w, h) {
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${w} ${h}"><defs>
    <linearGradient id="b" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ffe9c7"/><stop offset="1" stop-color="#ffc98f"/></linearGradient></defs>
    <rect width="${w}" height="${h}" fill="url(#b)"/>
    <rect x="0" y="${h * 0.66}" width="${w}" height="${h * 0.34}" fill="#c96f4a"/>
    <rect x="${w * 0.12}" y="${h * 0.62}" width="${w * 0.76}" height="${h * 0.08}" rx="12" fill="#8a4b34"/>
    <path d="M${w * 0.1} ${h * 0.7} h${w * 0.8} v${h * 0.3} h-${w * 0.8} z" fill="#e8956a" opacity=".5"/>
    <ellipse cx="${w * 0.5}" cy="${h * 0.54}" rx="${w * 0.24}" ry="${h * 0.13}" fill="#f2f2f2"/>
    <ellipse cx="${w * 0.36}" cy="${h * 0.5}" rx="${w * 0.1}" ry="${h * 0.1}" fill="#f2f2f2"/>
    <path d="M${w * 0.29} ${h * 0.44} L${w * 0.3} ${h * 0.33} L${w * 0.35} ${h * 0.41} Z M${w * 0.43} ${h * 0.44} L${w * 0.42} ${h * 0.33} L${w * 0.37} ${h * 0.41} Z" fill="#f2f2f2"/>
    <path d="M${w * 0.32} ${h * 0.5} q${w * 0.015} ${h * 0.015} ${w * 0.03} 0 M${w * 0.37} ${h * 0.5} q${w * 0.015} ${h * 0.015} ${w * 0.03} 0" stroke="#5a4a42" stroke-width="5" fill="none" stroke-linecap="round"/>
    <ellipse cx="${w * 0.58}" cy="${h * 0.5}" rx="${w * 0.08}" ry="${h * 0.05}" fill="#ffb27a" opacity=".55"/>
    <path d="M${w * 0.72} ${h * 0.56} q${w * 0.08} ${-h * 0.02} ${w * 0.04} ${-h * 0.12}" stroke="#f2f2f2" stroke-width="${h * 0.045}" fill="none" stroke-linecap="round"/>
    <text x="${w * 0.6}" y="${h * 0.3}" font-family="Helvetica, Arial" font-size="${h * 0.08}" fill="#8a4b34" opacity=".8">z z z</text></svg>`;
}

function latte(w, h) {
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${w} ${h}">
    <rect width="${w}" height="${h}" fill="#e9d8c4"/>
    <rect x="0" y="0" width="${w}" height="${h}" fill="#d9c2a6" opacity=".35"/>
    <circle cx="${w * 0.5}" cy="${h * 0.52}" r="${h * 0.4}" fill="#fff" opacity=".95"/>
    <circle cx="${w * 0.5}" cy="${h * 0.52}" r="${h * 0.3}" fill="#f4efe9"/>
    <circle cx="${w * 0.5}" cy="${h * 0.52}" r="${h * 0.25}" fill="#a8693c"/>
    <path d="M${w * 0.5} ${h * 0.66} C${w * 0.38} ${h * 0.56} ${w * 0.41} ${h * 0.43} ${w * 0.47} ${h * 0.45} C${w * 0.49} ${h * 0.46} ${w * 0.5} ${h * 0.48} ${w * 0.5} ${h * 0.5} C${w * 0.5} ${h * 0.48} ${w * 0.51} ${h * 0.46} ${w * 0.53} ${h * 0.45} C${w * 0.59} ${h * 0.43} ${w * 0.62} ${h * 0.56} ${w * 0.5} ${h * 0.66} Z" fill="#f6e7d4"/>
    <path d="M${w * 0.78} ${h * 0.44} a${h * 0.08} ${h * 0.08} 0 1 1 0 ${h * 0.16}" stroke="#fff" stroke-width="${h * 0.04}" fill="none"/>
    <rect x="${w * 0.08}" y="${h * 0.7}" width="${w * 0.14}" height="${h * 0.04}" rx="6" fill="#8a5a3a" transform="rotate(-20 ${w * 0.15} ${h * 0.72})"/></svg>`;
}

function omurice(w, h) {
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${w} ${h}">
    <rect width="${w}" height="${h}" fill="#cfe6f2"/>
    <ellipse cx="${w * 0.5}" cy="${h * 0.55}" rx="${w * 0.36}" ry="${h * 0.34}" fill="#fff"/>
    <ellipse cx="${w * 0.5}" cy="${h * 0.55}" rx="${w * 0.3}" ry="${h * 0.28}" fill="#f4f8fb"/>
    <ellipse cx="${w * 0.5}" cy="${h * 0.54}" rx="${w * 0.22}" ry="${h * 0.16}" fill="#ffd34d"/>
    <ellipse cx="${w * 0.5}" cy="${h * 0.5}" rx="${w * 0.17}" ry="${h * 0.08}" fill="#ffe27a" opacity=".7"/>
    <path d="M${w * 0.5} ${h * 0.62} C${w * 0.43} ${h * 0.56} ${w * 0.45} ${h * 0.48} ${w * 0.49} ${h * 0.5} C${w * 0.5} ${h * 0.505} ${w * 0.5} ${h * 0.515} ${w * 0.5} ${h * 0.52} C${w * 0.5} ${h * 0.515} ${w * 0.5} ${h * 0.505} ${w * 0.51} ${h * 0.5} C${w * 0.55} ${h * 0.48} ${w * 0.57} ${h * 0.56} ${w * 0.5} ${h * 0.62} Z" fill="#e2413b"/>
    <circle cx="${w * 0.73}" cy="${h * 0.42}" r="${h * 0.035}" fill="#4caf50"/><circle cx="${w * 0.76}" cy="${h * 0.46}" r="${h * 0.03}" fill="#66bb6a"/>
    <rect x="${w * 0.84}" y="${h * 0.2}" width="${w * 0.03}" height="${h * 0.6}" rx="8" fill="#c0c8d0"/></svg>`;
}

function flowers(w, h) {
  const r = rng(99);
  let f = "";
  const cols = ["#ff7aa8", "#ffd34d", "#ff9f43", "#c38bff", "#ffffff"];
  for (let i = 0; i < 70; i++) {
    const x = r() * w;
    const y = h * 0.45 + r() * h * 0.55;
    const s = 6 + (y / h) * 26;
    const c = cols[Math.floor(r() * cols.length)];
    f += `<g transform="translate(${x.toFixed(1)} ${y.toFixed(1)})"><circle r="${s}" cx="${-s * 0.6}" fill="${c}"/><circle r="${s}" cx="${s * 0.6}" fill="${c}"/><circle r="${s}" cy="${-s * 0.6}" fill="${c}"/><circle r="${s}" cy="${s * 0.6}" fill="${c}"/><circle r="${s * 0.55}" fill="#ffefa0"/></g>`;
  }
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${w} ${h}"><defs>
    <linearGradient id="s" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#8fd3ff"/><stop offset="1" stop-color="#e8f7ff"/></linearGradient></defs>
    <rect width="${w}" height="${h}" fill="url(#s)"/><rect y="${h * 0.45}" width="${w}" height="${h * 0.55}" fill="#7cc576"/>${f}</svg>`;
}

const scenes = { "sunset-sea": sunsetSea, "night-sky": nightSky, mountains, "cat-nap": catNap, latte, omurice, flowers };
for (const [name, fn] of Object.entries(scenes)) png(`photos/${name}`, fn(1200, 900), 1200, 900);
for (const [name, fn] of Object.entries({ sunset: sunsetSea, night: nightSky, mountains, flowers })) {
  png(`banners/${name}`, fn(1500, 500), 1500, 500);
}

// ---- カスタム絵文字（Web の文字絵文字の描画処理で作る。/api/emoji.png と同じ見た目） ----
registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith(".") && context.parentURL?.endsWith(".ts") && !/\.\w+$/.test(specifier)) {
      return nextResolve(`${specifier}.ts`, context);
    }
    return nextResolve(specifier, context);
  },
});
const webDir = join(repo, "web");
const { parseFontData } = await import(join(webDir, "server/emoji/fontData.ts"));
const { render } = await import(join(webDir, "server/emoji/render.ts"));
const fonts = {};
function fontOf(id) {
  if (!fonts[id]) {
    const buf = readFileSync(join(webDir, `public/fonts/${id}.v1.bin`));
    fonts[id] = parseFontData(buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength));
  }
  return fonts[id];
}
const emoji = [
  ["erai", ["えらい"], "ff6b9a", "ffffff", "mplusrounded"],
  ["sugoi", ["すごい"], "ff9f1c", "ffffff", "delagothic"],
  ["kawaii", ["かわいい"], "ff7ac0", "ffffff", "mplusrounded"],
  ["otsu", ["おつ"], "3aa0ff", "ffffff", "delagothic"],
  ["kusa", ["草"], "37b24d", "ffffff", "notosans"],
  ["wakaru", ["わかる"], "845ef7", "ffffff", "mplusrounded"],
  ["kami", ["神"], "f59f00", "ffffff", "delagothic"],
  ["oishisou", ["おいし", "そう"], "e8590c", "ffffff", "mplusrounded"],
  ["nice", ["NICE"], "12b886", "ffffff", "delagothic"],
  ["thanks", ["THANKS"], "4c6ef5", "ffffff", "delagothic"],
  ["cute", ["CUTE"], "f06595", "ffffff", "mplusrounded"],
  ["wow", ["WOW"], "fd7e14", "ffffff", "delagothic"],
  // 英語版のデモで日本語の絵文字の代わりに使う
  ["yum", ["YUM"], "e8590c", "ffffff", "mplusrounded"],
  ["same", ["SAME"], "845ef7", "ffffff", "mplusrounded"],
  ["lol", ["LOL"], "37b24d", "ffffff", "delagothic"],
];
for (const [name, lines, color, stroke, font] of emoji) {
  const r = await render(fontOf(font), { lines, color, stroke });
  if (!r.ok) throw new Error(`emoji ${name}: ${r.error}`);
  writeFileSync(join(out, `emoji/${name}.png`), Buffer.from(r.png));
}
console.log("demo media written to", out);
