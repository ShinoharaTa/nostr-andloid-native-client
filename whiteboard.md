# Nostr Deck クライアント — 設計ホワイトボード

モバイル向けネイティブ Nostr クライアント（Deck 型 UI）のフレームワーク選定と設計メモ。
作成日: 2026-06-25

関連: [タスク/ロードマップ](./TASKS.md) / [UI 設計方針](./designs/ui-design-principles.md) / [モック](./designs/index.html) / [デザイントークン](./designs/tokens.css)

---

## ゴール / 要件

- **Deck 型 UI**：TweetDeck/Mastodon Deck のように複数フィード（カラム）を並べる。
  - カラム = 1つの Nostr `REQ` フィルタ（フォロー中 / ハッシュタグ / 特定リレー / DM / 通知 / 任意の npub …）。
- **フォルダブル最適化（Android 第一）**：Galaxy Z Fold、将来の iPhone Fold など Wide 型。
  - **折りたたみ時** → シングルカラム（普通の SNS のスワイプ操作）。
  - **展開時** → 複数カラムを横並び。「開いたときこそ本質」。
- **iOS / iPad も対応**：iPad の Stage Manager / Split View の可変幅でも有効に。
- **ネイティブの軽さ**：JS ブリッジ・WebView を挟まない（React Native / Tauri は不採用）。
- **オフラインファースト**：不安定な回線下でも快適。kind:0 / 画像のキャッシュが重さの本質。
- **回線種別に応じた制御**：WiFi(従量制でない)では積極更新、セルラーでは節約。

---

## フレームワーク選定 → 結論: Compose Multiplatform (CMP)

### 用語
- **Kotlin**：言語。
- **KMP (Kotlin Multiplatform)**：ロジック（DB・通信・計算）を複数 OS で共有。UI は含まない。
- **Jetpack Compose**：Android の宣言的 UI ツールキット。
- **CMP (Compose Multiplatform)**：その Compose を iOS / デスクトップ等でも動かし、UI も共有。KMP の上に乗る。
- 関係：**KMP = ロジック共有、その上に CMP = UI も共有**。
- iOS では Kotlin/Native で AOT コンパイル、UI は Skia 直接描画 → JS ブリッジ/WebView なし＝軽い。

### 候補比較（要点）

| 要件 | ① CMP(UI共有) | ② KMP+ネイティブUI | ③ 完全別実装 | ④ Flutter |
|---|---|---|---|---|
| Deck カスタムUI | ◎ 1本 | △ 2回 | ✕ 2回 | ◎ 1本 |
| フォルダブル/ヒンジ | ◎ androidx.window | ◎ | ◎ | ○ displayFeatures |
| iPad 可変幅 | ◎ | ○ | ○ | ◎ |
| ネイティブの軽さ | ◎ Kotlin/Native | ◎ | ◎ | ◎ Dart AOT |
| Nostr ライブラリ資産 | ◎ Quartz/Amethyst | ◎ | ◎ | △ Dart は手薄 |
| Schnorr/secp256k1 成熟度 | ◎ secp256k1-kmp | ◎ | ◎ | △ FFI 自前 |
| 共有DB(SQLDelight) | ◎ | ◎ | ✕ | ○ drift |
| 回線判定/背景同期 | ◎ expect/actual | ◎ | ◎ | ○ plugin |
| 実装コスト総量 | **小** | 中 | 大 | 小 |

### CMP を選ぶ決め手
1. **Deck は完全カスタム UI** → CMP iOS の弱点（UIKit ネイティブ部品でなく Skia 描画）が無効化される。UI 二重化(②③)の対価が見合わない。
2. **設計してきた層が丸ごと commonMain に乗る**（SQLDelight / Ktor / kind:0 バッチ / Coil3 / NetworkPolicy / 配信キュー）。
3. **secp256k1-kmp と Quartz(Amethyst) で Nostr の地雷をショートカット**。Flutter との最大差。

### 退路
- ロジックを KMP モジュールに綺麗に分離しておけば、いざ iOS を SwiftUI で作り込みたくなっても（①→②）ロジック層は無傷で残せる。リスク低。

