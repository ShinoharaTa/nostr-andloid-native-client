import { type ReactNode, useMemo, useState } from "react";
import { proxiedEmojiOnly } from "../../lib/imageProxy";

/**
 * [#1025] Yo の絵（送り主が付けた絵文字の画像。ネイティブ YoUi.kt YoImage）。読み込めるまで・読み込めなかったとき・
 * url が無いときは、fallback（描いた「Yo!」など）を出す。
 *
 * 画像は**画像プロキシ経由でだけ**読み込む。URL は送り主が選んだもので、SPEC は「受け取る側は取りに行かないほうがよい」
 * としている（送り主のサーバーに、見た時刻と IP アドレスが伝わるため）。プロキシ経由なら、送り主のサーバーにつなぐのは
 * プロキシなので伝わらない。プロキシが受け付けないホストの絵は読み込まず、fallback のままにする（ほかのカスタム絵文字の
 * ように、読めなかったら配信元へ直接取りに行く、ということはしない）。
 */
export function YoImage({
  url,
  className,
  fallback,
}: {
  url: string | null;
  className: string;
  fallback: ReactNode;
}) {
  const src = useMemo(() => (url === null ? null : proxiedEmojiOnly(url)), [url]);
  // 読み込めた / 読み込めなかった src（src が変われば、どちらでもない状態に戻る）
  const [loaded, setLoaded] = useState<string | null>(null);
  const [failed, setFailed] = useState<string | null>(null);
  if (src === null || failed === src) return <>{fallback}</>;
  const ready = loaded === src;
  return (
    <>
      {!ready && fallback}
      <img
        className={className}
        src={src}
        alt=""
        decoding="async"
        referrerPolicy="no-referrer"
        // 読み込めるまでは場所を取らない（fallback が出ている）
        hidden={!ready}
        onLoad={() => setLoaded(src)}
        onError={() => setFailed(src)}
      />
    </>
  );
}
