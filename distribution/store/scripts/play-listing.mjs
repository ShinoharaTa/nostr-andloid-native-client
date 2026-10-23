// Google Play Developer API でストアの掲載情報（日英の文言と画像）を登録する。
//   node distribution/store/scripts/play-listing.mjs <画像のフォルダ>
//   （GitHub Actions の store-listing.yml から動かす。Play の認証は Actions の中にしかない）
// 環境変数: PLAY_ACCESS_TOKEN（androidpublisher のスコープ）、DRY_RUN=true で送らずに内容だけを出す、
//   SEND_FOR_REVIEW=false で審査に送らずに置いておく（既定は確定と同時に審査へ送る）。
// 画像のフォルダ: <dir>/<ja|en>/{phone,tablet-7,tablet-10}/*.jpg、<dir>/<ja|en>/feature-1024x500.jpg、<dir>/icon-512.png
//   （build-store-images.mjs の out/play と同じ並び）
// 既定は確定と同時に審査へ送る。「管理対象の公開（Managed publishing）」がオンなので、承認後に Play Console で
// 「変更を公開」を押すまで掲載には反映されない。
// SEND_FOR_REVIEW=false は審査に送らずに置いておく（changesNotSentForReview）。管理対象の公開がオフのアプリは
// この指定を受け付けないので、そのときは確定せずに止める。
import { readFileSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const PKG = "net.shino3.nostrism";
const BASE = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PKG}`;
const UPLOAD = `https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/${PKG}`;
const DRY = process.env.DRY_RUN === "true";
const SEND = process.env.SEND_FOR_REVIEW !== "false";
const imagesDir = process.argv[2];
if (!imagesDir) throw new Error("画像のフォルダを指定する");
const token = process.env.PLAY_ACCESS_TOKEN || (() => { throw new Error("PLAY_ACCESS_TOKEN が未設定"); })();

// listing.md の節 → Play の言語コードと画像のフォルダ
const LANGS = { "Google Play（日本語）": ["ja-JP", "ja"], "Google Play（English, en-US）": ["en-US", "en"] };
const FIELD = {
  "アプリ名": "title", "App name": "title",
  "簡単な説明": "shortDescription", "Short description": "shortDescription",
  "詳しい説明": "fullDescription", "Full description": "fullDescription",
};
const SCREENSHOTS = [["phoneScreenshots", "phone"], ["sevenInchScreenshots", "tablet-7"], ["tenInchScreenshots", "tablet-10"]];

function listing() {
  const md = readFileSync(join(root, "listing.md"), "utf8");
  const out = [];
  for (const part of md.split(/\n## /).slice(1)) {
    const title = part.slice(0, part.indexOf("\n")).trim();
    const lang = LANGS[title];
    if (!lang) continue;
    const fields = {};
    for (const m of part.matchAll(/^### ([^（(\n]+)[（(]\d+[）)][^\n]*\n(?:[^`#][^\n]*\n)*```text\n([\s\S]*?)\n```/gm)) {
      const key = FIELD[m[1].trim()];
      if (key) fields[key] = m[2];
    }
    out.push({ language: lang[0], dir: lang[1], ...fields });
  }
  return out;
}

async function call(method, url, body, contentType = "application/json") {
  const res = await fetch(url, {
    method,
    headers: { Authorization: `Bearer ${token}`, ...(body ? { "Content-Type": contentType } : {}) },
    body: body === undefined ? undefined : contentType === "application/json" ? JSON.stringify(body) : body,
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${url.replace(/^https:\/\/[^/]+/, "")} → ${res.status} ${text.slice(0, 400)}`);
  return text ? JSON.parse(text) : null;
}

const files = (dir) => readdirSync(dir).filter((f) => /\.(jpe?g|png)$/.test(f)).sort().map((f) => join(dir, f));
const mime = (f) => (f.endsWith(".png") ? "image/png" : "image/jpeg");

async function replaceImages(edit, language, imageType, paths) {
  if (DRY) { console.log(`[dry-run] ${language} ${imageType}: ${paths.length}枚`); return; }
  await call("DELETE", `${BASE}/edits/${edit}/listings/${language}/${imageType}`);
  for (const p of paths) {
    await call("POST", `${UPLOAD}/edits/${edit}/listings/${language}/${imageType}?uploadType=media`, readFileSync(p), mime(p));
  }
  console.log(`${language} ${imageType}: ${paths.length}枚`);
}

const edit = (await call("POST", `${BASE}/edits`, {})).id;
try {
  const current = await call("GET", `${BASE}/edits/${edit}/listings`);
  console.log(`いまの掲載言語: ${(current?.listings ?? []).map((l) => l.language).join(", ") || "(なし)"}`);
  for (const l of listing()) {
    const text = { language: l.language, title: l.title, shortDescription: l.shortDescription, fullDescription: l.fullDescription };
    if (DRY) console.log(`[dry-run] ${l.language} 文言: title=${l.title} short=${[...l.shortDescription].length}字 full=${[...l.fullDescription].length}字`);
    else { await call("PUT", `${BASE}/edits/${edit}/listings/${l.language}`, text); console.log(`${l.language} 文言を更新`); }
    for (const [imageType, sub] of SCREENSHOTS) await replaceImages(edit, l.language, imageType, files(join(imagesDir, l.dir, sub)));
    await replaceImages(edit, l.language, "featureGraphic", [join(imagesDir, l.dir, "feature-1024x500.jpg")]);
    await replaceImages(edit, l.language, "icon", [join(imagesDir, "icon-512.png")]);
  }
  if (DRY) {
    await call("DELETE", `${BASE}/edits/${edit}`);
    console.log("[dry-run] 確定せずに破棄した");
  } else {
    if (SEND) {
      await call("POST", `${BASE}/edits/${edit}:commit`);
      console.log("確定して審査に送った（承認後、Play Console の公開の概要で「変更を公開」を押すと掲載に反映される）");
    } else {
      try {
        await call("POST", `${BASE}/edits/${edit}:commit?changesNotSentForReview=true`);
        console.log("確定した（審査には送っていない。Play Console の「公開の概要」から送る）");
      } catch (e) {
        if (String(e.message).includes("sent for review automatically")) {
          throw new Error("このアプリは変更が自動で審査に送られる設定（管理対象の公開がオフ）なので、審査に送らずに確定できない。" +
            "Play Console で「管理対象の公開」をオンにしてから動かすか、send_for_review=true で動かす（編集は破棄した）");
        }
        throw e;
      }
    }
  }
} catch (e) {
  await call("DELETE", `${BASE}/edits/${edit}`).catch(() => {});
  throw e;
}
