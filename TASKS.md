# 開発タスク / ロードマップ

設計は [whiteboard.md](./whiteboard.md)・[UI設計方針](./designs/ui-design-principles.md)。
現状: UI スキャフォールド（全画面・2ペイン・Deck）＋ Signer/LocalSigner 実装済み。**すべて SampleData（仮データ）**。
方針: まず**1本の縦切り（実リレー→DB→画面）**を通し、その後に横展開する。

凡例: ⬜ 未着手 / 🟡 進行中 / ✅ 完了 ・ 優先度 P0(最優先)〜P2

---

## M1. 縦切り: 実リレーの Following が画面に出る（最優先）
「実際に Nostr につながる」最初の到達点。これが出れば以降は載せるだけ。

- ✅ P0 SQLDelight ドライバ生成（Android/iOS）＋ DB 初期化・DI
- ✅ P0 Ktor WebSocket で **1リレー接続**（接続/購読/受信の最小往復）
- ✅ P0 `REQ`/`EVENT`/`EOSE`/`CLOSE` の最小プロトコル（NIP-01）
- ✅ P0 受信 kind:1 を **id/sig 検証**して `event` テーブルへ保存
- ✅ P0 Repository（cache-first 読み）：FeedColumn を SampleData→DB 読みに差し替え
- ✅ P1 kind:0 を解決して著者名/アバター表示（→ M3）
- ✅ P2 相対時刻を created_at から算出

**完了条件**: 既定リレーに接続し、フォロー中フィードが実イベントで描画される。
→ ✅ **達成**（Pixel 10 Pro Fold 実機で relay.damus.io/nos.lol の実投稿を確認）。

---

## M2. リレー基盤の本実装
- 🟡 P0 リレープール（複数接続・指数バックオフ＋ジッターは実装。`relay_status` 永続は未）
- ✅ P0 **カラム=REQ ライフサイクル**：表示時に subId=columnId で購読、dispose で CLOSE
- ✅ P0 filter 別 DB クエリ（hashtag=event_tag join / authors / search / global）+ タグ索引
- ⬜ P1 `since` 差分取得（再接続時の取りこぼし最小化）
- ⬜ P1 スキーマ・マイグレーション（現状は変更時にアンインストールが必要）
- ⬜ P2 Negentropy（NIP-77）対応リレーで集合差分同期
- → ✅ **データ層確認**: event_tag 1708件、feedByHashtag('nostr') が実投稿6件を返却

## M3. プロフィール解決（kind:0）— 体感に最も効く
- ✅ P0 受信著者をバッチ（`{kinds:[0],authors:[...]}`）+ 400ms デバウンスで解決
- ✅ P0 notes Flow を event×profile の combine にし、解決後に名前/アバターが自動反映
- 🟡 P1 アウトボックス（NIP-65 / kind:10002）：リレーリスト取得・`relay`テーブル永続・設定UIは✅。配信のwrite限定最適化は未
- ⬜ P1 二層キャッシュ（可視=メモリLRU / 全件=ディスク）+ TTL（現状は DB + combine）
- ✅ P1 `insertProfileIfAbsent` / `updateProfileIfNewer` で dedup
- → ✅ **実機確認**: 151 プロフィール解決・実名/nip05/アバター画像を描画、未解決は hex+モノクロにフォールバック

## M4. 投稿・署名・配信
- ✅ P0 `UnsignedEvent` 作成 → `Signer.sign` → publish（投稿）
- 🟡 P0 **publish_queue**：DB 楽観反映 + enqueue は✅。オンライン復帰フラッシュ/NIP-20 OK 反映は未
- 🟡 P1 リアクション/リポスト表示（kind:7 / kind:6）→ **M8 で実装中**
- ⬜ P1 返信投稿（NIP-10 e/p タグ付き kind:1）
- ⬜ P2 zap（NIP-57 / kind:9734・9735、LNURL）

## M5. 鍵・ログイン
- ✅ P0 secure な `KeyVault`：Android Keystore（iOS Keychain は未）
- ✅ P0 ログイン UI（nsec インポート / 新規生成、npub 表示、パスワード欄+自動入力、鍵切替ガード）
- ⬜ P1 NIP-46（リモート bunker, iOS可）
- ⬜ P2 Nosskey（パスキー WebAuthn PRF・要ドメイン+assetlinks）
- ⬜ P2 iOS Keychain の `KeyVault` actual

## M8. タイムライン表示の拡充（並列 worktree で進行中）
フォロー中タイムラインの「読み物」としての質を上げる。
- 🟡 P1 **リポスト/QuoteRepost 表示**（NIP-18）：kind:6 リポストは元ノートを「🔁 ○○がリポスト」付きで、
  kind:1+`q`タグ/kind:1引用は引用カード付きで表示。`event`テーブルに格納しタグ索引で解決（新テーブル無し）
- 🟡 P1 **長文の折りたたみ**：一定行数/文字数を超えたら省略し「もっと見る」で展開（CollapsibleText）
- 🟡 P1 **絵文字リアクション表示**（NIP-25 kind:7 / NIP-30 カスタム絵文字）：対象ノートごとに
  絵文字別カウント集計、カスタム絵文字(`emoji`タグの shortcode→URL)を画像表示。`event`格納+集計クエリ

## M6. NIP 機能拡充
- ⬜ P1 NIP-10 スレッド：e/p タグから実ツリー構築（現状は仮）
- ⬜ P1 NIP-28 パブリックチャット：kind:40/41/42、kind:43/44 モデレーション
- ⬜ P1 画像：Coil3 + imeta(NIP-92/93) blurhash プレースホルダ
- ⬜ P1 NIP-17 DM：gift wrap + **NIP-44 暗号**（Signer.nip44Encrypt/Decrypt 実装）
- ⬜ P2 NIP-42（リレー AUTH、自前リレー保護）

## M7. プラットフォーム仕上げ
- ⬜ P1 `NetworkPolicy` actual 実装（ConnectivityManager / NWPathMonitor）→ ティア別更新
- ⬜ P1 背景同期：WorkManager（Android）/ BGTaskScheduler（iOS, WiFi＋充電時）
- ⬜ P1 ヒンジのガター（androidx.window FoldingFeature）
- ⬜ P2 カラムのドラッグ並べ替え（現状 `DeckState.move` のみ）
- ⬜ P2 iOS ビルド（iosApp の Xcode プロジェクト生成・要 Xcode）
- ⬜ P2 アクセシビリティ（最小タップ48dp・フォントスケール・片手）

---

## 横断的に常に守る（設計方針）
- UI はローカル DB だけを読む（SSOT / stale-while-revalidate）
- カラム/スレッド/ルームは ColumnSpec に統一、pin で永続化
- 署名は Signer 抽象越し（アプリ本体は方式を知らない）
- モノクロ基調・グラデーション禁止（[UI設計方針](./designs/ui-design-principles.md)）
- 変更は実機ビルドで検証してからコミット
