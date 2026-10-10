import { useEffect, useState } from "react";
import { useEmbedPrefs } from "../linkcard/embedPrefs";
import type { OgpResult } from "../linkcard/ogpLoader";
import { linkCardLoaderFor } from "../linkcard/spotifyLoader";
import type { LinkCardState } from "../linkcard/useLinkCards";

type Result = { url: string | null; ogp: OgpResult | undefined };

/** メモリにある結果だけで作る（スクロールで作り直されたときに取得中を挟まない） */
function known(url: string | null, kind: "ogp" | "spotify"): Result {
  return { url, ogp: url === null ? undefined : linkCardLoaderFor(kind).peek(url) };
}

/**
 * ステータスの `r`（URL 1 本）のリンクカード（useLinkCards の 1 URL 版）。
 * url が null、またはその種類の埋め込み設定（Spotify は spotify、それ以外は ogp）が OFF なら null（取りに行かない）。
 * Spotify は OGP の代わりに oEmbed から作る（useLinkCards と同じ spotifyLoader、#820）。
 */
export function useStatusLinkCard(url: string | null, kind: "ogp" | "spotify"): LinkCardState | null {
  const enabled = useEmbedPrefs((s) => (kind === "spotify" ? s.spotify : s.ogp));
  const target = enabled ? url : null;
  const [state, setState] = useState(() => known(target, kind));
  // URL が変わったら（設定の切り替え等）メモリにある分から作り直す
  const current = state.url === target ? state : known(target, kind);
  if (current !== state) setState(current);

  useEffect(() => {
    if (target === null) return;
    let alive = true;
    void linkCardLoaderFor(kind)
      .load(target)
      .then((ogp) => {
        if (!alive) return;
        setState((prev) => (prev.url === target && prev.ogp === ogp ? prev : { url: target, ogp }));
      });
    return () => {
      alive = false;
    };
  }, [target, kind]);

  return target === null ? null : { url: target, kind, ogp: current.ogp };
}
