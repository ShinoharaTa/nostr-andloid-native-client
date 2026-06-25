# Nostr Deck Client

フォルダブル/大画面に最適化した Deck 型の Nostr ネイティブクライアント。
Kotlin + **Compose Multiplatform**（Android / iOS / iPad）。

設計の根拠と全体像は **[whiteboard.md](./whiteboard.md)** に集約。
デザインモック（HTML）は **[designs/index.html](./designs/index.html)**（ブラウザで開く）。

---

## いまの状態（スキャフォールド段階）

UI の中核（幅駆動の Deck レイアウト）と、データ層の骨格・expect/actual の枠が入った状態。
**まだ仮データで動く UI のみ**で、リレー通信・DB・署名は TODO スタブ。

| 領域 | 状態 |
|---|---|
| Deck レイアウト（折り=Pager / 展開=横スクロール固定幅カラム） | ✅ 実装（仮データ） |
| アダプティブ・ナビ（Compact=BottomBar / Expanded=左Rail） | ✅ AppScaffold |
| カラム統合モデル（pin/transient・open/close/jump） | ✅ DeckState |
| 4レンダラー（FEED/THREAD/CHANNEL_LIST/ROOM） | ✅ 仮データ |
| 左レールのカラムジャンプ（animateScrollTo / Pager） | ✅ |
| デザイントークン同期（tokens.css ↔ theme/Color.kt） | ✅ |
| 幅駆動の分岐（BoxWithConstraints → 600dp ブレークポイント） | ✅ |
| カラムのドラッグ並べ替え | 🟡 `DeckState.move` のみ。ジェスチャ未接続 |
| NetworkPolicy（回線種別 → RefreshPolicy） | 🟡 枠のみ。actual は UNMETERED 固定スタブ |
| SQLDelight スキーマ（event/profile/relay/publish_queue） | 🟡 .sq 定義済み・Repository 未接続 |
| リレー購読（Ktor WebSocket / REQ ライフサイクル） | ⬜ 未着手 |
| kind:0 バッチ解決・アウトボックス(NIP-65) | ⬜ 未着手 |
| Schnorr 署名（secp256k1-kmp） | ⬜ 未着手 |
| 画像（Coil3 + imeta/blurhash） | ⬜ 未着手 |
| iosApp（Xcode プロジェクト） | ⬜ 要生成 |

> ⚠️ この環境では JDK / Android SDK / Xcode / ネットワークが無いため **ビルド検証は未実施**。
> 下記手順でツールチェーンの揃った環境で初回ビルドすること。バージョンは `gradle/libs.versions.toml`。

---

## ビルド手順（要ツールチェーン）

```bash
# 1. Gradle wrapper を生成（未コミットのため）
gradle wrapper --gradle-version 8.11

# 2. Android
./gradlew :composeApp:assembleDebug
#   端末/エミュレータへ: ./gradlew :composeApp:installDebug

# 3. iOS … iosApp/ の Xcode プロジェクトが必要。
#    KMP ウィザード(Android Studio の Kotlin Multiplatform プラグイン)で
#    iosApp を生成し、MainViewControllerKt.MainViewController() をホストする。
```

## ディレクトリ

```
composeApp/src/
├─ commonMain/kotlin/app/nostrdeck/
│   ├─ App.kt                 … ルート。DeckState を remember し AppScaffold へ
│   ├─ state/DeckState.kt     … 統合カラム状態（pin/transient・open/close/jump）
│   ├─ theme/                 … Color/Theme（tokens.css と同期）
│   ├─ model/Models.kt        … Event/Profile/ColumnSpec/Channel/ThreadEntry…
│   ├─ data/                  … NetworkPolicy(expect) / RefreshPolicy / SampleData
│   └─ ui/                    … AppScaffold（幅でナビ分岐）/ DeckRail / DeckScreen(=DeckArea)
│                               FeedColumn / ThreadColumn / ChannelListColumn /
│                               ChannelRoomColumn / ColumnChrome / NoteItem / Avatar
├─ commonMain/sqldelight/…/Nostr.sq   … SSOT DB スキーマ（pinned_column/channel 含む）
├─ androidMain/               … MainActivity / NetworkPolicy.android / Manifest
└─ iosMain/                   … MainViewController / NetworkPolicy.ios
```

## 次の実装ステップ（whiteboard の TODO）
1. SQLDelight ドライバを生成し Repository（cache-first 読み）を実装
2. Ktor WebSocket でリレープール + カラム=REQ のライフサイクル（表示中のみ購読）
3. kind:0 をビューポート駆動でバッチ解決 → profile upsert
4. secp256k1-kmp で署名 → publish_queue → オンライン復帰でフラッシュ
5. NetworkPolicy の actual を実 API（ConnectivityManager / NWPathMonitor）へ
6. ヒンジ（androidx.window）でカラム境界にガター挿入
