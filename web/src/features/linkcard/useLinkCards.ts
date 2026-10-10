import type { NostrEvent } from "nostr-tools/pure";
import { useEffect, useMemo, useState } from "react";
import { useEmbedPrefs } from "./embedPrefs";
import { detectEmbeds, type LinkEmbed } from "./linkTargets";
import type { OgpResult } from "./ogpLoader";
import { linkCardLoaderFor } from "./spotifyLoader";

/** 1 枚分。ogp が undefined = 取得中、null = カードにしない */
export type LinkCardState = { url: string; kind: "ogp" | "spotify"; ogp: OgpResult | undefined };

const NONE: readonly string[] = [];
const NONE_DETECTED: readonly LinkEmbed[] = [];
type CardEmbed = LinkEmbed & { kind: "ogp" | "spotify" };
const NONE_CARDS: readonly CardEmbed[] = [];

type Results = { embeds: readonly CardEmbed[]; results: ReadonlyMap<string, OgpResult> };

function isCardEmbed(embed: LinkEmbed): embed is CardEmbed {
  return embed.kind === "ogp" || embed.kind === "spotify";
}

/** メモリにある結果だけで作る（スクロールで作り直されたときに取得中を挟まない） */
function known(embeds: readonly CardEmbed[]): Results {
  const results = new Map<string, OgpResult>();
  for (const { url, kind } of embeds) {
    const result = linkCardLoaderFor(kind).peek(url);
    if (result !== undefined) results.set(url, result);
  }
  return { embeds, results };
}

/**
 * 投稿のリンクカード（ogp・spotify の埋め込みごとの取得状態）と、カードを出した URL（本文から畳む）。
 * Spotify は OGP の代わりに oEmbed から作る（spotifyLoader、#820）。
 * enabled = false（CW を開いていない）の間は取りに行かない。
 */
export function useLinkCards(
  event: NostrEvent,
  enabled: boolean,
): { cards: LinkCardState[]; carded: readonly string[] } {
  const prefs = useEmbedPrefs();
  // detectEmbeds はイベントごとに同じ配列を返す（設定に関係ない検出）ので、依存を真偽値にできる
  const detected = enabled ? detectEmbeds(event) : NONE_DETECTED;
  const cardEmbeds = useMemo(() => {
    const cards = detected.filter(isCardEmbed).filter((e) => (e.kind === "ogp" ? prefs.ogp : prefs.spotify));
    return cards.length === 0 ? NONE_CARDS : cards;
  }, [detected, prefs.ogp, prefs.spotify]);
  const [state, setState] = useState(() => known(cardEmbeds));
  // URL の並びが変わったら（CW を開いた等）メモリにある分から作り直す
  const current = state.embeds === cardEmbeds ? state : known(cardEmbeds);
  if (current !== state) setState(current);

  useEffect(() => {
    let alive = true;
    for (const { url, kind } of cardEmbeds) {
      void linkCardLoaderFor(kind)
        .load(url)
        .then((result) => {
          if (!alive) return;
          setState((prev) =>
            prev.embeds !== cardEmbeds || (prev.results.has(url) && prev.results.get(url) === result)
              ? prev
              : { embeds: cardEmbeds, results: new Map(prev.results).set(url, result) },
          );
        });
    }
    return () => {
      alive = false;
    };
  }, [cardEmbeds]);

  const cards = cardEmbeds.map((e) => ({ url: e.url, kind: e.kind, ogp: current.results.get(e.url) }));

  // hideCardedUrls が ON の間、実際に出せた OGP/Spotify のカードと、出した YouTube の URL を畳む
  const cardedKey = prefs.hideCardedUrls
    ? [
        ...cards.filter((card) => card.ogp).map((card) => card.url),
        ...detected.filter((e) => e.kind === "youtube" && prefs.youtube).map((e) => e.url),
      ].join("\n")
    : "";
  // 本文の木を作り直さないよう、畳む URL が同じなら同じ配列を返す（URL は空白を含まないので改行で区切れる）
  const carded = useMemo(() => (cardedKey === "" ? NONE : cardedKey.split("\n")), [cardedKey]);
  return { cards, carded };
}
