import { getSeenRelays } from "applesauce-core/helpers/relays";
import type { NostrEvent } from "nostr-tools/pure";
import { memo, useEffect, useMemo, useRef, useState } from "react";
import { Link, useNavigate } from "react-router";
import { useT } from "../../i18n";
import { formatDateTimeLocal } from "../../i18n/format";
import { avatarInitial, avatarShade } from "../../lib/avatar";
import { hrefForEvent, hrefForProfile } from "../../lib/content/labels";
import { isBlankContent, parseNoteContent, withoutLinks, withoutMention } from "../../lib/content/parse";
import { clientNameOf, contentWarningOf, quotePointerOf, replyParentPointerOf } from "../../lib/content/tags";
import { isDataSaver, markProxyBlocked, originOf, proxied } from "../../lib/imageProxy";
import { extractMedia } from "../../lib/media";
import { relativeTime } from "../../lib/time";
import { displayName, pictureOf, useEventByPointer, useProfile, useRepostedEvent } from "../../nostr/loaders";
import { CatEars } from "../../ui/CatEars";
import { useNyanApplies } from "../../ui/nyan";
import { NoteActionButtons, NoteMoreMenu } from "../actions/NoteActionButtons";
import { ArticleCards } from "../article/ArticleCard";
import { ChatChannelLine } from "../chat/ChatChannelLine";
import { roomHrefOf } from "../chat/chatMessage";
import { NoteFooter } from "../compose/NoteFooter";
import { LinkCards } from "../linkcard/LinkCard";
import { useLinkCards } from "../linkcard/useLinkCards";
import { NoteMedia } from "../media/NoteMedia";
import { type NoteAccentKind, noteAccentKindOf } from "../theme/noteAccent";
import { useThemePrefs } from "../theme/themePrefs";
import { useTranslation, useTranslationPending } from "../translate/translateStore";
import { CollapsibleContent } from "./CollapsibleContent";
import { ContentWarning } from "./ContentWarning";
import { NoteContent } from "./NoteContent";
import styles from "./NoteItem.module.css";
import { QuoteCard } from "./QuoteCard";
import { ReplyContext } from "./ReplyContext";
import { RepostHeader } from "./RepostHeader";
import { useNow } from "./useNow";
import { useOpenOnClick } from "./useOpenOnClick";

/** [#540] アバターのプロキシ幅（ネイティブの Avatar.kt と同じ 256。アニメはデータセーバー中だけ止める） */
const AVATAR_PROXY_WIDTH = 256;

/**
 * リポスト元を待つ時間。過ぎても取れなければ行ごと隠す
 * （ネイティブは未取得の間は行を出さない。待ち時間の値はネイティブに無い）
 */
const REPOST_WAIT_MS = 8_000;

/** 待ちきれずに隠したリポストの id（仮想リストで作り直されたとき、また「読み込み中…」から始めない） */
const gaveUpReposts = new Set<string>();

/**
 * [#464] ノート種別の視覚表示（設定 > 表示。既定は「なし」）のクラス。
 * ライン（左2px。box-shadow なので `background` を使う選択ハイライト（KbList.module.css）とは
 * 競合しない）／背景色（alpha 0.14。KbList.module.css の選択時の背景より詳細度を弱くして、
 * 選択ハイライトを前面にする）。ネイティブ NoteItem.kt 156–168 と同じ2種。
 */
const ACCENT_CLASS: Record<"line" | "bg", Record<NoteAccentKind, string>> = {
  line: {
    repost: styles.accentLineRepost,
    quote: styles.accentLineQuote,
    reply: styles.accentLineReply,
    reaction: styles.accentLineReaction,
  },
  bg: {
    repost: styles.accentBgRepost,
    quote: styles.accentBgQuote,
    reply: styles.accentBgReply,
    reaction: styles.accentBgReaction,
  },
};

function accentClassOf(kind: NoteAccentKind | null, style: "none" | "line" | "bg"): string | null {
  if (kind === null || style === "none") return null;
  return ACCENT_CLASS[style][kind];
}

