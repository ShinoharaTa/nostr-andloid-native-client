// ストア掲載用の画像を作る（アプリの素の画面 + キャッチコピー + 背景）。
//   node distribution/store/scripts/build-store-images.mjs [ja|en ...] [--only <target>]
// 入力: distribution/store/raw/<lang>/<端末>-<場面>.png（StoreScreenshots が描いたもの）
// 出力: distribution/store/out/<appstore|play>/<lang>/<枠>/NN-<場面>.jpg
//       台紙は HTML で組み、Chrome のヘッドレスで撮る。Apple は透過不可・Play は 24bit のみなので JPEG で書き出す。
//
// 規則（2026-10 時点の公式の要件。README の「要件」節に出典）:
//   - App Store: iPhone（Dynamic Island 中 1206×2622 が必須・大 1320×2868）、iPad 13" 2752×2064（iPad 対応なので必須）、
//     iPhone Duo（内側 2007×2853・外側 1398×2034。2027-04 から必須）。最大 10 枚。透過不可
//   - Google Play: スマホ 9:16（1080×1920 以上・4 枚以上でおすすめ枠の対象）、タブレット 7"/10" は 16:9 か 9:16 で 4〜8 枚。
//     端末の枠を付けない・文字は画像の 20% 以内・宣伝文句（無料・No.1 等）と行動喚起は入れない
import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const args = process.argv.slice(2);
const only = args.includes("--only") ? args[args.indexOf("--only") + 1] : null;
const langs = args.filter((a) => a === "ja" || a === "en");
if (langs.length === 0) langs.push("ja", "en");

// ---- コピー（場面ごと） ----
const COPY = {
  home: {
    ja: ["Nostr を、\nもっと心地よく。", "写真も、会話も、リアクションも。見やすいタイムラインで。"],
    en: ["Nostr, made comfortable.", "Photos, posts and reactions in a clean timeline."],
  },
  deck: {
    ja: ["タイムラインを、\n横に並べて一望。", "フォロー中・ハッシュタグ・チャット・通知をひとつのデッキに。"],
    en: ["All your timelines, side by side.", "Following, hashtags, chats and notifications in one deck."],
  },
  notifications: {
    ja: ["誰が、何に。\nひと目でわかる通知。", "返信・リアクション・Zap・リポストをまとめて確認。"],
    en: ["Notifications that make sense.", "Replies, reactions, Zaps and reposts — at a glance."],
  },
  thread: {
    ja: ["リアクションも、\nスレッドでつながる。", "リアクションへのリアクションも、ツリーでたどれる。"],
    en: ["Reactions, threaded.", "Even reactions to reactions show up as a tree."],
  },
  chat: {
    ja: ["パブリックチャットで、\nゆるく話そう。", "カスタム絵文字も、そのまま使える。"],
    en: ["Hang out in public chats.", "Custom emoji work right in the chat."],
  },
  profile: {
    ja: ["ステータスで、\nいまがわかる。", "フォロー中の人がいま何をしているか、プロフィールで。"],
    en: ["See what they're up to.", "Statuses and Now Playing, right on their profiles."],
  },
  theme: {
    ja: ["好きな色に、\n着せ替え。", "背景・文字・アクセントの 3 色で、自分だけのテーマを。"],
    en: ["Make it yours.", "Build your own theme from just three colors."],
  },
  fold: {
    ja: ["閉じても、\n開いても。", "外側の画面はスマホ表示、内側はデッキ表示に。"],
    en: ["Folded or unfolded.", "Phone view outside, deck view inside."],
  },
};

// 場面ごとのグローの色（背景は暗いまま。中身の写真と絵文字が映えるように色は控えめに）
const HUE = {
  home: ["#8b5cff", "#ff6aa0"], deck: ["#3d8bff", "#8b5cff"], notifications: ["#ff5c8a", "#ffb020"], thread: ["#20c4a4", "#3d8bff"],
  chat: ["#ffb020", "#ff5c8a"], profile: ["#ff7a4d", "#8b5cff"], theme: ["#ff5c93", "#ffc46b"], fold: ["#8f6bff", "#20c4a4"],
};

