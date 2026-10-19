import { createPortal } from "react-dom";
import { useT } from "../i18n";
import { APP_VERSION } from "../lib/appVersion";

/**
 * /about（#647。常に LP。ログイン中でも）。本文は静的 HTML（#lp）が既に描いている。
 * 表示・非表示は lpVisibility.ts がパスを見て切り替えるので、React は本文を描かない。
 * [#642 S17] アプリバージョン（ビルド時の値。取れないビルドでは出さない）だけを LP のフッターへ足す。
 */
export function AboutRoute() {
  const t = useT();
  const footer = document.querySelector("#lp footer .shell");
  if (APP_VERSION === null || !footer) return null;
  return createPortal(<span className="version">{t("web_about_version", APP_VERSION)}</span>, footer);
}
