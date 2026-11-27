import type { NostrEvent } from "nostr-tools/pure";
import type { EventDraft } from "../../nostr/publish";

/**
 * [#1025] nostr-yo の「Yo」。相手に「Yo」とだけ送る遊び（ネイティブ #1024 の model/Yo.kt の写し）。
 * 決まりは https://github.com/kojira/nostr-yo/blob/main/SPEC.md（2026-11-27 版。NIP ではなく nostr-yo の提案）。
 *
 * Yo は相手のプロフィール（kind:0）へのリアクション（NIP-25）で、絵文字は NIP-30 の `:yo:`:
 *   kind 7 / content ":yo:"
 *   ["e", <相手の最新の kind:0 の id>, <リレーヒント>, <相手の公開鍵>]
 *   ["p", <相手の公開鍵>, <リレーヒント>]
 *   ["k", "0"]
 *   ["emoji", "yo", <https の画像 URL>]
 *   ["t", "yo"]
 * ここは純関数だけ（判定・送るイベントの形）。取得と発行は sendYo.ts。
 */

export const YO_KIND = 7;
export const YO_CONTENT = ":yo:";
export const YO_SHORTCODE = "yo";

/** 画面に出す印（文字。送り主が指定した画像は取りに行かない） */
export const YO_LABEL = "Yo";

/**
 * 送る Yo の `emoji` の URL。nostr-yo と同じにする（nostr-yo の受信箱で「別の画像」と注記されない。
 * ほかのクライアントでも同じ絵で出る）。URL として載せるだけで、画像は同梱しない（リポジトリにライセンスが無い）。
 */
export const YO_IMAGE_URL = "https://kojira.github.io/nostr-yo/yo.png";

/** `emoji` の URL の長さの上限（SPEC の条件 2） */
export const YO_MAX_URL_LENGTH = 2048;

/** これより先の未来の `created_at` は Yo とみなさない（SPEC の条件 6。10 分） */
export const YO_MAX_FUTURE_SEC = 600;

/** 送る前に相手の最新の kind:0 を取りに行く待ち時間。全リレーの EOSE が揃えばそこで打ち切る */
export const YO_FETCH_TIMEOUT_MS = 5_000;

/** 1 件届いてから、ほかのリレーにもっと新しい版が無いか待つ時間（応答しないリレーのために毎回上限まで待たせない） */
export const YO_FETCH_SETTLE_MS = 1_200;

const HEX64 = /^[0-9a-f]{64}$/;

/** その名前のタグ（1 つ目の要素が name のもの） */
function named(tags: readonly string[][], name: string): string[][] {
  return tags.filter((tag) => tag[0] === name);
}

/**
 * event が Yo なら、受け取る人の公開鍵。Yo でなければ null。
 * SPEC の「receiver が Yo とみなす条件」を全部見る（id と署名は取り込みのときに確かめてある）。nostr-yo の実装と同じ判定:
 *  1. kind:7 で、本文がちょうど `:yo:`
 *  2. `emoji` タグがちょうど 1 つ。名前は `yo`、URL は isAcceptableYoEmojiUrl
 *  3. `p` タグがちょうど 1 つ（それが受け取る人）
 *  4. `e` タグがちょうど 1 つで、64 桁の 16 進数。公開鍵のヒント（4 つ目）があれば、受け取る人
 *  5. `k` タグがちょうど 1 つで `0`。`a` タグは無い
 *  6. `created_at` が now の 10 分より先ではない
 * `t=yo` は見ない（付いていなくても Yo。付いているだけでは Yo ではない）。
 */
export function yoRecipientOf(
  event: Pick<NostrEvent, "kind" | "content" | "tags" | "created_at">,
  now: number,
): string | null {
  if (event.kind !== YO_KIND || event.content !== YO_CONTENT) return null;
  if (event.created_at > now + YO_MAX_FUTURE_SEC) return null;
  const emoji = named(event.tags, "emoji");
  if (emoji.length !== 1 || emoji[0][1] !== YO_SHORTCODE || !isAcceptableYoEmojiUrl(emoji[0][2] ?? "")) {
    return null;
  }
  const ps = named(event.tags, "p");
  const recipient = ps.length === 1 ? ps[0][1] : undefined;
  if (recipient === undefined || !HEX64.test(recipient)) return null;
  const ks = named(event.tags, "k");
  if (ks.length !== 1 || ks[0][1] !== "0") return null;
  const es = named(event.tags, "e");
  if (es.length !== 1 || !HEX64.test(es[0][1] ?? "")) return null;
  const authorHint = es[0][3] ?? "";
  if (authorHint !== "" && authorHint !== recipient) return null;
  if (named(event.tags, "a").length > 0) return null;
  return recipient;
}