// ---- 書き出す枠 ----
// layout: "portrait"（上にコピー、下に画面）/ "landscape"（同じ。横長）/ "pair"（外側と内側を並べる）
const TARGETS = [
  // 6.9" は 6.3" と同じ画面を大きく使う（440pt ではナビゲーションレールに切り替わり、投稿が窮屈に見えるため）
  { store: "appstore", id: "iphone-6.9", w: 1320, h: 2868, raw: "iphone", scenes: ["home", "notifications", "thread", "chat", "profile", "theme"] },
  { store: "appstore", id: "iphone-6.3", w: 1206, h: 2622, raw: "iphone", scenes: ["home", "notifications", "thread", "chat", "profile", "theme"] },
  { store: "appstore", id: "ipad-13", w: 2752, h: 2064, raw: "ipad", scenes: [["deck", "home"], "thread", "profile", "theme"] },
  { store: "appstore", id: "iphone-duo-inner", w: 2007, h: 2853, raw: "duo-inner", scenes: [["deck", "home"], "thread", "profile", "theme", ["fold", "pair:duo-outer-home+duo-inner-home"]] },
  { store: "appstore", id: "iphone-duo-outer", w: 1398, h: 2034, raw: "duo-outer", scenes: ["home", "notifications", "thread", "chat", "profile", "theme"] },
  { store: "play", id: "phone", w: 1080, h: 1920, raw: "android-phone", scenes: ["home", "notifications", "thread", "chat", "profile", "theme"] },
  { store: "play", id: "tablet-7", w: 1920, h: 1080, raw: "android-tablet", scenes: [["deck", "home"], "thread", "profile", "theme", ["fold", "pair:android-phone-home+fold-inner-home"]] },
  { store: "play", id: "tablet-10", w: 2560, h: 1440, raw: "android-tablet", scenes: [["deck", "home"], "thread", "profile", "theme", ["fold", "pair:android-phone-home+fold-inner-home"]] },
  { store: "play", id: "foldable", w: 2560, h: 1440, raw: "fold-inner", scenes: [["fold", "pair:android-phone-home+fold-inner-home"], ["deck", "home"], "thread", "theme"] },
];

const esc = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;");

/** アイコンと同じ「リレーをつなぐ曲線」を背景に薄く敷く */
function waves(w, h, seed) {
  let s = seed;
  const r = () => ((s = (s * 1103515245 + 12345) % 2147483648) / 2147483648);
  let paths = "";
  for (let i = 0; i < 7; i++) {
    const y0 = h * (0.15 + r() * 0.75);
    const y1 = h * (0.1 + r() * 0.8);
    const c1 = h * r();
    const c2 = h * r();
    paths += `<path d="M${-w * 0.05} ${y0} C${w * 0.3} ${c1} ${w * 0.6} ${c2} ${w * 1.05} ${y1}" stroke="#ffffff" stroke-opacity="${0.05 + r() * 0.05}" stroke-width="${Math.max(2, w * 0.004)}" fill="none"/>`;
    paths += `<circle cx="${w * 1.05 - 6}" cy="${y1}" r="${Math.max(5, w * 0.007)}" fill="#ffffff" fill-opacity=".08"/>`;
  }
  return `<svg width="${w}" height="${h}" viewBox="0 0 ${w} ${h}" style="position:absolute;inset:0">${paths}</svg>`;
}

function screen(src, x, y, w, rad) {
  return `<img src="${src}" style="position:absolute;left:${x}px;top:${y}px;width:${w}px;border-radius:${rad}px;box-shadow:0 ${w * 0.03}px ${w * 0.09}px rgba(0,0,0,.55),0 0 0 ${Math.max(2, w * 0.004)}px rgba(255,255,255,.08)"/>`;
}

