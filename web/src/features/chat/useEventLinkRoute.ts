import type { EventPointer } from "applesauce-core/helpers/pointers";
import { useEffect, useState } from "react";
import {
  appEventLinkDeps,
  type EventLinkDeps,
  type EventLinkRoute,
  eventLinkRouteNow,
  resolveEventLinkRoute,
} from "./eventLink";

/**
 * [#798] 本文リンク（/e/:ref の note / nevent）の開き先。手元で決まればその場で返し、決まらなければ取得を待つ
 * （待つ間は null）。リンクごとに 1 度だけ決める（開いた後に一覧やストアが変わっても切り替えない）。
 */
export function useEventLinkRoute(
  link: EventPointer,
  deps: EventLinkDeps = appEventLinkDeps,
): EventLinkRoute | null {
  const key = `${link.id}\n${link.kind ?? ""}`;
  const [state, setState] = useState(() => ({ key, route: eventLinkRouteNow(link, deps) }));
  let current = state;
  if (state.key !== key) {
    current = { key, route: eventLinkRouteNow(link, deps) };
    setState(current);
  }
  const pending = current.route === null;

  // biome-ignore lint/correctness/useExhaustiveDependencies: link・deps はリンク（key）ごとに 1 度だけ使う
  useEffect(() => {
    if (!pending) return;
    let alive = true;
    void resolveEventLinkRoute(link, deps).then((route) => {
      if (alive) setState((s) => (s.key === key ? { key, route } : s));
    });
    return () => {
      alive = false;
    };
  }, [key, pending]);

  return current.route;
}