/**
 * `emoji` の URL として認めるか: `https://` で、ホストがあり、認証情報（`user:pass@`）を含まず、YO_MAX_URL_LENGTH 文字まで。
 * 空白・制御文字を含むものは認めない。**この URL は取りに行かない**（判定に使うだけ）。
 * ネイティブと同じ結果になるよう `new URL()` は使わない（ブラウザの URL の解釈は寛容で、実装ごとに差がある）。
 */
export function isAcceptableYoEmojiUrl(url: string): boolean {
  if (url.length > YO_MAX_URL_LENGTH) return false;
  // biome-ignore lint/suspicious/noControlCharactersInRegex: 制御文字と空白を弾くための範囲
  if (/[\u0000- \u007f]/.test(url)) return false;
  if (!/^https:\/\//i.test(url)) return false;
  const rest = url.slice("https://".length);
  const end = rest.search(/[/?#]/);
  const authority = end === -1 ? rest : rest.slice(0, end);
  if (authority.includes("@") || authority.includes("\\")) return false;
  // ホスト（IPv6 は [..]）。ポートを除いて空でないこと
  const host = authority.startsWith("[")
    ? authority.includes("]")
      ? authority.slice(1, authority.indexOf("]"))
      : ""
    : (authority.split(":")[0] ?? "");
  return host !== "";
}

/**
 * 届いた候補のうち、author の・署名の通る・いちばん新しい kind:0（Yo の対象）。無ければ null。
 * 同じ `created_at` なら id の小さい方（NIP-01。nostr-yo と同じ）。
 */
export function latestProfileOf(
  candidates: readonly NostrEvent[],
  author: string,
  verify: (event: NostrEvent) => boolean,
): NostrEvent | null {
  const sorted = candidates
    .filter((e) => e.kind === 0 && e.pubkey === author)
    .sort((a, b) => b.created_at - a.created_at || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
  return sorted.find(verify) ?? null;
}

/**
 * 送る Yo（署名前）。profileEventId は相手の最新の kind:0 の id、hint はそれが見つかるリレー（無ければ空）。
 * createdAt を省くと発行のときの時刻になる。
 */
export function yoDraft(
  recipient: string,
  profileEventId: string,
  hint = "",
  createdAt?: number,
): EventDraft {
  if (!HEX64.test(recipient) || !HEX64.test(profileEventId)) throw new Error("invalid Yo target");
  return {
    kind: YO_KIND,
    content: YO_CONTENT,
    tags: [
      ["e", profileEventId, hint, recipient],
      ["p", recipient, hint],
      ["k", "0"],
      ["emoji", YO_SHORTCODE, YO_IMAGE_URL],
      ["t", YO_SHORTCODE],
    ],
    ...(createdAt === undefined ? {} : { created_at: createdAt }),
  };
}

/**
 * 返す Yo の `created_at`。受け取った Yo（receivedAt。無ければ null）より前にしない。
 * 相手の時計が進んでいると、すぐ返した Yo が受け取った Yo より「前」になり、返したことにならないため。
 * 未来へ進めるのは YO_MAX_FUTURE_SEC まで（それより先だと、こちらの Yo が Yo とみなされなくなる）。
 */
export function yoReplyCreatedAt(now: number, receivedAt: number | null): number {
  return receivedAt === null ? now : Math.max(now, Math.min(receivedAt, now + YO_MAX_FUTURE_SEC));
}

/** リアクションの対象がプロフィール（kind:0）か（`k` タグで見る。Yo に限らない） */
export function reactsToProfile(tags: readonly string[][]): boolean {
  return tags.some((tag) => tag[0] === "k" && tag[1] === "0");
}

/** 件数つきの表示（同じ人から 2 件以上届いているとき「Yo ×3」） */
export function yoCountLabel(count: number): string {
  return count > 1 ? `${YO_LABEL} ×${count}` : YO_LABEL;
}

/** 自分が送った Yo のうち、相手ごとのいちばん新しい時刻（「返しました」の判定） */
export function myLatestYoTo(myReactions: readonly NostrEvent[], now: number): Map<string, number> {
  const latest = new Map<string, number>();
  for (const event of myReactions) {
    const to = yoRecipientOf(event, now);
    if (to === null) continue;
    if (event.created_at > (latest.get(to) ?? Number.NEGATIVE_INFINITY)) latest.set(to, event.created_at);
  }
  return latest;
}