function page(t, scene, lang, rawDir) {
  const [key, pair] = Array.isArray(scene) ? scene : [scene, null];
  const rawName = Array.isArray(scene) ? scene[1] : scene;
  const [rawTitle, sub] = COPY[key][lang];
  const W = t.w;
  const H = t.h;
  const landscape = W > H;
  // 横長の枠は 1 行に収まるので改行しない
  const title = landscape ? rawTitle.replace("\n", "") : rawTitle;
  const unit = Math.min(W, H);
  // コピーは上部 20% 以内（Play の規則）
  const capH = Math.round(H * (landscape ? 0.2 : 0.19));
  const titleSize = Math.round(unit * (landscape ? 0.05 : 0.074) * (lang === "en" ? 1.02 : 1));
  const subSize = Math.round(titleSize * 0.46);
  const html = (body) => doc(body);
  let body;
  if (pair?.startsWith?.("pair:") || (typeof rawName === "string" && rawName.startsWith("pair:"))) {
    const [outer, inner] = rawName.slice(5).split("+");
    // 外側（スマホ）と内側（デッキ）を並べる。高さを揃えて中央に
    const top = capH + H * 0.04;
    const availH = H - top - H * 0.06;
    const innerAspect = inner.startsWith("duo") ? 2007 / 2853 : 2208 / 1840;
    const outerAspect = outer.startsWith("duo") ? 1398 / 2034 : 1080 / 2400;
    if (!landscape) {
      // 縦長の枠は横に並べると小さくなるので、内側を大きく置いて外側を手前に重ねる
      const iw = W * 0.72;
      const ow = W * 0.4;
      body = screen(`${rawDir}/${inner}.png`, W * 0.23, top, iw, iw * 0.035) +
        screen(`${rawDir}/${outer}.png`, W * 0.06, top + H * 0.17, ow, ow * 0.07);
      return html(body);
    }
    let innerW = availH * innerAspect;
    let outerW = availH * outerAspect;
    const gap = W * 0.04;
    const scale = Math.min(1, (W * 0.9 - gap) / (innerW + outerW));
    innerW *= scale;
    outerW *= scale;
    const h2 = availH * scale;
    const x0 = (W - innerW - outerW - gap) / 2;
    const y0 = top + (availH - h2) / 2;
    body = screen(`${rawDir}/${outer}.png`, x0, y0, outerW, outerW * 0.07) + screen(`${rawDir}/${inner}.png`, x0 + outerW + gap, y0, innerW, innerW * 0.035);
  } else {
    const src = `${rawDir}/${t.raw}-${rawName}.png`;
    // 画面は幅いっぱいに近く置き、下端ははみ出して切る（大きく見せる）
    const sw = landscape ? W * 0.84 : W * 0.8;
    const x = (W - sw) / 2;
    const y = capH + H * (landscape ? 0.03 : 0.025);
    body = screen(src, x, y, sw, sw * (landscape ? 0.022 : 0.075));
  }
  return doc(body);
  function doc(body) {
  const font = lang === "ja" ? `"M PLUS Rounded 1c","Noto Sans JP",sans-serif` : `"M PLUS Rounded 1c","Noto Sans",sans-serif`;
  return `<!doctype html><html><head><meta charset="utf-8">
<link href="https://fonts.googleapis.com/css2?family=M+PLUS+Rounded+1c:wght@500;800&family=Noto+Sans+JP:wght@500;700&display=block" rel="stylesheet">
<style>html,body{margin:0;width:${W}px;height:${H}px;overflow:hidden;background:#0c0c10}
.bg{position:absolute;inset:0;background:radial-gradient(${W * 0.85}px ${H * 0.42}px at 30% ${landscape ? 0 : 4}%, ${HUE[key][0]}aa, transparent 72%),radial-gradient(${W * 0.75}px ${H * 0.4}px at 85% ${landscape ? 10 : 14}%, ${HUE[key][1]}88, transparent 70%),radial-gradient(${W}px ${H * 0.6}px at 50% 100%, ${HUE[key][0]}44, transparent 70%),linear-gradient(180deg,#191530 0%,#0e0c18 55%,#0c0c10 100%)}
.cap{position:absolute;left:0;right:0;top:0;height:${capH}px;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;font-family:${font};color:#fff;padding:0 ${W * 0.06}px;box-sizing:border-box}
.t{font-weight:800;font-size:${titleSize}px;line-height:1.22;letter-spacing:.01em;white-space:pre-line;text-wrap:balance;text-shadow:0 2px 24px rgba(0,0,0,.35)}
.s{margin-top:${subSize * 0.55}px;font-weight:500;font-size:${subSize}px;color:#e6e2f5;line-height:1.45;text-wrap:balance}
</style></head><body><div class="bg"></div>${waves(W, H, key.length * 7919 + W)}
<div class="cap"><div class="t">${esc(title)}</div><div class="s">${esc(sub)}</div></div>${body}
<script>
// コピーが帯からはみ出す言語・枠では、収まるまで文字を少しずつ小さくする
document.fonts.ready.then(() => {
  const c = document.querySelector(".cap"), t = c.querySelector(".t"), s = c.querySelector(".s");
  for (let k = 1; k > 0.6; k -= 0.03) {
    t.style.fontSize = ${titleSize} * k + "px"; s.style.fontSize = ${subSize} * k + "px";
    if (t.offsetHeight + s.offsetHeight + parseFloat(getComputedStyle(s).marginTop) <= c.clientHeight * 0.88) break;
  }
});
</script></body></html>`;
  }
}

