// App Store Connect API でストアの掲載情報を登録する。
//   node distribution/store/scripts/asc.mjs status                 … 今の登録状態を読むだけ
//   node distribution/store/scripts/asc.mjs metadata [--dry-run]   … listing.md の App Store の欄（日英）を登録
//   node distribution/store/scripts/asc.mjs screenshots [--replace] … out/appstore の画像を登録（--replace で既存を差し替え）
// 認証は testflight.sh と同じ環境変数: ASC_KEY_ID / ASC_ISSUER_ID / ASC_KEY_PATH（Admin ロールの .p8）。
// 審査への提出はしない（コンソールで確認してから人が出す）。
import { createHash, createPrivateKey, sign } from "node:crypto";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const BUNDLE_ID = "net.shino3.nostrism";
const API = "https://api.appstoreconnect.apple.com";
const [cmd = "status", ...flags] = process.argv.slice(2);
const DRY = flags.includes("--dry-run");

const env = (k) => process.env[k] || (() => { throw new Error(`${k} が未設定`); })();
let token = null;
let tokenAt = 0;
function jwt() {
  const now = Math.floor(Date.now() / 1000);
  if (token && now - tokenAt < 900) return token;
  const b64 = (o) => Buffer.from(JSON.stringify(o)).toString("base64url");
  const head = b64({ alg: "ES256", kid: env("ASC_KEY_ID"), typ: "JWT" });
  const body = b64({ iss: env("ASC_ISSUER_ID"), iat: now, exp: now + 1100, aud: "appstoreconnect-v1" });
  const key = createPrivateKey(readFileSync(env("ASC_KEY_PATH")));
  const sig = sign("sha256", Buffer.from(`${head}.${body}`), { key, dsaEncoding: "ieee-p1363" }).toString("base64url");
  token = `${head}.${body}.${sig}`;
  tokenAt = now;
  return token;
}