### 次点の条件
- **Flutter**：チームが Dart 堪能 or 完全単一コードベース最優先なら可。ただし Nostr/暗号の成熟度で劣り自前検証が要る。
- **KMP + SwiftUI(②)**：iOS をピクセルパーフェクトに作り込みたい場合のみ。Deck は独自 UI なので旨味小。

---

## 確定スタック

```
言語/UI       : Kotlin + Compose Multiplatform
ターゲット     : Android / iOS（iPad含む）, 幅駆動アダプティブ
DB(SSOT)      : SQLDelight
リレー通信     : Ktor Client（WebSocket）
署名/検証     : secp256k1-kmp（Schnorr, NIP-01）
画像          : Coil 3（永続ディスク＋imeta/blurhash）
設定/鍵       : multiplatform-settings / DataStore ＋ expect/actual で Keystore・Keychain
回線判定      : expect/actual（ConnectivityManager / NWPathMonitor）
背景同期      : WorkManager（Android）/ BGTaskScheduler（iOS）
フォルダブル   : androidx.window（FoldingFeature）＋幅分岐は共通
参考実装      : Amethyst/Quartz（プロトコル）, Coracle・Nostrudel（Deck UX）
```

### モジュール構成
```
shared/
├─ commonMain/   ← Deck UI(Compose), Nostr ロジック, REQ/購読管理, 状態
├─ androidMain/  ← FoldingFeature 検出, Android Keystore
└─ iosMain/      ← Split/Stage Manager 幅, iOS Keychain
androidApp/  iosApp/
```
expect/actual で逃がす差分は3点：**折り目検出 / 鍵保管 / 回線判定**。

---

## Deck レイアウト設計

方針：**カラム幅は固定(320〜360dp)、画面幅で見える枚数が変わる。はみ出しは横スクロール。**
（可変フィットより軽い＝入る分だけ描画、残りはオフスクリーン。iPad/Fold の可変幅にそのまま効く）

- **折りたたみ時(Compact)** → `HorizontalPager`、1カラム=1ページ、スワイプ切替。
- **展開時(Medium/Expanded)** → `Row(horizontalScroll)` に固定幅カラムを並べる。
- 分岐は **`WindowSizeClass` の widthSizeClass** で駆動（ヒンジ検出ではなく "幅" で分岐 ← iOS にヒンジ API が無くても破綻しない）。

```kotlin
if (windowState.isCompact) {
    HorizontalPager(state = ...) { page -> FeedColumn(columns[page]) }
} else {
    val scroll = rememberScrollState()
    Row(Modifier.horizontalScroll(scroll)) {
        columns.forEach { col -> FeedColumn(col, Modifier.width(340.dp)) }
    }
}
```

### 詰まりどころ
1. **折り↔開きはコンフィグ変更 → Activity 再生成**。カラム状態(スクロール位置・読込済イベント)は **ViewModel に hoist** して維持。
2. **ヒンジ(オクルージョン)**：`WindowInfoTracker` の `FoldingFeature` を Flow で受け、境界にガター挿入。カラムが折り目で割れないように。
3. **テーブルトップ姿勢(HALF_OPENED + 水平ヒンジ)**：上=アクティブカラム / 下=投稿ボックス等。展開の本質を演出できる場所。
4. **複数カラム=複数購読の並行**：表示中のみ購読 active、画面外はバッファのみ。

### 操作系
- カラム追加/削除/並べ替え(ドラッグ) = Nostr フィルタ編集 UI。
- カラム設定 = `{kind, authors, #t, relays...}` をそのまま永続化(SQLDelight)。

---

## ナビゲーション & 画面構成（Damus との差別化）

差別化の主役は「Deck + 左レール」。Damus は単一カラムのスタック型ナビなので、レール＋カラムジャンプ体験自体が別物。

### アダプティブ・ナビゲーション（Material 3 指針）
幅でナビの器を入れ替える。「展開すると下タブが消える」のではなく**下タブが左レールに昇格**する。

