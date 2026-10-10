import type { NostrEvent } from "nostr-tools/pure";
import { useState } from "react";
import { Link } from "react-router";
import { useT } from "../../i18n";
import { hrefForProfile } from "../../lib/content/labels";
import { markProxyBlocked, originOf, proxied } from "../../lib/imageProxy";
import { relativeTime } from "../../lib/time";
import { displayName, pictureOf, useProfile } from "../../nostr/loaders";
import {
  AddReactionIcon,
  FavoriteBorderIcon,
  FavoriteIcon,
  ReplyIcon,
  StarBorderIcon,
  StarIcon,
} from "../../ui/icons";
import { ScreenHeader } from "../../ui/ScreenHeader";
import { showToast } from "../../ui/toast";
import { reactionSentMessage, saveMadeEmoji } from "../actions/pickedReaction";
import { ReactionPickerDialog } from "../actions/ReactionPickerDialog";
import { useDefaultReaction } from "../actions/reactionPrefs";
import { publishReaction, reactWithDefault, useIsReacted } from "../actions/reactions";
import { openCompose } from "../compose/composeStore";
import { ActionButton } from "../compose/NoteFooter";
import type { ThreadEntry } from "../thread/threadTree";
import { Avatar, NoteItem } from "../timeline/NoteItem";
import { ArticleMarkdown } from "./ArticleMarkdown";
import styles from "./ArticleReader.module.css";

function tagValue(event: NostrEvent, name: string): string | null {
  const value = event.tags.find((t) => t[0] === name && typeof t[1] === "string")?.[1];
  return value?.trim() ? value.trim() : null;
}

function warn(message: string) {
  return (e: unknown) => console.warn(`[article] ${message}`, e);
}

/**
 * [#534] NIP-23 長文記事（kind:30023）のビューワー（ネイティブ ArticleReader.kt の Web 版）。
 * タイトル・画像・概要・著者行 → 本文（ArticleMarkdown）→ アクション（コメント / 既定リアクション / 絵文字）
 * → コメント一覧（記事への返信。ThreadScreen の entries から記事本体を除いたもの＝ネイティブと同じ）。
 */
export function ArticleReader({
  article,
  comments,
  onBack,
}: {
  article: NostrEvent;
  comments: readonly ThreadEntry[];
  onBack: () => void;
}) {
  const t = useT();
  const profile = useProfile(article.pubkey);
  const title = tagValue(article, "title") ?? t("article_untitled");
  const image = tagValue(article, "image");
  const summary = tagValue(article, "summary");
  const publishedAt = Number(tagValue(article, "published_at")) || article.created_at;

  return (
    <>
      <ScreenHeader title={t("article_title")} subtitle="NIP-23 · kind:30023" onBack={onBack} />
      <div className={styles.screen}>
        <h1 className={styles.title}>{title}</h1>
        <Link className={styles.author} to={hrefForProfile(article.pubkey)}>
          <Avatar url={pictureOf(profile)} size="sm" seed={article.pubkey} pubkey={article.pubkey} />
          <span className={styles.authorName}>{displayName(profile, article.pubkey)}</span>
          <span className={styles.time}>{relativeTime(publishedAt)}</span>
        </Link>
        {image && <ArticleBanner url={image} />}
        {summary && <p className={styles.summary}>{summary}</p>}
        <hr className={styles.divider} />
        <ArticleMarkdown content={article.content} />
        <hr className={styles.divider} />
        <div className={styles.actions}>
          <ActionButton
            label={t("article_comment")}
            onClick={() => openCompose({ mode: "reply", target: article })}
          >
            <ReplyIcon />
          </ActionButton>
          <DefaultReactionButton event={article} />
          <EmojiReactionButton event={article} />
        </div>
        {comments.length > 0 && (
          <>
            <p className={styles.commentsHeader}>{t("article_comments_fmt", comments.length)}</p>
            {comments.map((entry) => (
              <NoteItem key={entry.event.id} event={entry.event} />
            ))}
          </>
        )}
      </div>
    </>
  );
}

/** バナー画像。プロキシが読めなければ元 URL（https のみ）で 1 度だけ取り直し、それも読めなければ隠す */
function ArticleBanner({ url }: { url: string }) {
  const [src, setSrc] = useState<string | null>(() => proxied(url, 900, 80));
  if (!src) return null;

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
      className={styles.banner}
      src={src}
      alt=""
      loading="lazy"
      decoding="async"
      referrerPolicy="no-referrer"
      onError={onError}
    />
  );
}

/** 既定リアクション（♡ / ☆）。押すたびに付与・取り消しをトグルする（記事の行では確認を挟まない） */
function DefaultReactionButton({ event }: { event: NostrEvent }) {
  const t = useT();
  const content = useDefaultReaction((s) => s.content);
  const isStar = content === "⭐" || content === "★";
  const active = useIsReacted(event.id);
  const Glyph = isStar ? (active ? StarIcon : StarBorderIcon) : active ? FavoriteIcon : FavoriteBorderIcon;
  return (
    <ActionButton
      label={t("section_reaction")}
      pressed={active}
      onClick={() => void reactWithDefault(event).catch(warn("Failed to react"))}
    >
      <Glyph />
    </ActionButton>
  );
}

/** 絵文字でリアクション（ピッカーを開き、選んだものを送る） */
function EmojiReactionButton({ event }: { event: NostrEvent }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  return (
    <>
      <ActionButton label={t("web_emoji_reaction")} onClick={() => setOpen(true)}>
        <AddReactionIcon />
      </ActionButton>
      {open && (
        <ReactionPickerDialog
          target={event}
          onPick={(c, url, made) =>
            void publishReaction(event, c, url)
              .then(() => showToast(reactionSentMessage(c, made)), warn("Failed to react"))
              // [#768] 「自分の絵文字リストにも保存」は送信の後に（成否はリアクションとは別のトースト）
              .then(() => saveMadeEmoji(c, url, made))
          }
          onClose={() => setOpen(false)}
        />
      )}
    </>
  );
}
