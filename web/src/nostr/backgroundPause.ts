import { pauseNip46, resumeNip46 } from "../signer/nip46";
import { pauseRelays, resumeRelays } from "./pool";
import { retryUnsent } from "./publish";

/** 非表示がこれだけ続いたら全リレーを一時停止する（ネイティブ BG_PAUSE_DELAY_MS。タブの切り替え程度では切らない） */
export const BG_PAUSE_DELAY_MS = 5 * 60 * 1000;

/**
 * 起動時に 1 度: タブの非表示が BG_PAUSE_DELAY_MS 続いたら全リレーを閉じ（購読の定義は残す）、
 * 表示に戻ったらすぐ張り直して（since 差分）未送信を再送する（ネイティブ onBackground / onForeground）。
 * NIP-46 の中継リレーも同じく止めて、再送の前に張り直す（再送の署名が NIP-46 のことがある）。
 * それより早く戻れば何もしない。戻り値で監視をやめる（一時停止中なら再開する）
 */
export function startBackgroundPause(): () => void {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const cancel = () => {
    clearTimeout(timer);
    timer = undefined;
  };
  const onVisibility = () => {
    if (document.visibilityState === "hidden") {
      if (timer === undefined) {
        timer = setTimeout(() => {
          timer = undefined;
          pauseRelays();
          pauseNip46();
        }, BG_PAUSE_DELAY_MS);
      }
      return;
    }
    cancel();
    const resumed = resumeRelays();
    resumeNip46();
    if (resumed) retryUnsent();
  };
  document.addEventListener("visibilitychange", onVisibility);
  // 裏で開かれたタブも数え始める
  if (document.visibilityState === "hidden") onVisibility();
  return () => {
    document.removeEventListener("visibilitychange", onVisibility);
    cancel();
    resumeRelays();
    resumeNip46();
  };
}