| 幅 | グローバルナビ |
|---|---|
| Compact(折りたたみ) | 下部 NavigationBar |
| Medium/Expanded(展開・iPad) | 左 NavigationRail（アイコン） |

### 左 NavigationRail（展開時の常設）
1. 上：アカウントアバター/ロゴ（アカウント切替）
2. グローバル宛先：Home / 検索 / 通知(バッジ) / DM(バッジ)
3. 区切り
4. **カラムショートカット = Deck の目次**。各カラムをアイコン表示、タップで `animateScrollTo` でそのカラムへ横ジャンプ（「一瞬で切替」）。ドラッグで並べ替え
5. 下：＋カラム追加 / 設定

住み分け：**コンテンツ系（Following・ハッシュタグ・リスト）= カラム**、**アプリ機能（検索・通知・DM・設定）= レール宛先**。

### スレッド表示 = スレッドカラム（Deck ネイティブ）
- ノートをタップ → **右隣にスレッドカラムがスライドイン**。root→祖先→対象ノート(ハイライト)→返信ツリーを1カラムに。閉じれば Deck に戻る。
- Compact 時のみ通常の全画面プッシュにフォールバック。
- **使う NIP は NIP-10**（`e`/`p` タグの root/reply マーカーで返信ツリー構成）、kind:1 以外のコメントは NIP-22。
- ※ **NIP-42 はリレー認証(AUTH)でスレッドとは無関係**。AUTH は自前リレー保護用途で別途実装。

### パブリックチャット（NIP-28）
NIP-10 の返信スレッドとは**別物**。チャンネル（公開チャットルーム）。
- **kind:40** チャンネル作成 / **kind:41** メタ更新 → 一覧の素
- **kind:42** チャンネル内メッセージ → ルームの発言（#e でチャンネル参照）
- **kind:43/44** メッセージ非表示・ユーザーミュート → クライアント側モデレーション

2段で目的のチャットへ：**チャンネル一覧カラム → タップでルームカラム**。

### 統合モデル：すべて「カラム」、ピンで永続化
フィード/スレッド/チャンネルルームを区別なく `ColumnSpec` で扱い、**pinned の有無だけ**で永続性とレール表示を制御。

| 状態 | 挙動 | 例 |
|---|---|---|
| transient（pinned=false） | タップで開き ✕ で閉じる。実行時のみ | ノート→スレッド、チャンネル→ルーム |
| pinned（pinned=true） | SQLDelight に永続化 + **左レールにアイコン常駐**・並べ替え可 | Following / #nostr / お気に入りチャンネル / 残したいスレッド |

**ピン留め = 一時カラムを永続セットへ昇格**。これで「好きなスレッド/チャンネルをレール（カラム一覧）に固定」を実現。

### 既存画面への組み込み口
1. 左レールの **list アイコン** → パブリックチャット（チャンネル一覧）カラムを開く
2. チャンネル一覧の各行 **📌** → そのルームをピン留め（レール追加＋永続カラム化）
3. スレッド/ルームのヘッダ **📌トグル** → 見ている一時カラムをその場で固定
4. レールのピン留めアイコン → タップで該当カラムへジャンプ（未読バッジ付き）

永続化テーブル: `pinned_column`（並びの SSOT）、`channel`（kind:40/41 集約キャッシュ）。

### カラムレンダラーは3種（住み分け）
| 種別 | NIP/kind | 並び | 入力欄 |
|---|---|---|---|
| FEED | kind:1 | 逆時系列 | なし(FAB) |
| THREAD | NIP-10 | ツリー | 返信ボックス |
| CHANNEL_LIST | NIP-28 40/41 | 活動順 | なし |
| ROOM | NIP-28 kind:42 | **時系列昇順・最新が下** | **下部常設** |

ルームだけチャット UI（バブル・著者まとめ・最新が下・下部入力）。フィード/スレッドの逆時系列読み物とは別レンダラー。

