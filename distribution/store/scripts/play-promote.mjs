// Google Play のトラック間でリリースを昇格する（例: クローズドテスト alpha → オープンテスト beta）。
//   node distribution/store/scripts/play-promote.mjs
//   （GitHub Actions の play-promote.yml から動かす。Play の認証は Actions の中にしかない）
// 環境変数:
//   PLAY_ACCESS_TOKEN … androidpublisher のスコープ
//   FROM_TRACK / TO_TRACK … 昇格元・先（alpha=クローズド / beta=オープン / production=製品版）
//   VERSION_CODE … 昇格するビルド番号（空なら昇格元の配信中のリリース）
//   DRY_RUN=true … 送らずに内容だけを出す
// ビルドし直さずに同じ AAB をそのまま出す（同じビルド番号は二度アップロードできないため）。
// 「管理対象の公開」がオンなので、確定と同時に審査へ送られ、承認後に Play Console の公開の概要で「変更を公開」を押すと配信される。
const PKG = "net.shino3.nostrism";
const BASE = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PKG}`;
const env = (k, d) => process.env[k] || d;
const token = env("PLAY_ACCESS_TOKEN") || (() => { throw new Error("PLAY_ACCESS_TOKEN が未設定"); })();
const from = env("FROM_TRACK", "alpha");
const to = env("TO_TRACK", "beta");
const versionCode = env("VERSION_CODE", "");
const DRY = env("DRY_RUN") === "true";
if (from === to) throw new Error("昇格元と昇格先が同じ");

async function call(method, url, body) {
  const res = await fetch(url, {
    method,
    headers: { Authorization: `Bearer ${token}`, ...(body ? { "Content-Type": "application/json" } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${url.replace(/^https:\/\/[^/]+/, "")} → ${res.status} ${text.slice(0, 500)}`);
  return text ? JSON.parse(text) : null;
}

const describe = (r) => `${r.name ?? "(名前なし)"} [${(r.versionCodes ?? []).join(",")}] ${r.status}`;

const edit = (await call("POST", `${BASE}/edits`, {})).id;
try {
  const source = await call("GET", `${BASE}/edits/${edit}/tracks/${from}`);
  const releases = source?.releases ?? [];
  console.log(`${from} のリリース: ${releases.map(describe).join(" / ") || "(なし)"}`);
  const release = versionCode
    ? releases.find((r) => (r.versionCodes ?? []).includes(versionCode))
    : releases.find((r) => r.status === "completed");
  if (!release) throw new Error(`${from} に昇格できるリリースが無い（${versionCode ? `ビルド ${versionCode}` : "配信中のもの"}）`);

  const target = await call("GET", `${BASE}/edits/${edit}/tracks/${to}`).catch(() => null);
  console.log(`${to} のいまのリリース: ${(target?.releases ?? []).map(describe).join(" / ") || "(なし)"}`);

  // 昇格先には、昇格元と同じビルド・名前・リリースノートで、完全公開として出す
  const promoted = {
    name: release.name,
    versionCodes: release.versionCodes,
    status: "completed",
    ...(release.releaseNotes ? { releaseNotes: release.releaseNotes } : {}),
  };
  console.log(`${to} へ: ${describe(promoted)}（リリースノート ${(release.releaseNotes ?? []).map((n) => n.language).join(", ") || "なし"}）`);
  if (DRY) {
    await call("DELETE", `${BASE}/edits/${edit}`);
    console.log("[dry-run] 確定せずに破棄した");
  } else {
    await call("PUT", `${BASE}/edits/${edit}/tracks/${to}`, { track: to, releases: [promoted] });
    await call("POST", `${BASE}/edits/${edit}:commit`);
    console.log("確定して審査に送った（承認後、Play Console の公開の概要で「変更を公開」を押すと配信される）");
  }
} catch (e) {
  await call("DELETE", `${BASE}/edits/${edit}`).catch(() => {});
  throw e;
}
