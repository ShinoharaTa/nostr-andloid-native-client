# 開発タスク / ロードマップ

設計は [whiteboard.md](./whiteboard.md)・[UI設計方針](./designs/ui-design-principles.md)。
現状: **実データで稼働**（リレー/DB/署名/投稿/画像/通知/NIP-28 パブリックチャット/デザインシステムまで完了）。
次にやることは末尾の **「バックログ」** に集約。

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
- ✅ P1 スキーマ・マイグレーション（1〜7.sqm / verifyMigrations。SELECT追加のみは .sqm 不要）
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
- ✅ P1 リアクション/リポスト表示（kind:7 / kind:6）→ M8 で完了
- ✅ P1 返信投稿（NIP-10 e/p タグ付き kind:1・返信元カード表示つき）
- ⬜ P2 zap（NIP-57 / kind:9734・9735、LNURL）

## M5. 鍵・ログイン
- ✅ P0 secure な `KeyVault`：Android Keystore（iOS Keychain は未）
- ✅ P0 ログイン UI（nsec インポート / 新規生成、npub 表示、パスワード欄+自動入力、鍵切替ガード）
- ⬜ P1 NIP-46（リモート bunker, iOS可）
- ⬜ P2 Nosskey（パスキー WebAuthn PRF・要ドメイン+assetlinks）
- ⬜ P2 iOS Keychain の `KeyVault` actual

## M8. タイムライン表示の拡充（並列 worktree で実装・統合済み）
フォロー中タイムラインの「読み物」としての質を上げる。
- ✅ P1 **リポスト/QuoteRepost 表示**（NIP-18）：kind:6/16 は元ノートを「🔁 ○○がリポスト」付き、
  `q`タグ引用は引用カードで表示。`event`テーブル格納+タグ索引で解決（新テーブル無し）
- ✅ P1 **長文の折りたたみ**：8行超で省略し「もっと見る/閉じる」で展開（CollapsibleText）
- ✅ P1 **絵文字リアクション表示**（NIP-25 kind:7 / NIP-30 カスタム絵文字）：絵文字別カウント集計、
  カスタム絵文字(`emoji`タグ shortcode→URL)を画像表示。`event`格納+集計クエリ
- ✅ P1 **投稿アクション**：💬返信(NIP-10)/🔁リポスト(NIP-18)/♡リアクション(NIP-25 "+") をノートから送信
- ✅ P1 **#ハッシュタグ投稿**（NIP-24 't'タグ）：本文の#を't'化、過去タグを記憶し前方一致レコメンド+最近5件ワンタップ
- ✅ P2 アクションの状態反映：自分が♡/リポスト済みをハイライト、♡はトグル(再タップでNIP-09削除)
- ✅ P2 リポストは確認ダイアログ（誤タップ防止）
- ✅ P1 **画像表示**：本文URL除去 / 1枚=単一・2-9枚=グリッド・10枚以上=カルーセル / タップでLightbox全画面(ズーム可)
- ✅ P1 **折りたたみUI改善**：シェブロン付き角丸ピルで「操作」と分かる見た目に
- ✅ P2 **反応数の集計**：ローカルに見えた範囲のリプライ/リポスト数・♡数を表示（NIP に総数概念は無くベストエフォート）
- ✅ P2 引用リポスト送信（q タグ付き kind:1・引用カード表示）
- ✅ P2 ♡/リアクション取り消し(kind:5)前の確認ダイアログ
- ⬜ P2 リポストの取り消し（kind:6 を NIP-09 削除）
- ⬜ P2 zap 数の集計（kind:9735 / NIP-57）

## M6. NIP 機能拡充
- ✅ P1 NIP-10 スレッド：e/p タグから実ツリー構築（ThreadColumn・深さインデント）
- ✅ P1 NIP-28 パブリックチャット：kind:42 送受信・NIP-10返信・リアクション集約(Slack風)・
  ピン留め・通知連携。チャンネル一覧は thread.nchan.vip 由来（kind:40/41 は購読しない）。
  kind:43/44 モデレーションは ⬜
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

## M9. デザインシステム / 脱AI化（feat/design-system → main 統合済み）
- ✅ 型スケール Option C（7段: Display20/Title15/Body14/Sub13/Caption12/Label11/Micro10）
- ✅ タップ領域 T3（実40dp箱）+ TouchTargetXs(32dp: インライン補助操作) + 角丸/余白の完全トークンスナップ
- ✅ 文字ロール DeckWeight（Name=Bold / Strong / Link / Body）
- ✅ 脱AI化 施策1(1pxボーダー撤廃→面と隙間) / 施策2(ウェイト対比) / 施策4(近接) — **施策3(アイコン)は見送り決定**
- ✅ 左レール統一（RailSlot 48dp / 3ブロック構成 / ピン一覧のみスクロール）
- ✅ 戻る/閉じる共通化（HeaderBackButton / HeaderIconButton）
- ✅ Deck 共通コントロール（DeckButton/Ghost/TextButton/TextField/ConfirmDialog）で M3 標準を置換
- ✅ 破壊的/公開操作の確認ダイアログ（リレー削除・保存公開・メディア削除・キャッシュ・鍵切替・投稿破棄・リアクション取消）
- ✅ 投稿モーダル刷新（枠なし入力・オーバーレイタップで閉じる・破棄確認）/ メディアサーバー単一選択ラジオ