### Compose 実装 TODO（このセクション分）
- [ ] `AppScaffold`：幅で NavigationBar(compact) ⇆ NavigationRail(expanded) を切替
- [ ] レールのカラムショートカット → Deck の `ScrollState.animateScrollTo` でジャンプ
- [ ] `ThreadColumn`（NIP-10 ツリー構築）＋ ノートタップでカラム挿入/除去
- [ ] `ChannelListColumn`（kind:40/41 集約）→ タップで `ChannelRoomColumn`（kind:42 チャット）
- [ ] kind:43/44 のクライアント側モデレーション適用
- [ ] バッジ（未読通知/DM/チャンネル）

---

## オフラインファースト / キャッシュ設計

### 大原則：UI はネットワークを一切見ない（SSOT）
```
UI(Compose) → 読むのはローカル DB / ディスクキャッシュだけ
                ↑（背景で非同期に充填）
Sync エンジン → リレープール / 購読 / プロフィール解決 / 配信キュー
```
stale-while-revalidate：常にローカルから即描画、ネットワークは裏で DB を埋めるだけ。

### DB スキーマ（SQLDelight）
- `events`(id, pubkey, kind, created_at, content, tags, sig)
- `profiles` = **kind:0 を pubkey ごとに created_at 最大の1件だけ保持（dedup 必須）**
- `relay_status`, `publish_queue`

### kind:0（プロフィール）解決 ← 一番効く
1. **ビューポート駆動 + バッチ + デバウンス**：可視著者の pubkey を集め `{kinds:[0], authors:[...]}` 1本に束ねる。スクロール中は 100〜200ms デバウンス。
2. **キャッシュファースト + 長 TTL**（数時間〜日）。期限切れでも古いのを出して裏で更新。
3. **アウトボックス(NIP-65)**：kind:10002 を見て本人が書いてるリレーへ取りに行く。帯域節約。
4. **二層キャッシュ**：可視は in-memory LRU、全件はディスク。

### 画像（Coil 3）
- 永続ディスク LRU、キーは URL 正規化。
- **imeta(NIP-92/93) の blurhash/dim でプレースホルダ即描画** → CLS 防止でスクロールが滑らか。
- 失敗時は指数バックオフ再試行。メモリ上限はカラム数で割って配分。

### 回線不安定への耐性
1. リレー再接続：指数バックオフ+ジッター、`relay_status` で健全性管理。
2. **配信キュー**：自分の投稿/リアクションは DB に楽観的に書いて即 UI 反映 → `publish_queue` に積み、復帰時フラッシュ（NIP-20 OK で状態更新）。
3. **差分同期**：NIP-77(Negentropy) 対応リレーは集合差分、非対応は `since`(最後の created_at) 限定。← 自前リレーで効かせやすい。
4. オフスクリーンカラムは `CLOSE`、復帰時 `since` から再開。

---

## 回線種別に応じた制御（NetworkPolicy）

ネイティブの強み。**Repository 層だけが参照**し、UI/購読ロジックはティアを知らない。

### 判定 API（expect/actual で1つに）
| | Android | iOS |
|---|---|---|
| 従量制か | `NetworkCapabilities.NET_CAPABILITY_NOT_METERED` | `NWPathMonitor.isExpensive` |
| 節約モード | `RESTRICT_BACKGROUND_STATUS` | `path.isConstrained`(Low Data) |
| 変化購読 | `NetworkCallback` を Flow 化 | `NWPathMonitor` を Flow 化 |

```kotlin
enum class NetworkTier { UNMETERED, METERED, CONSTRAINED, OFFLINE }
```

### ティア別ポリシー
| 動作 | UNMETERED(WiFi) | METERED(セルラー) | CONSTRAINED(低データ) |
|---|---|---|---|
| kind:0 再検証 | 積極リフレッシュ+先読み | TTL 超過分のみ最小限 | キャッシュのみ/明示操作時 |
| アバター画質 | フル解像度先読み | 可視分のみ低解像度 | blurhash 止まり |
| Negentropy 全差分 | やる | since 限定で軽め | やらない |
| 背景同期 | 許可 | 原則しない | しない |

