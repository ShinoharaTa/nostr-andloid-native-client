# iosApp — iOS ホスト

commonMain の Compose UI（`App()` / `MainViewController()`）を iOS アプリとして起動する薄い SwiftUI ホスト。

## 構成
- `project.yml` … **プロジェクト定義の SSOT**。[xcodegen](https://github.com/yonaskolb/XcodeGen) で `.xcodeproj` を生成する。
- `iosApp/iOSApp.swift` … `@main` エントリ。
- `iosApp/ContentView.swift` … `MainViewControllerKt.MainViewController()`（Compose）を `UIViewControllerRepresentable` で載せる。
- `Configuration/Config.xcconfig` … バンドルID・アプリ名・**チームID**（実機署名用）。

KMP フレームワーク（`ComposeApp`）は、Xcode のビルド前スクリプトが
`./gradlew :composeApp:embedAndSignAppleFrameworkForXcode` を実行して埋め込む。

## セットアップ / プロジェクト再生成
`project.yml` を変更したら再生成する:

```bash
brew install xcodegen   # 未導入なら
cd iosApp && xcodegen generate
```

## シミュレータで実行
```bash
cd iosApp
xcodebuild -project iosApp.xcodeproj -scheme iosApp \
  -configuration Debug -destination 'platform=iOS Simulator,name=iPhone 17' \
  -derivedDataPath build build
xcrun simctl install booted build/Build/Products/Debug-iphonesimulator/Nostrism.app
xcrun simctl launch booted net.shino3.nostrism
```

## 実機（iPhone）へインストール
1. `Configuration/Config.xcconfig` の `TEAM_ID` に Apple Developer のチームID（10桁）を入れる。
2. `xcodegen generate` で再生成。
3. `open iosApp.xcodeproj` で Xcode を開き、接続した iPhone を選んで Run。
   - 初回は端末で「設定 > 一般 > VPNとデバイス管理」から開発者証明書を信頼。
   - iPhone 側で「デベロッパーモード」を有効化しておくこと（iOS 16+）。

## TestFlight へ配信

ローカルからアップロードする（CI 連携は別途）。

### 事前準備（初回のみ）
1. **App Store Connect にアプリを登録**: [App Store Connect](https://appstoreconnect.apple.com) >
   マイアプリ > + > 新規アプリ。バンドルIDは `net.shino3.nostrism`（無ければ
   [Certificates, Identifiers & Profiles](https://developer.apple.com/account/resources/identifiers/list)
   で App ID を先に登録）。
2. **App Store Connect API キーを発行**: ユーザーとアクセス > 統合 > キー > 「App Store Connect API」で
   キーを作成（ロールは「App Manager」以上）。ダウンロードした `AuthKey_XXXXXXXXXX.p8`（**再DL不可・要保管**）、
   Key ID、Issuer ID を控える。※`.p8` はリポジトリにコミットしないこと（`.gitignore` 済み）。
3. `Configuration/Config.xcconfig` の `TEAM_ID` にチームID（10桁）を記入。

### アップロード
```bash
cd iosApp
export TEAM_ID=ABCDE12345
export ASC_KEY_ID=XXXXXXXXXX
export ASC_ISSUER_ID=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
export ASC_KEY_PATH=/absolute/path/AuthKey_XXXXXXXXXX.p8
./scripts/testflight.sh
```
`scripts/testflight.sh` が xcodegen 再生成 → Release アーカイブ → App Store Connect へアップロードまで実行する。
ビルド番号(`CFBundleVersion`)は git のコミット数で自動採番（単調増加）。表示バージョンは
`project.yml` の `MARKETING_VERSION`。処理後、TestFlight に反映されるまで数分〜数十分。

## 既知の制約
- 現状 `MainViewController()` は `App()` を引数なしで呼ぶため、iOS 版は **サンプルデータ表示モード**
  （実リレー接続・ログイン・DB・署名は未配線）。実機能化には iosMain の各実装
  （`DriverFactory.ios` / `KeychainKeyVault` / Darwin Ktor など）から `EventRepository` を
  組み立てて `App(repository)` に渡す配線が別途必要。