---

## 実装計画（2026-07 策定・上から順に着手）

### M10. 読み物導線の完成（推奨・最初）
- ⬜ P1 ContentText タップ動作: メンション→プロフィール / #タグ→ハッシュタグカラム / nevent→スレッド
- ⬜ P1 検索: レールの検索を実装（NIP-50 search REQ + columnFeed(search) 流用、結果のカラム化）
- ⬜ P2 リポスト取り消し（kind:5・確認ダイアログは既存パターン）
- ⬜ P2 since 差分取得（再接続時の取りこぼし削減）

### M11. 配信の信頼性（publish_queue 完成）
- ⬜ P1 NIP-20 OK 反映（送信済み/失敗判定・リトライ）
- ⬜ P1 オフライン→復帰フラッシュ / NetworkPolicy actual（ConnectivityManager）

### M12. DM 縦切り（大物・1本集中）
- ⬜ P1 NIP-44 v2（ECDH+HKDF+ChaCha20+HMAC・公式テストベクタでユニットテスト）
- ⬜ P1 NIP-17 gift wrap 送受信 → DmScreen 実データ化

### M13. Zap（NIP-57）
- ⬜ P2 LNURL pay → kind:9734/9735・zap数集計・⚡UI接続
- 方針（仮）: まず外部ウォレット起動（lightning: URI）で出荷 → NWC(NIP-47) を後付け

### 隙間タスク（マイルストーンの合間）
- 設定の未実装セクション / デザイン残（バックログD） / カラム削除の確認ダイアログ検討

---

## バックログ（次にやること・2026-07 整理）

### A. 機能の未実装（画面から見えるもの）
- ⬜ P1 **検索**（SearchScreen はプレースホルダ。レールにボタンだけある）
- ⬜ P1 **DM**（画面は SampleData。NIP-17 gift wrap + NIP-44 が前提 → B-1）
- ⬜ P1 **カラム並べ替え**（ヘッダの grip が飾り。`DeckState.move` へのジェスチャ接続）
- ⬜ P2 設定の未実装セクション（アカウント / 表示 / このアプリについて）
- ⬜ P2 Zap（NIP-57 自動 Zap・zap 数集計。現状 lud16 表示のみ）
- ⬜ P2 リポストの取り消し（kind:6 の NIP-09 削除）

### B. 基盤・プロトコル
- ⬜ P1 **NIP-44 実装**（LocalSigner: ECDH+HKDF+ChaCha20+HMAC）→ DM の前提
- ⬜ P1 publish_queue の完成（オンライン復帰フラッシュ / NIP-20 OK 反映）
- ⬜ P1 ContentText のタップ動作（メンション→プロフィール / #タグ→カラム）
- ⬜ P2 Signer 拡張（NIP-46 bunker / Nosskey / iOS Keychain / 複数 npub 切替）
- ⬜ P2 `since` 差分取得 / NIP-77 Negentropy / NIP-42 AUTH
- ⬜ P2 imeta(NIP-92/93) blurhash プレースホルダ

### C. プラットフォーム仕上げ（M7 再掲）
- ⬜ P1 NetworkPolicy actual（ConnectivityManager）/ 背景同期（WorkManager）
- ⬜ P1 ヒンジガター（androidx.window FoldingFeature。現状は固定22dpトークン）
- ⬜ P2 iOS ビルド（Xcode プロジェクト生成）

### D. デザインシステムの残タスク
- ⬜ P2 アバターサイズの3段集約（22/28/30/32/38/60/72 混在 → Sm/Md/Lg トークン）
- ⬜ P2 グリフサイズの Icon* トークンスナップ残り（11/13/15/26dp 等）
- ⬜ P2 lineHeight のトークン化残り（18/19/20/21sp 散在）
- ⬜ P2 RelayGreen/Amber を theme/Color.kt へ / ConnectionIndicator の .border() 撤去（施策1残）

### E. UX 検討
- ⬜ P2 Compose の下書き保持（現状は破棄確認のみ）
- ⬜ P2 通知画面本体の kind 別フィルタ UI

---

## 横断的に常に守る（設計方針）
- UI はローカル DB だけを読む（SSOT / stale-while-revalidate）
- カラム/スレッド/ルームは ColumnSpec に統一、pin で永続化
- 署名は Signer 抽象越し（アプリ本体は方式を知らない）
- モノクロ基調・グラデーション禁止（[UI設計方針](./designs/ui-design-principles.md)）
- 変更はエミュレータ（実機同寸 2076×2152/390dpi）で検証してからコミット。実機インストールは明示指示時のみ
