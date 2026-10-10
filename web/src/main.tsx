import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./app/App";
import { unregisterLegacyServiceWorker } from "./app/legacyServiceWorker";
import { startPersistence } from "./db";
import { startDm } from "./features/dm/dmService";
import { startOwnLists } from "./features/lists/ownLists";
import { startMuteList } from "./features/mute/muteSync";
import { initTheme } from "./features/theme/themePrefs";
import { initLocale } from "./i18n/locale";
import { startBackgroundPause } from "./nostr/backgroundPause";
import { startConnStats } from "./nostr/connStats";
import { startOwnRelayList } from "./nostr/outbox";
import { startPublishQueue } from "./nostr/publish";
import { startRelayAuth } from "./nostr/relayAuth";
import { useSession } from "./signer/session";
import { applyOsAttribute } from "./ui/platform";
import "./styles/global.css";

// 保存済みのテーマ・文字サイズ・太字を React の描画前に同期的に当てる（初回描画のちらつきを避ける。
// CSP で inline script は置けないので、ここが一番早い）
initTheme();
// <html lang> と data-dialect を当てる（言語の解決は描画前に済ませる）
initLocale();
// iOS 判定を <html data-os="ios"> に反映する（#598。BottomNav.module.css の下端インセット分岐で使う）
applyOsAttribute();
// 旧 scope（/app/）の Service Worker が残っていれば解除する（#647）
void unregisterLegacyServiceWorker();

const root = document.getElementById("root");
if (!root) throw new Error("#root not found");

// 保存済みセッションの復元は起動時に 1 度だけ（StrictMode の二重実行で拡張を 2 回呼ばない）
void useSession.getState().restore();
// DM は保存済みの分を読むため DB を開いた後に始める
void startPersistence().then(() => {
  void startPublishQueue();
  startDm();
});
// ログイン中は自分の kind:10002 で読み書きリレーを決める
startOwnRelayList();
// ログイン中は自分の kind:10000（ミュート）を購読して表示から除く
startMuteList();
// ログイン中は自分の kind:10003（ブックマーク）・kind:10001（固定投稿）を購読する
startOwnLists();
// リレーの AUTH（NIP-42）に設定のポリシーで応答する
startRelayAuth();
// タブの非表示が 5 分続いたらリレーを一時停止し、表示に戻ったら張り直す
startBackgroundPause();
// リレーごとの受信数・受信量を数える（設定 > データ・キャッシュ の「接続と通信量」）
startConnStats();

createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
