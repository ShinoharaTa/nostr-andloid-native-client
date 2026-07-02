# Nostr Deck Client

フォルダブル/大画面に最適化した Deck 型の Nostr ネイティブクライアント。
Kotlin + **Compose Multiplatform**（Android / iOS / iPad）。

設計の根拠と全体像は **[whiteboard.md](./whiteboard.md)** に集約。
デザインモック（HTML）は **[designs/index.html](./designs/index.html)**（ブラウザで開く）。

---

## いまの状態（実データで稼働）

実リレー接続・DB・署名・投稿・画像・通知・パブリックチャットまで動く状態。
タスクの詳細・バックログは **[TASKS.md](./TASKS.md)** を参照。

| 領域 | 状態 |
|---|---|
| Deck レイアウト / アダプティブ・ナビ / カラム統合モデル | ✅ |
| リレープール（Ktor WS・カラム=REQ ライフサイクル・指数バックオフ） | ✅ |
| SQLDelight SSOT（cache-first・マイグレーション 1〜7.sqm） | ✅ |
| kind:0 バッチ解決 / NIP-65 アウトボックス（リレー設定UI含む） | ✅ |
| 投稿（kind:1）・返信(NIP-10)・リポスト/引用(NIP-18)・リアクション(NIP-25/30) | ✅ |
| 画像表示（グリッド/カルーセル/Lightbox）・NIP-96 アップロード（圧縮つき） | ✅ |
| 通知（メンション/リプライ/リアクション/リポスト） | ✅ |
| パブリックチャット（NIP-28 kind:42、一覧は thread.nchan.vip 由来） | ✅ |
| デザインシステム（DeckType/Space/Radius/Dimens/Weight・DeckControls・確認ダイアログ） | ✅ |
| Signer（LOCAL + Android Keystore・鍵切替ガード） | ✅（NIP-46/Nosskey/iOS Keychain は未） |
| 検索 / DM(NIP-17+NIP-44) / Zap(NIP-57) / カラム並べ替え | ⬜ 未実装（TASKS.md バックログ） |
| iosApp（Xcode プロジェクト） | ⬜ 要生成 |

> ✅ Android debug ビルド + エミュレータ（Pixel 10 Pro Fold 同寸 2076×2152 / 390dpi）で検証。
> Gradle wrapper はコミット済みなので `./gradlew` で即ビルド可。iOS は Xcode 未導入のため未検証。

---

## ビルド手順

```bash
# JDK が PATH に無い場合は Android Studio 同梱の JBR を使う:
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

# Android（wrapper はコミット済み）
./gradlew :composeApp:assembleDebug
#   端末/エミュレータへ: ./gradlew :composeApp:installDebug
#   出力: composeApp/build/outputs/apk/debug/composeApp-debug.apk

# iOS … iosApp/ の Xcode プロジェクトが必要（要 Xcode）。
#   KMP ウィザード(Android Studio の Kotlin Multiplatform プラグイン)で
#   iosApp を生成し、MainViewControllerKt.MainViewController() をホストする。
```

> `local.properties`(SDK パス) は .gitignore 済み。各環境で `sdk.dir=...` を作成するか
> `ANDROID_HOME` を設定する。

## ディレクトリ

```
composeApp/src/
├─ commonMain/kotlin/app/nostrdeck/
│   ├─ App.kt                 … ルート。DeckState を remember し AppScaffold へ
│   ├─ state/DeckState.kt     … 統合カラム状態（pin/transient・open/close/jump）
│   ├─ theme/                 … Color/Theme（tokens.css と 1対1: DeckType/Space/Radius/Dimens/Weight）
│   ├─ model/Models.kt        … Event/Profile/ColumnSpec/Channel/ThreadEntry…
│   ├─ crypto/                … Nip01（イベントID/署名）/ Nip19（bech32）
│   ├─ nostr/                 … リレープール（Ktor WebSocket・REQ ライフサイクル）
│   ├─ signer/                … Signer 抽象 / LocalSigner / KeyVault（Keystore）
│   ├─ data/                  … EventRepository（SSOT・cache-first）/ NetworkPolicy(expect)
│   └─ ui/                    … AppScaffold / DeckRail / DeckScreen / 各カラム・画面 /
│                               DeckControls（共通ボタン・入力・確認ダイアログ）/
│                               ComposeSheet / ReactionPicker / NoteImages / ImageProxy …
├─ commonMain/sqldelight/…/   … Nostr.sq（SSOT スキーマ）+ 1〜7.sqm（マイグレーション）
├─ androidMain/               … MainActivity / NetworkPolicy.android / Manifest
└─ iosMain/                   … MainViewController / NetworkPolicy.ios
```

## 次の実装ステップ
[TASKS.md](./TASKS.md) の「バックログ」を参照（検索 / DM / Zap / カラム並べ替え / NIP-44 ほか）。
