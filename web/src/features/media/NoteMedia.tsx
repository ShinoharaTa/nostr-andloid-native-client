import { type ReactNode, useMemo, useState } from "react";
import { type NoteMedia as Media, urlPositions } from "../../lib/media";
import { useEmbedPrefs } from "../linkcard/embedPrefs";
import { LinkCardItem } from "../linkcard/LinkCard";
import type { LinkCardState } from "../linkcard/useLinkCards";
import { ImageGrid } from "./ImageGrid";
import { Lightbox } from "./Lightbox";
import styles from "./NoteMedia.module.css";
import { VideoPlayer } from "./VideoPlayer";
import { YouTubeCard } from "./YouTubeCard";

/**
 * 動画・YouTube の上限（ネイティブの Embed.kt detectEmbeds(max = 4)）。画像は別枠で上限なし。
 * ネイティブは出現順に混ぜて 4 件だが、ここでは動画 → YouTube の順に数える（何を出すかは従来どおり。並べるのは出現順）。
 */
const EMBED_LIMIT = 4;

const NO_CARDS: LinkCardState[] = [];

/**
 * 投稿のメディアと埋め込み（NoteItem からの唯一の入口）。ネイティブ NoteItem.kt と同じく、画像（グリッド / カルーセル →
 * ライトボックス）をまとめて先に出し、その下に動画・YouTube・リンクカード（cards）を本文（content）の出現順に 1 列で並べる
 * （ネイティブ LinkEmbeds.kt。同じ URL が何度も出るときは最初の位置）。content が無ければ 動画 → YouTube → リンクカード の順。
 */
export function NoteMedia({
  media,
  cards = NO_CARDS,
  content = "",
}: {
  media: Media;
  cards?: LinkCardState[];
  content?: string;
}) {
  const [lightbox, setLightbox] = useState<number | null>(null);
  const prefs = useEmbedPrefs();
  const positions = useMemo(() => urlPositions(content), [content]);
  const { images } = media;
  // 上限 4 は設定に関係なく決める（動画を OFF にしても、その分を YouTube に回さない）
  const videosDetected = media.videos.slice(0, EMBED_LIMIT);
  const youtubeDetected = media.youtube.slice(0, EMBED_LIMIT - videosDetected.length);
  const videos = prefs.video ? videosDetected : [];
  const youtube = prefs.youtube ? youtubeDetected : [];

  const embeds: { url: string; node: ReactNode }[] = [
    ...videos.map((video) => ({
      url: video.url,
      node: <VideoPlayer key={`video:${video.url}`} item={video} />,
    })),
    ...youtube.map((video) => ({
      url: video.url,
      node: <YouTubeCard key={`youtube:${video.id}`} id={video.id} />,
    })),
    // 取れなかった（null）カードは出さない（本文のリンクがそのまま残る）
    ...cards
      .filter((card) => card.ogp !== null)
      .map((card) => ({
        url: card.url,
        node: <LinkCardItem key={`card:${card.url}`} card={card} ogpImages={prefs.ogpImages} />,
      })),
  ];
  // 本文に見つからないもの（content 無し）は後ろへ。sort は安定なので、位置が同じなら元の順のまま
  const at = (url: string) => positions.get(url) ?? Number.MAX_SAFE_INTEGER;
  embeds.sort((a, b) => at(a.url) - at(b.url));
  if (images.length + embeds.length === 0) return null;

  return (
    <>
      <div className={styles.media}>
        {images.length > 0 && <ImageGrid images={images} onOpen={setLightbox} />}
        {embeds.map((embed) => embed.node)}
      </div>
      {lightbox !== null && <Lightbox items={images} index={lightbox} onClose={() => setLightbox(null)} />}
    </>
  );
}
