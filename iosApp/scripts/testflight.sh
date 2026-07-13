#!/usr/bin/env bash
# TestFlight へビルドをアップロードする（ローカル実行。CI 連携は別途）。
#
# 前提:
#   - Apple Developer（有料）に加入済み、App Store Connect に本アプリ（bundle id）を登録済み
#   - App Store Connect API キー(.p8) を発行済み（ユーザーとアクセス > キー）
#   - xcodegen / Xcode コマンドラインツールが使える
#
# 使い方:
#   export TEAM_ID=ABCDE12345
#   export ASC_KEY_ID=XXXXXXXXXX
#   export ASC_ISSUER_ID=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
#   export ASC_KEY_PATH=/absolute/path/AuthKey_XXXXXXXXXX.p8
#   ./scripts/testflight.sh
#
# ビルド番号(CFBundleVersion)は git のコミット数で自動採番（単調増加）。
set -euo pipefail

cd "$(dirname "$0")/.."   # iosApp/
ROOT="$(cd .. && pwd)"

: "${TEAM_ID:?TEAM_ID を設定してください（Apple Developer チームID・10桁）}"
: "${ASC_KEY_ID:?ASC_KEY_ID を設定してください（App Store Connect APIキーID）}"
: "${ASC_ISSUER_ID:?ASC_ISSUER_ID を設定してください（APIキーの Issuer ID）}"
: "${ASC_KEY_PATH:?ASC_KEY_PATH を設定してください（AuthKey_*.p8 の絶対パス）}"

BUILD_NUMBER="${BUILD_NUMBER:-$(git -C "$ROOT" rev-list --count HEAD)}"
echo "==> build number: $BUILD_NUMBER / team: $TEAM_ID"

# JAVA_HOME（Compose フレームワーク埋め込みの gradle 用）
if [ -z "${JAVA_HOME:-}" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi

# プロジェクトを最新の project.yml から再生成
command -v xcodegen >/dev/null || { echo "xcodegen が必要です: brew install xcodegen"; exit 1; }
xcodegen generate

ARCHIVE="build/Nostrism.xcarchive"
EXPORT_DIR="build/export"
rm -rf "$ARCHIVE" "$EXPORT_DIR"

echo "==> archiving (Release)…"
# 認証キーは archive にも渡す（Xcode に Apple ID 未ログインでも自動プロビジョニングが通るように）。
xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Release \
  -destination 'generic/platform=iOS' \
  -archivePath "$ARCHIVE" \
  DEVELOPMENT_TEAM="$TEAM_ID" \
  CURRENT_PROJECT_VERSION="$BUILD_NUMBER" \
  -allowProvisioningUpdates \
  -authenticationKeyPath "$ASC_KEY_PATH" \
  -authenticationKeyID "$ASC_KEY_ID" \
  -authenticationKeyIssuerID "$ASC_ISSUER_ID" \
  archive

# ExportOptions は変数展開できないので teamID を埋めて生成する。
# destination=upload で export と同時に App Store Connect へ送る。
EXPORT_PLIST="build/ExportOptions.generated.plist"
cat > "$EXPORT_PLIST" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>method</key>
  <string>app-store-connect</string>
  <key>destination</key>
  <string>upload</string>
  <key>teamID</key>
  <string>$TEAM_ID</string>
  <key>signingStyle</key>
  <string>automatic</string>
  <key>uploadSymbols</key>
  <true/>
</dict>
</plist>
PLIST

echo "==> exporting + uploading to App Store Connect…"
xcodebuild -exportArchive \
  -archivePath "$ARCHIVE" \
  -exportOptionsPlist "$EXPORT_PLIST" \
  -exportPath "$EXPORT_DIR" \
  -allowProvisioningUpdates \
  -authenticationKeyPath "$ASC_KEY_PATH" \
  -authenticationKeyID "$ASC_KEY_ID" \
  -authenticationKeyIssuerID "$ASC_ISSUER_ID"

echo "==> 完了。App Store Connect の TestFlight に反映されます（処理に数分〜数十分）。"