/**
 * タイムラインの 1 件（ネイティブの NoteItem.kt）。返信先の 1 行・アバター・表示名・NIP-05・相対時刻・本文・引用カード。
 * kind:6/16 は「🔁 (アバター) 名前」の行を付けて元投稿を出す。
 * openable（既定 true）なら全体のクリックと時刻のリンクでスレッドを開く（スレッドの行では false）。
 * embedded（既定 false）は通知の行の本体用: 下線と返信先の 1 行を出さない（kind 1 / 1111 のみ）。
 */
export const NoteItem = memo(function NoteItem({
  event,
  openable = true,
  embedded = false,
}: {
  event: NostrEvent;
  openable?: boolean;
  embedded?: boolean;
}) {
  if (event.kind === 6 || event.kind === 16) return <RepostItem repost={event} openable={openable} />;
  return <PostItem event={event} openable={openable} embedded={embedded} />;
});

function PostItem({
  event,
  openable,
  embedded,
}: {
  event: NostrEvent;
  openable: boolean;
  embedded: boolean;
}) {
  const ref = useRef<HTMLElement>(null);
  const href = openable ? threadHrefOf(event) : null;
  useOpenOnClick(ref, href);
  const base = embedded ? styles.embedded : styles.note;
  const noteAccent = useThemePrefs((s) => s.noteAccent);
  const accentKind = useMemo(
    () =>
      noteAccentKindOf({
        isRepost: false,
        hasQuote: quotePointerOf(event) !== null,
        isReaction: event.kind === 7,
        isReply: replyParentPointerOf(event) !== null,
      }),
    [event],
  );
  const accentClass = accentClassOf(accentKind, noteAccent);
  const className = [base, href ? styles.openable : null, accentClass].filter(Boolean).join(" ");
  return (
    <article ref={ref} className={className}>
      <NoteBody event={event} threadHref={href} embedded={embedded} />
    </article>
  );
}

function RepostItem({ repost, openable }: { repost: NostrEvent; openable: boolean }) {
  const t = useT();
  const original = useRepostedEvent(repost);
  const ref = useRef<HTMLElement>(null);
  // 開く先は元投稿（未解決の間は開かない）
  const href = openable && original ? threadHrefOf(original) : null;
  useOpenOnClick(ref, href);
  const [gaveUp, setGaveUp] = useState(() => gaveUpReposts.has(repost.id));
  const waiting = original === undefined && !gaveUp;
  useEffect(() => {
    if (!waiting) return;
    const timer = setTimeout(() => {
      gaveUpReposts.add(repost.id);
      setGaveUp(true);
    }, REPOST_WAIT_MS);
    return () => clearTimeout(timer);
  }, [waiting, repost.id]);
  const noteAccent = useThemePrefs((s) => s.noteAccent);
  // 取れないまま待ち時間が過ぎたら隠す（仮想リストは高さ 0 の行を扱えないので 1px の空行）。後から届けば出す
  if (!original && gaveUp) return <div className={styles.hiddenRow} aria-hidden="true" />;
  const accentClass = accentClassOf("repost", noteAccent);
  const className = [styles.note, href ? styles.openable : null, accentClass].filter(Boolean).join(" ");
  return (
    <article ref={ref} className={className}>
      <RepostHeader reposter={repost.pubkey} />
      {original ? (
        <NoteBody event={original} threadHref={href} />
      ) : (
        <p className={styles.missing}>{t("web_note_loading_original")}</p>
      )}
    </article>
  );
}

/**
 * スレッドを開くリンク先。受け取ったリレーを 2 件までヒントに付ける。
 * [#796] パブリックチャットの発言（kind:42）はそのチャンネルのルーム（スレッドにはチャンネル内の返信が出ないため。ネイティブと同じ）
 */
function threadHrefOf(target: NostrEvent): string {
  const room = target.kind === 42 ? roomHrefOf(target) : null;
  if (room !== null) return room;
  const seen = [...(getSeenRelays(target) ?? [])].slice(0, 2);
  return hrefForEvent(
    seen.length > 0
      ? { id: target.id, author: target.pubkey, relays: seen }
      : { id: target.id, author: target.pubkey },
  );
}

