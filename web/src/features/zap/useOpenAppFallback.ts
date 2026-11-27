import { useCallback, useEffect, useLayoutEffect, useRef } from "react";

/** アプリへのリンクを押してから、開けなかったとみなすまで（ms） */
export const OPEN_APP_TIMEOUT_MS = 1500;

/**
 * [#1012] アプリへのリンク（`lightning:` 等）を押したあと、アプリが開いたかを見る。
 * 返す start() をリンクの onClick で呼ぶ（既定の遷移は止めない）。OPEN_APP_TIMEOUT_MS の間に
 * ページが裏に回れば（visibilitychange で hidden・window の blur・pagehide）開けたとみなして何もしない。
 * 来なければ開けなかったとみなし onFail を呼ぶ。もう一度 start() したら前の判定は捨てる。
 * アンマウントでタイマーとリスナーを片付ける。
 */
export function useOpenAppFallback(onFail: () => void): () => void {
  const latestOnFail = useRef(onFail);
  useLayoutEffect(() => {
    latestOnFail.current = onFail;
  });
  const stop = useRef<(() => void) | null>(null);

  const cancel = useCallback(() => {
    stop.current?.();
    stop.current = null;
  }, []);

  useEffect(() => cancel, [cancel]);

  return useCallback(() => {
    cancel();
    const onVisibility = () => {
      if (document.visibilityState === "hidden") cancel();
    };
    const timer = window.setTimeout(() => {
      cancel();
      latestOnFail.current();
    }, OPEN_APP_TIMEOUT_MS);
    document.addEventListener("visibilitychange", onVisibility);
    window.addEventListener("blur", cancel);
    window.addEventListener("pagehide", cancel);
    stop.current = () => {
      window.clearTimeout(timer);
      document.removeEventListener("visibilitychange", onVisibility);
      window.removeEventListener("blur", cancel);
      window.removeEventListener("pagehide", cancel);
    };
  }, [cancel]);
}