async function api(method, path, body) {
  const res = await fetch(path.startsWith("http") ? path : API + path, {
    method,
    headers: { Authorization: `Bearer ${jwt()}`, "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  const json = text ? JSON.parse(text) : null;
  if (!res.ok) {
    const errs = (json?.errors ?? []).map((e) => `${e.code}: ${e.detail ?? e.title}`).join(" / ");
    throw new Error(`${method} ${path} → ${res.status} ${errs}`);
  }
  return json;
}

async function context() {
  const apps = await api("GET", `/v1/apps?filter[bundleId]=${BUNDLE_ID}&fields[apps]=name,bundleId,primaryLocale`);
  const app = apps.data[0];
  if (!app) throw new Error(`${BUNDLE_ID} のアプリが見つからない`);
  const versions = await api("GET", `/v1/apps/${app.id}/appStoreVersions?filter[platform]=IOS&limit=10`);
  const infos = await api("GET", `/v1/apps/${app.id}/appInfos`);
  // 編集できる版 = まだ出していない版（PREPARE_FOR_SUBMISSION など）
  const editable = versions.data.find((v) => ["PREPARE_FOR_SUBMISSION", "DEVELOPER_REJECTED", "REJECTED", "METADATA_REJECTED"].includes(v.attributes.appStoreState ?? v.attributes.appVersionState));
  const info = infos.data.find((i) => !["READY_FOR_DISTRIBUTION", "READY_FOR_SALE"].includes(i.attributes.state ?? i.attributes.appStoreState)) ?? infos.data[0];
  return { app, versions: versions.data, editable, info };
}

async function status() {
  const { app, versions, editable, info } = await context();
  console.log(`app: ${app.attributes.name} (${app.attributes.bundleId}) id=${app.id} primaryLocale=${app.attributes.primaryLocale}`);
  for (const v of versions) console.log(`version: ${v.attributes.versionString} state=${v.attributes.appStoreState ?? v.attributes.appVersionState} id=${v.id}`);
  console.log(`appInfo: id=${info.id} state=${info.attributes.state ?? info.attributes.appStoreState}`);
  const cat = await api("GET", `/v1/appInfos/${info.id}/primaryCategory`).catch(() => null);
  console.log(`primaryCategory: ${cat?.data?.id ?? "(なし)"}`);
  const infoLocs = await api("GET", `/v1/appInfos/${info.id}/appInfoLocalizations`);
  for (const l of infoLocs.data) console.log(`appInfoLocalization ${l.attributes.locale}: name=${l.attributes.name} subtitle=${l.attributes.subtitle ?? ""} privacy=${l.attributes.privacyPolicyUrl ?? ""}`);
  if (!editable) { console.log("編集できる版がない"); return; }
  const locs = await api("GET", `/v1/appStoreVersions/${editable.id}/appStoreVersionLocalizations`);
  for (const l of locs.data) {
    const a = l.attributes;
    console.log(`versionLocalization ${a.locale}: description=${(a.description ?? "").length}字 keywords=${a.keywords ?? ""} promo=${(a.promotionalText ?? "").length}字 support=${a.supportUrl ?? ""}`);
    const sets = await api("GET", `/v1/appStoreVersionLocalizations/${l.id}/appScreenshotSets?include=appScreenshots&fields[appScreenshots]=fileName,assetDeliveryState&limit[appScreenshots]=10`);
    for (const s of sets.data) {
      const ids = new Set((s.relationships?.appScreenshots?.data ?? []).map((d) => d.id));
      const shots = (sets.included ?? []).filter((x) => ids.has(x.id));
      const states = shots.map((x) => x.attributes.assetDeliveryState?.state ?? "?");
      const count = Object.entries(states.reduce((m, st) => ({ ...m, [st]: (m[st] ?? 0) + 1 }), {})).map(([k, v]) => `${k}=${v}`).join(" ");
      console.log(`  screenshots ${s.attributes.screenshotDisplayType}: ${ids.size}枚 ${count}`);
      for (const x of shots.filter((x) => x.attributes.assetDeliveryState?.state === "FAILED")) {
        console.log(`    失敗: ${x.attributes.fileName} ${JSON.stringify(x.attributes.assetDeliveryState.errors ?? [])}`);
      }
    }
  }
}

// ---- 掲載文（listing.md の App Store の節） ----
const LOCALES = { "App Store（日本語）": "ja", "App Store（English）": "en-US" };
const FIELD = { "名前": "name", "Name": "name", "サブタイトル": "subtitle", "Subtitle": "subtitle",
  "プロモーションテキスト": "promotionalText", "Promotional Text": "promotionalText",
  "キーワード": "keywords", "Keywords": "keywords", "説明": "description", "Description": "description" };
const SUPPORT_URL = "https://nostrism.shino3.net/";
const MARKETING_URL = "https://nostrism.shino3.net/";
const PRIVACY_URL = "https://nostrism.shino3.net/privacy-policy.html";

function listing() {
  const md = readFileSync(join(root, "listing.md"), "utf8");
  const out = {};
  for (const part of md.split(/\n## /).slice(1)) {
    const title = part.slice(0, part.indexOf("\n")).trim();
    const locale = LOCALES[title];
    if (!locale) continue;
    const fields = {};
    for (const m of part.matchAll(/^### ([^（(\n]+)[（(]\d+[）)][^\n]*\n(?:[^`#][^\n]*\n)*```text\n([\s\S]*?)\n```/gm)) {
      const key = FIELD[m[1].trim()];
      if (key) fields[key] = m[2];
    }
    out[locale] = fields;
  }
  return out;
}

/** 同じ locale の行があれば PATCH、無ければ POST */
async function upsertLocalization(type, parentRel, parentType, parentId, existing, locale, attributes) {
  const row = existing.find((l) => l.attributes.locale === locale);
  if (DRY) { console.log(`[dry-run] ${row ? "PATCH" : "POST"} ${type} ${locale}`, Object.fromEntries(Object.entries(attributes).map(([k, v]) => [k, typeof v === "string" && v.length > 40 ? `${v.slice(0, 40)}…(${[...v].length}字)` : v]))); return; }
  if (row) {
    await api("PATCH", `/v1/${type}/${row.id}`, { data: { type, id: row.id, attributes } });
  } else {
    await api("POST", `/v1/${type}`, {
      data: { type, attributes: { locale, ...attributes }, relationships: { [parentRel]: { data: { type: parentType, id: parentId } } } },
    });
  }
  console.log(`${row ? "更新" : "作成"}: ${type} ${locale}`);
}

async function metadata() {
  const { editable, info } = await context();
  if (!editable) throw new Error("編集できる版がない");
  const texts = listing();
  const infoLocs = (await api("GET", `/v1/appInfos/${info.id}/appInfoLocalizations`)).data;
  for (const [locale, f] of Object.entries(texts)) {
    await upsertLocalization("appInfoLocalizations", "appInfo", "appInfos", info.id, infoLocs, locale,
      { name: f.name, subtitle: f.subtitle, privacyPolicyUrl: PRIVACY_URL });
    // 基本情報の欄を新しい言語で作ると、Apple 側が版の同じ言語の欄も作る。読み直してから更新する
    const verLocs = (await api("GET", `/v1/appStoreVersions/${editable.id}/appStoreVersionLocalizations`)).data;
    await upsertLocalization("appStoreVersionLocalizations", "appStoreVersion", "appStoreVersions", editable.id, verLocs, locale,
      { description: f.description, keywords: f.keywords, promotionalText: f.promotionalText, supportUrl: SUPPORT_URL, marketingUrl: MARKETING_URL });
  }
  // カテゴリ: ソーシャルネットワーキング
  if (DRY) { console.log("[dry-run] PATCH appInfos primaryCategory=SOCIAL_NETWORKING"); return; }
  await api("PATCH", `/v1/appInfos/${info.id}`, {
    data: { type: "appInfos", id: info.id, relationships: { primaryCategory: { data: { type: "appCategories", id: "SOCIAL_NETWORKING" } } } },
  });
  console.log("更新: primaryCategory = SOCIAL_NETWORKING");
}

// ---- スクリーンショット（out/appstore/<lang>/<枠>/NN-*.jpg） ----
// 枠の種類は API の screenshotDisplayType。iPhone Duo はまだ API に種類が無いので、コンソールの画面から登録する。
const SHOT_SETS = [
  { dir: "iphone-6.9", type: "APP_IPHONE_67" },        // 6.9"（1320×2868）
  { dir: "iphone-6.3", type: "APP_IPHONE_61" },        // 6.3"（1206×2622）
  { dir: "ipad-13", type: "APP_IPAD_PRO_3GEN_129" },   // 13"（2752×2064）
];
const SHOT_LANG = { ja: "ja", "en-US": "en" };

async function uploadScreenshot(setId, file) {
  const bytes = readFileSync(file);
  const fileName = file.slice(file.lastIndexOf("/") + 1);
  const reserved = await api("POST", "/v1/appScreenshots", {
    data: { type: "appScreenshots", attributes: { fileName, fileSize: bytes.length },
      relationships: { appScreenshotSet: { data: { type: "appScreenshotSets", id: setId } } } },
  });
  const shot = reserved.data;
  for (const op of shot.attributes.uploadOperations) {
    const headers = Object.fromEntries((op.requestHeaders ?? []).map((h) => [h.name, h.value]));
    const res = await fetch(op.url, { method: op.method, headers, body: bytes.subarray(op.offset, op.offset + op.length) });
    if (!res.ok) throw new Error(`upload ${fileName} → ${res.status}`);
  }
  await api("PATCH", `/v1/appScreenshots/${shot.id}`, {
    data: { type: "appScreenshots", id: shot.id,
      attributes: { uploaded: true, sourceFileChecksum: createHash("md5").update(bytes).digest("hex") } },
  });
  return shot.id;
}

/** 処理（寸法の検査など）が終わるまで待ち、失敗した画像を報告する */
async function waitProcessed(ids) {
  const pending = new Set(ids);
  for (let i = 0; i < 60 && pending.size > 0; i++) {
    for (const id of [...pending]) {
      const s = (await api("GET", `/v1/appScreenshots/${id}?fields[appScreenshots]=fileName,assetDeliveryState`)).data;
      const st = s.attributes.assetDeliveryState?.state;
      if (st === "COMPLETE") pending.delete(id);
      if (st === "FAILED") {
        pending.delete(id);
        console.log(`  失敗: ${s.attributes.fileName} ${JSON.stringify(s.attributes.assetDeliveryState.errors ?? [])}`);
      }
    }
    if (pending.size > 0) await new Promise((r) => setTimeout(r, 5000));
  }
  if (pending.size > 0) console.log(`  処理待ちのまま: ${pending.size}枚（コンソールで確認）`);
}

async function screenshots() {
  const { editable } = await context();
  if (!editable) throw new Error("編集できる版がない");
  const replace = flags.includes("--replace");
  const verLocs = (await api("GET", `/v1/appStoreVersions/${editable.id}/appStoreVersionLocalizations`)).data;
  for (const [locale, lang] of Object.entries(SHOT_LANG)) {
    const loc = verLocs.find((l) => l.attributes.locale === locale);
    if (!loc) { console.log(`${locale} の版の欄が無い（先に metadata を実行）`); continue; }
    const sets = (await api("GET", `/v1/appStoreVersionLocalizations/${loc.id}/appScreenshotSets?include=appScreenshots`)).data;
    for (const { dir, type } of SHOT_SETS) {
      const folder = join(root, "out/appstore", lang, dir);
      const files = readdirSync(folder).filter((f) => f.endsWith(".jpg")).sort().map((f) => join(folder, f));
      let set = sets.find((x) => x.attributes.screenshotDisplayType === type);
      const existing = set?.relationships?.appScreenshots?.data ?? [];
      if (DRY) { console.log(`[dry-run] ${locale} ${type}: ${files.length}枚（既存 ${existing.length}枚${existing.length && !replace ? "・スキップ" : ""}）`); continue; }
      if (existing.length > 0 && !replace) { console.log(`${locale} ${type}: 既存 ${existing.length}枚があるのでスキップ（差し替えは --replace）`); continue; }
      for (const e of existing) await api("DELETE", `/v1/appScreenshots/${e.id}`);
      if (!set) {
        set = (await api("POST", "/v1/appScreenshotSets", {
          data: { type: "appScreenshotSets", attributes: { screenshotDisplayType: type },
            relationships: { appStoreVersionLocalization: { data: { type: "appStoreVersionLocalizations", id: loc.id } } } },
        })).data;
      }
      const ids = [];
      for (const f of files) ids.push(await uploadScreenshot(set.id, f));
      console.log(`${locale} ${type}: ${ids.length}枚を送信`);
      await waitProcessed(ids);
    }
  }
}

const commands = { status, metadata, screenshots };
if (!commands[cmd]) throw new Error(`不明なコマンド: ${cmd}`);
await commands[cmd]();
