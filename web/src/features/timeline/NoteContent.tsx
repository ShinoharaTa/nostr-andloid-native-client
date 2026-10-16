import type {
  BlossomURI,
  Emoji,
  Gallery,
  Hashtag,
  Link as LinkNast,
  Mention,
  Root,
  Text,
} from "applesauce-content/nast";
import type { ProfileContent } from "applesauce-core/helpers/profile";
import type { ComponentMap } from "applesauce-react/helpers";
import { useRenderNast } from "applesauce-react/hooks/use-render-nast";
import type { NostrEvent } from "nostr-tools/pure";
import { createContext, useContext, useMemo, useState } from "react";
import { Link } from "react-router";
import {
  hrefForEvent,
  hrefForProfile,
  hrefForTag,
  mentionLabel,
  shortUrlLabel,
} from "../../lib/content/labels";
import { parseNoteContent, splitTrailingPunct, withoutLinks, withoutMention } from "../../lib/content/parse";
import { markProxyBlocked, originOf, proxiedEmoji } from "../../lib/imageProxy";
import { useProfile } from "../../nostr/loaders";
import { nyaize, useNyanApplies } from "../../ui/nyan";
import styles from "./NoteContent.module.css";

const EXTERNAL_LINK = { target: "_blank", rel: "noopener noreferrer nofollow ugc" } as const;

/** "full" = タイムラインの本文、"quote" = 引用カード内（リンク・メンション・タグは装飾だけでタップしない） */
type Variant = "full" | "quote";

/** [#540] nyaize は投稿者への にゃんモードの適用可否（トークン化後のテキスト片だけに掛ける） */
type ContentCtx = { variant: Variant; nyaize: boolean };

const NoteContentContext = createContext<ContentCtx>({ variant: "full", nyaize: false });

/** http(s) だけを <a> にする（javascript: 等は文字のまま） */
function isWebUrl(href: string): boolean {
  return /^https?:\/\//i.test(href);
}

// [#540] にゃんモード: プレーンテキスト断片だけを nyaize する（URL・メンション・タグ・shortcode は別ノードなので影響しない）
function TextNode({ node }: { node: Text }) {
  const { nyaize: shouldNyaize } = useContext(NoteContentContext);
  return shouldNyaize ? nyaize(node.value) : node.value;
}

// 末尾の句読点（"…/a." の "."）はリンクに含めず、後ろに文字として出す
function LinkNode({ node }: { node: LinkNast }) {
  const { variant } = useContext(NoteContentContext);
  const [url, tail] = splitTrailingPunct(node.value);
  if (!isWebUrl(url)) return node.value;
  return (
    <>
      {variant === "quote" ? (
        <span className={styles.link}>{shortUrlLabel(url)}</span>
      ) : (
        <a className={styles.link} href={url} {...EXTERNAL_LINK}>
          {url}
        </a>
      )}
      {tail}
    </>
  );
}

// 連続した URL は applesauce が gallery にまとめる。画像・動画は取り除き済みなので、残ったものを 1 行ずつ出す
function GalleryNode({ node }: { node: Gallery }) {
  const { variant } = useContext(NoteContentContext);
  return (
    <span className={styles.gallery}>
      {node.links.map((href, i) =>
        isWebUrl(href) && variant === "full" ? (
          // biome-ignore lint/suspicious/noArrayIndexKey: 同じ URL が並ぶことがあり、位置が唯一の識別子
          <a key={i} className={styles.link} href={href} {...EXTERNAL_LINK}>
            {href}
          </a>
        ) : (
          // biome-ignore lint/suspicious/noArrayIndexKey: 同上
          <span key={i} className={isWebUrl(href) ? styles.link : undefined}>
            {isWebUrl(href) ? shortUrlLabel(href) : href}
          </span>
        ),
      )}
    </span>
  );
}

function HashtagNode({ node }: { node: Hashtag }) {
  const { variant } = useContext(NoteContentContext);
  if (variant === "quote") return <span className={styles.hashtag}>#{node.name}</span>;
  return (
    <Link className={styles.hashtag} to={hrefForTag(node.hashtag)}>
      #{node.name}
    </Link>
  );
}

/** プロフィールの名前（display_name → displayName → name）。無ければ undefined（npub の短縮は mentionLabel が出す） */
function profileName(profile: ProfileContent | undefined): string | undefined {
  for (const value of [profile?.display_name, profile?.displayName, profile?.name]) {
    if (typeof value === "string" && value.trim() !== "") return value.trim();
  }
  return undefined;
}

