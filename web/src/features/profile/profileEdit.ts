import type { EventTemplate, NostrEvent } from "nostr-tools/pure";
import { INDEXER_RELAYS } from "../../lib/columnRequest";
import { unixNow } from "../../lib/time";
import { OwnReplaceableUnreachableError, refetchOwnReplaceable } from "../../nostr/ownReplaceable";
import { writeRelays } from "../../nostr/pool";
import { PublishError, type PublishFailure, publishEvent } from "../../nostr/publish";

/*
 * [#922] プロフィール編集（kind:0）の初期値の読み取りと、発行する content の組み立て。
 * ネイティブ ProfileEdit.kt（#920）と同じ規則・同じテストケースにそろえる。
 *
 * NIP-24: name と display_name は別の項目。display_name は「name より大きく、豊かな文字を使える別の名前」で、
 * name は「display_name があっても必ず置く」。displayName は非推奨（display_name を使う。見つけたら無視するか消す）。
 * 以前は表示名の欄を name に書き、土台の display_name / displayName も同じ値にしていたため、別の値にしていた
 * name が消えていた。
 */

/** 編集できる項目（kind:0 のキー。ネイティブ ProfileEdit.FIELDS と同じ順） */
export const PROFILE_FIELDS = [
  "name",
  "display_name",
  "about",
  "picture",
  "banner",
  "lud16",
  "nip05",
  "website",
] as const;

export type ProfileField = (typeof PROFILE_FIELDS)[number];

/** 編集欄の値 */
export type ProfileFields = Record<ProfileField, string>;

/** 発行で差し替える項目（空文字 = キーを消す） */
export type ProfileChanges = Partial<ProfileFields>;

/** 非推奨の表示名のキー（NIP-24） */
const LEGACY_DISPLAY_NAME = "displayName";

/**
 * unreachable = 直前の取り直しでどのリレーからも応答が無かった（古い版で上書きしうるので止めた）。
 * stale = 編集を始めた時点の版と、取り直した最新版が違う（別の端末・クライアントでの変更を消すので止めた）。
 */
export type ProfileEditFailure = "unreachable" | "stale" | PublishFailure;

export class ProfileEditError extends Error {
  readonly reason: ProfileEditFailure;

  constructor(reason: ProfileEditFailure, options?: ErrorOptions) {
    super(`profile edit failed: ${reason}`, options);
    this.name = "ProfileEditError";
    this.reason = reason;
  }
}

/** kind:0 の content を JSON オブジェクトとして読む。読めなければ null */
function contentObject(event: NostrEvent | null): Record<string, unknown> | null {
  if (!event) return null;
  try {
    const json: unknown = JSON.parse(event.content);
    return typeof json === "object" && json !== null && !Array.isArray(json)
      ? (json as Record<string, unknown>)
      : null;
  } catch {
    return null;
  }
}

function stringOf(value: unknown): string {
  return typeof value === "string" ? value : "";
}

/**
 * 編集欄の初期値。表示名（display_name）は display_name → displayName の最初の空でない値（name には
 * フォールバックしない）。他は同名のキー（文字列でない値は空欄）。content が読めなければ全部空欄。
 */
export function profileFieldsOf(event: NostrEvent | null): ProfileFields {
  const json = contentObject(event) ?? {};
  const displayName =
    [json.display_name, json[LEGACY_DISPLAY_NAME]].map(stringOf).find((v) => v.trim() !== "") ?? "";
  return {
    name: stringOf(json.name),
    display_name: displayName,
    about: stringOf(json.about),
    picture: stringOf(json.picture),
    banner: stringOf(json.banner),
    lud16: stringOf(json.lud16),
    nip05: stringOf(json.nip05),
    website: stringOf(json.website),
  };
}

/** 初期値から変えた項目（前後の空白は比べない。値は前後の空白を落とす） */
export function changedFields(initial: ProfileFields, current: ProfileFields): ProfileChanges {
  const changed: ProfileChanges = {};
  for (const field of PROFILE_FIELDS) {
    const value = current[field].trim();
    if (initial[field].trim() !== value) changed[field] = value;
  }
  return changed;
}

/**
 * 発行する content。取り直した版（base）の JSON を土台に、変えた項目だけ上書きし、空欄にした項目はキーを消す
 * （未知のキーはそのまま）。name と display_name は同期しない。非推奨の displayName は消し、display_name が空で
 * 表示名の欄も変えていなければ、その値を display_name に移す（ネイティブ ProfileEdit.buildContent と同じ）。
 */
export function buildProfileContent(base: NostrEvent | null, changed: ProfileChanges): string {
  const json: Record<string, unknown> = { ...(contentObject(base) ?? {}) };
  for (const field of PROFILE_FIELDS) {
    const value = changed[field]?.trim();
    if (value === undefined) continue;
    if (value === "") delete json[field];
    else json[field] = value;
  }
  const legacy = stringOf(json[LEGACY_DISPLAY_NAME]).trim();
  delete json[LEGACY_DISPLAY_NAME];
  if (!("display_name" in changed) && stringOf(json.display_name).trim() === "" && legacy !== "") {
    json.display_name = legacy;
  }
  return JSON.stringify(json);
}

/** 発行する kind:0。tags は取り直した版のまま（未知のタグ・カスタム絵文字を残す） */
export function buildProfileTemplate(
  base: NostrEvent | null,
  changed: ProfileChanges,
  nowSec: number,
): EventTemplate {
  return {
    kind: 0,
    content: buildProfileContent(base, changed),
    tags: (base?.tags ?? []).map((t) => [...t]),
    // 同じ秒に続けて保存しても、置換可能イベントの新旧が崩れないように
    created_at: Math.max(nowSec, (base?.created_at ?? 0) + 1),
  };
}

/**
 * プロフィール（kind:0）を発行する。直前に自分の kind:0 をリレーとインデクサから取り直し（#478 の規則）、
 * どのリレーからも応答が無ければ発行せず ProfileEditError("unreachable")、編集を始めた時点の版（basedOnId）と
 * 取り直した最新版が違えば発行せず ProfileEditError("stale")（relayList.ts の publishRelayList と同じ）。
 * 取り直した最新版の content を土台に変えた項目だけ差し替える（応答はあったが kind:0 が無ければ変えた項目だけ）。
 * 署名の失敗は同じ reason の ProfileEditError。
 */
export async function publishProfile(
  me: string,
  changed: ProfileChanges,
  /** 編集を始めた時点で画面に出ていた自分の kind:0 の id（無かったら null） */
  basedOnId: string | null,
): Promise<void> {
  let base: NostrEvent | null;
  try {
    base = await refetchOwnReplaceable(me, 0);
  } catch (e) {
    if (e instanceof OwnReplaceableUnreachableError) throw new ProfileEditError("unreachable", { cause: e });
    throw e;
  }
  if ((base?.id ?? null) !== basedOnId) throw new ProfileEditError("stale");

  try {
    // プロフィールは他の人が見つけられるよう、自分の write リレーに加えてインデクサにも送る（kind:10002 と同じ）
    await publishEvent(buildProfileTemplate(base, changed, unixNow()), {
      relays: [...new Set([...writeRelays(), ...INDEXER_RELAYS])],
    });
  } catch (e) {
    if (e instanceof PublishError) throw new ProfileEditError(e.reason, { cause: e });
    throw e;
  }
}
