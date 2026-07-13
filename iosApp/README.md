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

## 既知の制約
- 現状 `MainViewController()` は `App()` を引数なしで呼ぶため、iOS 版は **サンプルデータ表示モード**
  （実リレー接続・ログイン・DB・署名は未配線）。実機能化には iosMain の各実装
  （`DriverFactory.ios` / `KeychainKeyVault` / Darwin Ktor など）から `EventRepository` を
  組み立てて `App(repository)` に渡す配線が別途必要。
