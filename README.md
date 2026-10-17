# Nostr Deck Client

フォルダブル/大画面に最適化した Deck 型の Nostr ネイティブクライアント。
Kotlin + **Compose Multiplatform**（Android / iOS / iPadOS / macOS）。

紹介ページとテーマストアの Web プレビューは **[docs/](./docs/)**（Cloudflare Pages で配信）。

設計の根拠と全体像は **[whiteboard.md](./whiteboard.md)** に集約。
デザインモック（HTML）は **[designs/index.html](./designs/index.html)**（ブラウザで開く）。
リリース手順（Play / TestFlight / GitHub Releases）は **[docs/RELEASING.md](./docs/RELEASING.md)**。

## ダウンロード

- **Android / macOS**: [Releases](../../releases) からプレビュー版（APK / dmg）
  ※ Play 版とは署名が異なるため相互に上書き更新できません。dmg は未署名（初回は右クリック→開く）
- **Android（テスター向け）**: Play クローズドテスト
- **iOS**: TestFlight

---

## いまの状態（実データで稼働）

実リレー接続・DB・署名・投稿・画像/動画・通知・パブリックチャット・DM・Zap まで動く状態。
タスクの詳細・バックログは **[TASKS.md](./TASKS.md)** を参照。

| 領域 | 状態 |
|---|---|
| Deck レイアウト / アダプティブ・ナビ / カラム統合モデル・並べ替え | ✅ |
| リレープール（Ktor WS・カラム=REQ ライフサイクル・指数バックオフ・NIP-42 AUTH） | ✅ |
| SQLDelight SSOT（cache-first・マイグレーション 1〜8.sqm） | ✅ |
| kind:0 バッチ解決 / NIP-65 アウトボックス（リレー設定UI含む） | ✅ |
| 投稿（kind:1）・返信(NIP-10)・リポスト/引用(NIP-18)・リアクション(NIP-25/30) | ✅ |
| 画像/動画（グリッド/カルーセル/Lightbox・インライン再生）・NIP-96 / Blossom アップロード（圧縮つき・投稿ごとに送信先を変更可） | ✅ |
| 通知（メンション/リプライ/リアクション/リポスト/Zap） | ✅ |
| パブリックチャット（NIP-28 kind:40/41/42）— 一覧は thread.nchan.vip 由来、作成/編集はアプリ内 | ✅ |
| 検索（NIP-50・キーワード/タグ複合） | ✅ |
| DM（NIP-17 gift wrap + NIP-44、旧 NIP-04 も復号して統合） | ✅ |
| Zap（NIP-57）・アプリ内送金（NIP-47 NWC・毎回確認） | ✅ |
| NIP-51 リスト（ミュート / ブックマーク / 固定投稿 / カスタム絵文字 / 参加中のパブリックチャット kind:10005） | ✅ |
| テーマ（3色カスタム + 自動導出）・テーマストア（NIP-78 kind:30078） | ✅ |
| カスタム絵文字エディタ（kind:10030 の編集） | ✅ |
| 長文記事（NIP-23 kind:30023）の閲覧 | ✅ |
| キーボードショートカット（j/k/h/l・n/r/t/f/b・? ヘルプ） | ✅ |
| デザインシステム（DeckType/Space/Radius/Dimens/Weight・DeckControls・確認ダイアログ） | ✅ |
| Signer（LOCAL + Android Keystore / iOS Keychain・NIP-55 外部署名・NIP-46 リモート署名） | ✅ |
| iOS（TestFlight 配信） | ✅ |
| macOS（Compose Desktop・dmg 配布） | 🟡 一部（スクロール/ショートカットに既知の不具合 #276 #221） |
| カラム構成のリレー保存（NIP-78） | ⬜ 凍結中（#138） |

> ✅ Android エミュレータ（Pixel 10 Pro Fold 同寸 2076×2152 / 390dpi）で検証。
> Gradle wrapper はコミット済みなので `./gradlew` で即ビルド可。

---