const tmp = join(root, ".build-tmp");
mkdirSync(tmp, { recursive: true });
for (const lang of langs) {
  const rawDir = join(root, "raw", lang);
  if (!existsSync(rawDir)) {
    console.warn(`skip ${lang}: ${rawDir} がない（StoreScreenshots を STORE_SHOTS=${lang} で先に動かす）`);
    continue;
  }
  for (const t of TARGETS) {
    if (only && t.id !== only) continue;
    const outDir = join(root, "out", t.store, lang, t.id);
    rmSync(outDir, { recursive: true, force: true });
    mkdirSync(outDir, { recursive: true });
    t.scenes.forEach((scene, i) => {
      const key = Array.isArray(scene) ? scene[0] : scene;
      const html = join(tmp, `${t.store}-${lang}-${t.id}-${i}.html`);
      writeFileSync(html, page(t, scene, lang, `file://${rawDir}`));
      const png = join(tmp, `${t.store}-${lang}-${t.id}-${i}.png`);
      execFileSync(CHROME, [
        "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
        "--allow-file-access-from-files", "--virtual-time-budget=8000",
        `--window-size=${t.w},${t.h}`, `--screenshot=${png}`, `file://${html}`,
      ], { stdio: "ignore" });
      const jpg = join(outDir, `${String(i + 1).padStart(2, "0")}-${key}.jpg`);
      execFileSync("sips", ["-s", "format", "jpeg", "-s", "formatOptions", "92", png, "--out", jpg], { stdio: "ignore" });
      console.log(`${t.store}/${lang}/${t.id}/${String(i + 1).padStart(2, "0")}-${key}.jpg`);
    });
  }
}
// ---- Play のフィーチャーグラフィック（1024×500）とアイコン（512×512） ----
// 指針: 中央に寄せる・端は背景だけ・アイコンと同じロゴを大きく置かない・端末の枠を描かない・
//       純白/黒/濃い灰色の背景を避ける・言語ごとに作る・代替テキストは 140 文字以内
const FEATURE = {
  ja: { title: "Nostr を、\nもっと心地よく。", sub: "タイムラインを並べて一望できる\nNostr クライアント", alt: "Nostrism のタイムラインとスレッドの画面。写真つきの投稿とリアクションの木が並ぶ" },
  en: { title: "Nostr, made\ncomfortable.", sub: "A Nostr client that puts\nyour timelines side by side.", alt: "Nostrism timeline and thread screens showing photo posts and threaded reactions" },
};
function featurePage(lang, rawDir) {
  const W = 1024, H = 500;
  const c = FEATURE[lang];
  const font = lang === "ja" ? `"M PLUS Rounded 1c","Noto Sans JP",sans-serif` : `"M PLUS Rounded 1c","Noto Sans",sans-serif`;
  const shot = (name, x, y, w, rot) =>
    `<img src="${rawDir}/android-phone-${name}.png" style="position:absolute;left:${x}px;top:${y}px;width:${w}px;border-radius:${w * 0.07}px;transform:rotate(${rot}deg);box-shadow:0 18px 50px rgba(30,0,60,.55),0 0 0 2px rgba(255,255,255,.12)"/>`;
  return `<!doctype html><html><head><meta charset="utf-8">
<link href="https://fonts.googleapis.com/css2?family=M+PLUS+Rounded+1c:wght@500;800&family=Noto+Sans+JP:wght@500;700&display=block" rel="stylesheet">
<style>html,body{margin:0;width:${W}px;height:${H}px;overflow:hidden}
.bg{position:absolute;inset:0;background:radial-gradient(520px 360px at 18% 20%,#ff7ab0cc,transparent 70%),radial-gradient(560px 420px at 88% 85%,#2fd3c0aa,transparent 70%),linear-gradient(120deg,#4b2aa8 0%,#6e3fd8 45%,#b04fd6 75%,#ff6f9f 100%)}
.cap{position:absolute;left:130px;top:0;bottom:0;width:430px;display:flex;flex-direction:column;justify-content:center;font-family:${font};color:#fff}
.t{font-weight:800;font-size:${lang === "ja" ? 50 : 46}px;line-height:1.18;white-space:pre-line;text-shadow:0 2px 18px rgba(40,0,80,.35)}
.s{margin-top:18px;font-weight:500;font-size:21px;line-height:1.45;color:#f3ecff;white-space:pre-line}
</style></head><body><div class="bg"></div>${waves(W, H, 4242)}
<div class="cap"><div class="t">${esc(c.title)}</div><div class="s">${esc(c.sub)}</div></div>
${shot("thread", 722, 92, 190, 7)}${shot("home", 572, 58, 200, -5)}
</body></html>`;
}
if (!only || only === "feature") {
  for (const lang of langs) {
    const rawDir = join(root, "raw", lang);
    if (!existsSync(rawDir)) continue;
    const outDir = join(root, "out", "play", lang);
    mkdirSync(outDir, { recursive: true });
    const html = join(tmp, `feature-${lang}.html`);
    writeFileSync(html, featurePage(lang, `file://${rawDir}`));
    const png = join(tmp, `feature-${lang}.png`);
    execFileSync(CHROME, [
      "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
      "--allow-file-access-from-files", "--virtual-time-budget=8000",
      "--window-size=1024,500", `--screenshot=${png}`, `file://${html}`,
    ], { stdio: "ignore" });
    // 24bit（透過なし）にするため JPEG で書き出す
    execFileSync("sips", ["-s", "format", "jpeg", "-s", "formatOptions", "95", png, "--out", join(outDir, "feature-1024x500.jpg")], { stdio: "ignore" });
    writeFileSync(join(outDir, "feature-alt.txt"), FEATURE[lang].alt + "\n");
    console.log(`play/${lang}/feature-1024x500.jpg`);
  }
  // アイコン 512×512: iOS の 1024 版（ランチャーと同じ絵柄）を縮め、Play の要件どおり 32bit（アルファ付き）PNG にする
  const iconSrc = join(dirname(dirname(root)), "iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/icon_1024.png");
  const svg = join(tmp, "icon.svg");
  writeFileSync(svg, `<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512"><image href="data:image/png;base64,${readFileSync(iconSrc).toString("base64")}" width="512" height="512"/></svg>`);
  execFileSync("rsvg-convert", ["--unlimited", svg, "-o", join(root, "out", "play", "icon-512.png")]);
  console.log("play/icon-512.png");
}
rmSync(tmp, { recursive: true, force: true });