function NoteBody({
  event,
  threadHref,
  embedded = false,
}: {
  event: NostrEvent;
  threadHref: string | null;
  embedded?: boolean;
}) {
  const profile = useProfile(event.pubkey);
  const picture = pictureOf(profile);
  const name = displayName(profile, event.pubkey);
  const nip05 =
    typeof profile?.nip05 === "string" && profile.nip05.trim() !== "" ? profile.nip05.trim() : null;
  const profileHref = hrefForProfile(event.pubkey);

  // CW の開封はこの表示の間だけ覚える
  const [revealed, setRevealed] = useState(false);
  const warning = contentWarningOf(event);

  // 本文中の参照は、引用元が取れてカードに出せたときだけ本文から消す（取れない間は ↗ のリンクのまま）
  const quote = useMemo(() => quotePointerOf(event), [event]);
  const quoted = useEventByPointer(quote?.pointer ?? null);
  const hideMention = quoted ? (quote?.encoded ?? null) : null;
  // リンクも同じく、カードに出せたものだけ本文から消す（取得中・取れなかったものはリンクのまま）
  const linkCards = useLinkCards(event, warning === null || revealed);
  const hasText = useMemo(
    () =>
      !isBlankContent(withoutLinks(withoutMention(parseNoteContent(event), hideMention), linkCards.carded)),
    [event, hideMention, linkCards.carded],
  );
  const media = extractMedia(event);
  const hasMedia = media.images.length + media.videos.length + media.youtube.length > 0;
  // [#796] パブリックチャットの発言は、返信もそのチャンネルのルームで（ここから返すと kind:1 の返信になってしまう）
  const navigate = useNavigate();
  const roomHref = event.kind === 42 ? roomHrefOf(event) : null;

  return (
    <>
      {!embedded && event.kind === 42 && <ChatChannelLine message={event} />}
      {!embedded && <ReplyContext event={event} />}
      <div className={styles.row}>
        {/* 名前と同じリンク先なので、読み上げ・タブ移動は名前の方だけにする */}
        <Link className={styles.avatarLink} to={profileHref} tabIndex={-1} aria-hidden="true">
          <Avatar key={picture} url={picture} size="md" seed={name} pubkey={event.pubkey} />
        </Link>
        <div className={styles.main}>
          <div className={styles.meta}>
            <span className={styles.author}>
              <Link className={styles.name} to={profileHref}>
                {name}
              </Link>
              {nip05 && <span className={styles.handle}>{nip05}</span>}
            </span>
            {threadHref ? (
              <Link to={threadHref} className={styles.timeLink}>
                <RelativeTime createdAt={event.created_at} client={clientNameOf(event)} />
              </Link>
            ) : (
              <RelativeTime createdAt={event.created_at} client={clientNameOf(event)} />
            )}
          </div>
          {warning !== null && !revealed ? (
            <ContentWarning reason={warning} onReveal={() => setRevealed(true)} />
          ) : (
            <>
              {hasText && (
                <CollapsibleContent event={event}>
                  <NoteContent event={event} hideMention={hideMention} hideLinks={linkCards.carded} />
                </CollapsibleContent>
              )}
              <TranslationBlock eventId={event.id} />
              {quote && <QuoteCard pointer={quote.pointer} encoded={quote.encoded} />}
              {hasMedia && <NoteMedia media={media} />}
              <LinkCards cards={linkCards.cards} />
              <ArticleCards content={event.content} />
            </>
          )}
          <NoteFooter
            event={event}
            more={<NoteMoreMenu event={event} />}
            onReply={roomHref !== null ? () => void navigate(roomHref) : undefined}
          >
            <NoteActionButtons event={event} />
          </NoteFooter>
        </div>
      </div>
    </>
  );
}

/**
 * ⋯「翻訳」の結果（本文の下に別ブロック。#541。ネイティブ NoteItem.kt と同じ置き場所）。
 * キャプションは常に「翻訳」、取得中はその横にスピナーを出す（ネイティブと同じ。N5）。
 * 隠している間（visible: false）は取得済みでも何も描かない。
 */
function TranslationBlock({ eventId }: { eventId: string }) {
  const t = useT();
  const entry = useTranslation(eventId);
  const pending = useTranslationPending(eventId);
  if (!pending && !entry?.visible) return null;
  return (
    <div className={styles.translation}>
      <p className={styles.translationCaption}>
        {t("note_translation_caption")}
        {pending && <span className={styles.translationSpinner} aria-hidden="true" />}
      </p>
      {entry?.visible && <p className={styles.translationText}>{entry.text}</p>}
    </div>
  );
}