function ProfileMention({ pubkey, encoded }: { pubkey: string; encoded: string }) {
  const { variant } = useContext(NoteContentContext);
  const label = mentionLabel(encoded, profileName(useProfile(pubkey)));
  if (variant === "quote") return <span className={styles.mention}>{label}</span>;
  return (
    <Link className={styles.mention} to={hrefForProfile(pubkey)}>
      {label}
    </Link>
  );
}

function MentionNode({ node }: { node: Mention }) {
  const { variant } = useContext(NoteContentContext);
  const { decoded } = node;
  switch (decoded.type) {
    case "npub":
      return <ProfileMention pubkey={decoded.data} encoded={node.encoded} />;
    case "nprofile":
      return <ProfileMention pubkey={decoded.data.pubkey} encoded={node.encoded} />;
    case "note":
    case "nevent":
    case "naddr":
      // [#534] naddr（記事 kind:30023 等）も note/nevent と同じくアプリ内リンクにする（/e/:ref が受ける）
      if (variant === "quote") return <span className={styles.mention}>{mentionLabel(node.encoded)}</span>;
      return (
        <Link className={styles.mention} to={hrefForEvent(node.encoded)}>
          {mentionLabel(node.encoded)}
        </Link>
      );
    default:
      return node.encoded;
  }
}

/** カスタム絵文字（NIP-30）。https の画像だけを出し、それ以外は書かれた文字のまま */
function EmojiNode({ node }: { node: Emoji }) {
  if (!/^https:\/\//i.test(node.url.trim())) return node.raw;
  // url が変わったら作り直す（読み込み失敗の状態を持ち越さない）
  return <EmojiImage key={node.url} url={node.url} code={node.code} />;
}

/**
 * 絵文字の画像。プロキシが読めなければ元 URL（https のみ）で 1 度だけ取り直し、そのホストを拒否として学習する。
 * それも読めなければ :code: の文字に戻す。
 */
function EmojiImage({ url, code }: { url: string; code: string }) {
  const [src, setSrc] = useState<string | null>(() => proxiedEmoji(url, 75));
  const label = `:${code}:`;
  if (!src) return label;

  function onError() {
    const origin = originOf(src);
    if (origin) {
      markProxyBlocked(origin);
      setSrc(/^https:\/\//i.test(origin) ? origin : null);
    } else {
      setSrc(null);
    }
  }

  return (
    <img
      className={styles.emoji}
      src={src}
      alt={label}
      title={label}
      loading="lazy"
      decoding="async"
      referrerPolicy="no-referrer"
      onError={onError}
    />
  );
}

// blossom: は書かれた文字のまま出す
function BlossomNode({ node }: { node: BlossomURI }) {
  return node.raw;
}

const components: ComponentMap = {
  text: TextNode,
  link: LinkNode,
  gallery: GalleryNode,
  hashtag: HashtagNode,
  mention: MentionNode,
  emoji: EmojiNode,
  blossom: BlossomNode,
};

const NO_LINKS: readonly string[] = [];

/**
 * 本文。applesauce-content の構文木（parseNoteContent）を React 要素にする。画像・動画の URL は木から除いてある。
 * hideMention は引用カードに出した参照、hideLinks はリンクカードに出した URL（どちらも本文からは消す）。
 * HTML としては一切解釈しない（dangerouslySetInnerHTML は使わない）。
 */
export function NoteContent({
  event,
  variant = "full",
  hideMention = null,
  hideLinks = NO_LINKS,
}: {
  event: NostrEvent;
  variant?: Variant;
  hideMention?: string | null;
  hideLinks?: readonly string[];
}) {
  const root = useMemo(
    () => withoutLinks(withoutMention(parseNoteContent(event), hideMention), hideLinks),
    [event, hideMention, hideLinks],
  );
  const content = useRenderNast(root, components);
  const nyaize = useNyanApplies(event.pubkey);
  return (
    <NoteContentContext.Provider value={{ variant, nyaize }}>
      <div className={variant === "quote" ? `${styles.content} ${styles.quote}` : styles.content}>
        {content}
      </div>
    </NoteContentContext.Provider>
  );
}

/**
 * 構文木をそのまま本文と同じ部品で描く（プロフィールの自己紹介など、ノート以外の文章用）。
 * size = "sub" で文字を一段小さくする。
 */
export function RichText({ root, size = "body" }: { root: Root; size?: "body" | "sub" }) {
  const content = useRenderNast(root, components);
  return (
    <NoteContentContext.Provider value={{ variant: "full", nyaize: false }}>
      <div className={size === "sub" ? `${styles.content} ${styles.sub}` : styles.content}>{content}</div>
    </NoteContentContext.Provider>
  );
}