## ビルド手順

**JDK 21 が必要**（#438）。Android Studio 同梱の JBR は 21 なのでそれで足りる。
`gradle/gradle-daemon-jvm.properties` で Gradle 本体を JDK 21 で動かすよう指定してあり、
JDK 17 から起動しても手元に 21 があればそちらを使う（無ければ「Java 21 が見つからない」で止まる）。
Desktop の依存（secp256k1-kmp-jni-jvm 0.17 以降）が JVM 21 を要求するため、17 では解決に失敗する。
生成するバイトコード（`jvmTarget`）は 17 のまま。

```bash
# JDK が PATH に無い場合は Android Studio 同梱の JBR(21) を使う:
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

# Android（wrapper はコミット済み）
./gradlew :composeApp:assembleDebug
#   端末/エミュレータへ: ./gradlew :composeApp:installDebug
#   出力: composeApp/build/outputs/apk/debug/composeApp-debug.apk

# iOS … iosApp/ に Xcode プロジェクトあり（要 Xcode）
./gradlew :composeApp:compileKotlinIosSimulatorArm64
#   TestFlight 配信: iosApp/scripts/testflight.sh（docs/RELEASING.md 参照）

# macOS（Compose Desktop）
./gradlew :composeApp:run
#   dmg: scripts/release-github.sh（jpackage には Temurin JDK 21 が必要）

# 全ターゲットの検証（変更時はこれを通す）
./gradlew :composeApp:compileDebugKotlinAndroid \
  :composeApp:compileKotlinIosSimulatorArm64 :composeApp:compileKotlinIosArm64 \
  :composeApp:compileKotlinDesktop :composeApp:desktopTest
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
│   ├─ crypto/                … Nip01（イベントID/署名）/ Nip19（bech32）/ Nip04・Nip44（暗号）
│   ├─ nostr/                 … リレープール（Ktor WebSocket・REQ ライフサイクル）
│   ├─ signer/                … Signer 抽象 / LocalSigner / KeyVault / Nip46（リモート署名）
│   ├─ wallet/                … NWC（NIP-47）クライアント・接続情報のセキュア保存
│   ├─ data/                  … EventRepository（SSOT・cache-first）/ NetworkPolicy(expect)
│   └─ ui/                    … AppScaffold / DeckRail / DeckScreen / 各カラム・画面 /
│                               DeckControls（共通ボタン・入力・確認ダイアログ）/
│                               ComposeSheet / ReactionPicker / NoteImages / ImageProxy …
├─ commonMain/sqldelight/…/   … Nostr.sq（SSOT スキーマ）+ 1〜8.sqm（マイグレーション）
├─ androidMain/               … MainActivity / NetworkPolicy.android / Manifest
├─ iosMain/                   … MainViewController / KeychainKeyVault / NetworkPolicy.ios
└─ desktopMain/               … main.kt（Compose Desktop エントリ）
```

その他のディレクトリ:

```
nostr-core/     … プロトコル層の共有モジュール（NostrEvent / Embed / Blurhash …）
iosApp/         … Xcode プロジェクト + scripts/testflight.sh
docs/           … 公開サイト（Cloudflare Pages）+ RELEASING.md + ストア資材
scripts/        … version.sh（バージョン導出）/ release-github.sh / release-info.sh
distribution/   … Play 配信用の whatsnew
```

## 次の実装ステップ

未対応・保留中は [Issues](../../issues) を参照。主なものは以下。

- **#141** ExoPlayer のプール化（動画多用フィードの負荷軽減）
- **#222** キーボードショートカット Phase2（go-to / 対象カラムの拡張）
- **#276 / #221** macOS のスクロール・ショートカット（Mac 対応は現在保留）
- **#138** カラム構成のリレー保存（NIP-78）の再有効化
- **#164 / #29** ストア資材の整備・公開前チェックリスト

設計の背景は [whiteboard.md](./whiteboard.md)、過去の作業履歴は [TASKS.md](./TASKS.md)。