- **kind:0 の TTL をティアで可変**にするのが肝（revalidate 閾値を回線で動かす）。

### バックグラウンド更新を WiFi 限定に
- **Android**：`WorkManager`(`NetworkType.UNMETERED` + `requiresCharging`)。
- **iOS**：`BGTaskScheduler`(`requiresNetworkConnectivity` + `requiresExternalPower`)。
- 例：夜間 WiFi 充電中にフォロー中の kind:0 とアバターを温める → 外ではキャッシュで滑らか。

### 注意
- メータード判定は嘘をつくことがある(テザリング等) → **ユーザートグルを最終権限**に。
- 設定で上書きできる逃げ道（「常に最新」「セルラーでも画像」）を用意。OS 判定は既定値。

---

## 署名・鍵管理（Signer 抽象）

アプリ本体は「どう署名されるか」を知らない。`Signer` インターフェース1枚で全方式を吸収（設計方針 P6）。

### Signer 実装
| 実装 | 鍵の所在 | 対応 | 状態 |
|---|---|---|---|
| `LocalSigner` | nsec を端末保管、secp256k1-kmp で Schnorr 署名 | Android/iOS | ✅ 実装・id検証済 |
| `NosskeySigner` | パスキー(WebAuthn PRF)で暗号化した nsec | Android/iOS | 🟡 スタブ |
| `Nip55Signer` | 外部署名アプリ(Amber)へ Intent 委譲 | Android のみ | 🟡 スタブ |
| `Nip46Signer` | リモート署名(bunker, リレー経由) | Android/iOS | 🟡 スタブ |
| `Nip07Signer` | window.nostr | WebView/CMP Web・Desktop | ⬜ 文脈限定 |

### NIP-07 / Nosskey の扱い
- **NIP-07 はブラウザ拡張 API** なのでネイティブには直接来ない。等価の委譲は **NIP-55（Android/Amber）/ NIP-46（iOS可・本命）**。NIP-07 本体は将来 WebView や CMP の Web/Desktop ターゲット用。
- **Nosskey = パスキー(WebAuthn PRF)で nsec を暗号化/導出**。Android=Credential Manager、iOS=ASAuthorization passkeys の PRF 出力で nsec を復号 → 署名は LocalSigner と同経路。`KeyVault` の一実装として収まる。

### 鍵保管（KeyVault）
- `InMemoryKeyVault`（開発用）→ 本番は `KeystoreKeyVault`(Android Keystore) / `KeychainKeyVault`(iOS Keychain) / `NosskeyKeyVault` に差し替え。
- 配信キュー（オフライン設計）の「署名 → publish」段に `Signer.sign` を挟む。

### 実装メモ
- イベントID/直列化 = `crypto/Nip01`（NIP-01 正準直列化 + SHA-256）。**ユニットテストで検証済**（順序・エスケープ・決定性）。
- Schnorr = secp256k1-kmp（Android は jni-android 同梱）。コンパイル済・実機/エミュでの署名往復は未検証。
- NIP-44（DM 暗号）は未実装（ECDH+HKDF+ChaCha20+HMAC）。`capabilities` で出し分け。

---

## 参考実装
- **Amethyst / Quartz**（Kotlin+Compose, GitHub: vitorpamplona/amethyst）：プロトコル層（購読・検証・署名・NIP・鍵管理）の手本。Deck UI は単一カラム+タブなので UI の手本ではない。
- **Coracle / Nostrudel**（web）：Deck の UX・カラム運用の手本。
- ネイティブモバイルの本格 Deck はまだ手薄＝狙いどころ。

---

## 次の一手（未着手）
- [ ] CMP プロジェクト雛形（shared に Deck 幅分岐スケルトン + SQLDelight スキーマ + NetworkPolicy/Keystore の expect/actual 空実装）
- [ ] カラムのライフサイクル × プロフィール解決 × DB 書込 の状態遷移図
- [ ] kind:0 リフレッシュ判定（TTL × ティア × 可視性）の擬似コード