/**
 * 相対時刻（30 秒ごとに進む）。title に日時と、client タグがあれば「… · <client> から投稿」を出す
 * （行には出さない = ネイティブと同じ）。
 */
function RelativeTime({ createdAt, client }: { createdAt: number; client: string | null }) {
  const t = useT();
  const now = useNow();
  const date = new Date(createdAt * 1000);
  // created_at は任意の数値なので、Date の範囲外なら属性を付けない（toISOString が例外を投げる）
  const valid = Number.isFinite(date.getTime());
  const title = valid
    ? `${formatDateTimeLocal(date)}${client ? ` · ${t("note_posted_via_fmt", client)}` : ""}`
    : undefined;
  return (
    <time className={styles.time} dateTime={valid ? date.toISOString() : undefined} title={title}>
      {relativeTime(createdAt, now)}
    </time>
  );
}

/**
 * md = 38px（タイムライン）、sm = 16px（リポストヘッダ）、xs = 20px（リアクションした人の列）、
 * lg = 40px（ユーザー一覧）、xl = 60px（PROFILE カラムの上部カード）、xxl = 72px（プロフィール）
 */
export type AvatarSize = "md" | "sm" | "xs" | "lg" | "xl" | "xxl";

/** [#540] プロキシ幅。ネイティブの Avatar は表示サイズに関わらずすべて 256 */
const AVATAR_PROXY: Record<AvatarSize, number> = {
  md: AVATAR_PROXY_WIDTH,
  sm: AVATAR_PROXY_WIDTH,
  xs: AVATAR_PROXY_WIDTH,
  lg: AVATAR_PROXY_WIDTH,
  xl: AVATAR_PROXY_WIDTH,
  xxl: AVATAR_PROXY_WIDTH,
};

const AVATAR_CLASS: Record<AvatarSize, string> = {
  md: styles.avatar,
  sm: styles.avatarSm,
  xs: styles.avatarXs,
  lg: styles.avatarLg,
  xl: styles.avatarXl,
  xxl: styles.avatarXxl,
};

/**
 * wsrv.nl を通した画像 URL。http(s) 以外は出さない。
 * [#540] q=80・n=-1（アニメ保持）。データセーバー中は今まで通り先頭フレームだけにする。
 */
function avatarSrc(url: string | undefined, width: number): string | null {
  if (!url || !/^https?:\/\//i.test(url.trim())) return null;
  return proxied(url, width, 80, !isDataSaver());
}

/**
 * アバター。プロキシが読めなければ元 URL（https のみ）で 1 度だけ取り直し、そのホストを拒否として学習する。
 * 画像が無い・読めないときは seed（ネイティブと同じく名前か pubkey）の頭文字をグレーの丸に出す。
 * url が変わったら呼び出し側の key で作り直す。
 * [#540] pubkey（hex。分かる呼び出し元だけが渡す）でにゃんモードの対象なら猫耳を重ねる。
 */
export function Avatar({
  url,
  size,
  seed,
  pubkey,
}: {
  url: string | undefined;
  size: AvatarSize;
  seed: string;
  pubkey?: string;
}) {
  const [src, setSrc] = useState(() => avatarSrc(url, AVATAR_PROXY[size]));
  const className = AVATAR_CLASS[size];
  const nyan = useNyanApplies(pubkey);

  function onError() {
    const origin = originOf(src);
    if (origin) {
      markProxyBlocked(origin);
      setSrc(/^https:\/\//i.test(origin) ? origin : null);
    } else {
      setSrc(null);
    }
  }

  const body = !src ? (
    <span
      className={nyan ? `${styles.nyanBody} ${styles.initial}` : `${className} ${styles.initial}`}
      style={{ background: avatarShade(seed) }}
      aria-hidden="true"
    >
      {avatarInitial(seed)}
    </span>
  ) : (
    <img
      className={nyan ? styles.nyanBody : className}
      src={src}
      alt=""
      loading="lazy"
      decoding="async"
      referrerPolicy="no-referrer"
      onError={onError}
    />
  );

  if (!nyan) return body;
  return (
    <span className={`${className} ${styles.nyanFrame}`}>
      <CatEars />
      {body}
    </span>
  );
}
