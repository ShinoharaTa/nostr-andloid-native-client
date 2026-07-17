# Play ストア掲載情報（下書き / draft — あとで書き換え可）

> オープンテスト用の最小構成。文言・素材は後から差し替え可能。

## アプリ名 / Title（最大30文字）
Nostrism

## 短い説明 / Short description（最大80文字）
デッキ型の Nostr クライアント。複数カラムでタイムラインを一望できます。

## 詳しい説明 / Full description（最大4000文字）

Nostrism は、分散型プロトコル Nostr の Android クライアント（MVP / ベータ）です。
TweetDeck のような複数カラムの「デッキ」表示で、フォロー中・ハッシュタグ・グローバル・通知・DM・検索などを横に並べて一望できます。折りたたみ端末・タブレットの大画面に最適化しています。

主な機能:
・デッキUI — カラムの追加 / 並べ替え / 幅調整（S/M/L）
・タイムライン — フォロー中 / グローバル / ハッシュタグ / プロフィール
・通知・DM（NIP-17 で暗号化）・未読バッジ
・リアクション（♡/☆ を選択可）・リポスト・引用・Zap（NIP-57）
・ワード検索（NIP-50）・ミュート（ワード / ユーザー）・通報（NIP-56）
・画像 / OGP / YouTube の埋め込み表示
・モノクロで統一した見やすいデザイン
・ライトテーマ / 表示サイズ・文字サイズ調整（アクセシビリティ）
・日本語 / 英語 UI

本アプリは開発者が運営するサーバーを持たず、利用者のデータを収集しません（詳細はプライバシーポリシー参照）。

※ 現在はオープンテスト（ベータ）版です。不具合やご要望のフィードバックを歓迎します。

---
## English listing (en-US)

### Title (max 30)
Nostrism

### Short description (max 80)
Deck-style Nostr client. See all your timelines at once in multiple columns.

### Full description (max 4000)
Nostrism is a deck-style client for the decentralized Nostr protocol (beta).

Like TweetDeck, it shows Following, hashtags, global, notifications, DMs, and search side by side in a multi-column deck — optimized for foldables and tablets, and just as comfortable on a phone.

Features:
- Deck UI — add / reorder / resize columns (S/M/L)
- Timelines — Following / Global / hashtags / profiles / keyword & tag feeds
- Notifications, encrypted DMs (NIP-17), unread badges
- Reactions (choose ♡/☆), reposts, quotes, Zaps (NIP-57)
- Word search (NIP-50), mute (words / users), reporting (NIP-56)
- Image / OGP / YouTube embeds, inline video
- Light & dark themes, display size & font size settings
- Japanese / English UI
- Clean monochrome design

Nostrism runs no servers of its own and collects no user data (see the privacy policy for details).

Note: this is an open beta. Bug reports and feedback are welcome.

## その他
- パッケージ名 / applicationId: `net.shino3.nostrism`
- カテゴリ: ソーシャル
- 無料アプリ / 広告なし / アプリ内購入なし
- プライバシーポリシー: https://nostrism.shino3.net/privacy-policy.html
- 児童の安全に関する基準（CSAE）: https://nostrism.shino3.net/child-safety.html （宣言は `child-safety-declaration.md` 参照）
- アプリアクセス（レビュー用メモ）: 利用には Nostr の鍵（nsec/npub）が必要です。レビュアーは Nostr ユーザーのため各自の鍵でログインできます。

## 素材（このフォルダ）
- アイコン 512×512: `icon-512.png`
- フィーチャーグラフィック 1024×500: `feature-1024x500.png`
- スマホ用スクショ: `screenshot-phone-1.png` … `-3.png`（1080×2160）
- タブレット用スクショ: `screenshot-tablet-1.png`（2076×2152・デッキ表示）
