#!/usr/bin/env bash
# [#221] GitHub Releases への**プレビュー配布**（macOS dmg + Android APK）。
#
# 位置づけ: ストア配信（Play クローズドテスト / TestFlight）とは別の、
# 「手早く触って試してもらう」ためのプレビュー配布。審査もテスター登録も要らない代わりに、
# 署名の都合で Play 版との相互更新はできない（この注意を Release ノートに必ず載せる）。
#
# 前提:
#  - リリースタグ済み（`git tag vX.Y.Z && git push --tags` / version.sh --on-tag が 1）
#  - `gh` CLI ログイン済み
#  - macOS（dmg 生成は jpackage 依存）。Android APK の署名は keystore.properties が必要
#
# 使い方:
#   scripts/release-github.sh              # dmg + APK をビルドして Release へ添付
#   DRY_RUN=1 scripts/release-github.sh    # ビルドのみ（Release は作らない・タグ未打ちでも可）
#   SKIP_MAC=1 scripts/release-github.sh   # Android だけ
#   SKIP_ANDROID=1 scripts/release-github.sh
set -euo pipefail
cd "$(dirname "$0")/.."

# [#948] 読み上げの ZEN-TTS ランタイム（ライセンス上リポジトリに入れられないので、ビルドのたびに取ってきて同梱する）
scripts/fetch-zen-tts.sh

SKIP_MAC="${SKIP_MAC:-0}"
SKIP_ANDROID="${SKIP_ANDROID:-0}"

# dmg 生成には jpackage 入りのフル JDK が必要（Android Studio 同梱の JBR には無い。
# Homebrew の openjdk は Compose Desktop が packaging 用に拒否する #3107）。
# 現在の JAVA_HOME に無ければ ~/.jdks の Temurin/Corretto を探す。
if [ "$SKIP_MAC" != "1" ]; then
  if [ ! -x "${JAVA_HOME:-/nonexistent}/bin/jpackage" ]; then
    for cand in "$HOME"/.jdks/jdk-21*/Contents/Home /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home; do
      if [ -x "$cand/bin/jpackage" ]; then export JAVA_HOME="$cand"; break; fi
    done
  fi
  if [ ! -x "${JAVA_HOME:-/nonexistent}/bin/jpackage" ]; then
    echo "⚠ jpackage 入りの JDK が見つかりません。例:" >&2
    echo "  mkdir -p ~/.jdks && cd ~/.jdks && curl -sL 'https://api.adoptium.net/v3/binary/latest/21/ga/mac/aarch64/jdk/hotspot/normal/eclipse' | tar xz" >&2
    exit 1
  fi
fi
# Android だけのときは手元の JDK（Android Studio 同梱 JBR 等）で足りる。
if [ ! -x "${JAVA_HOME:-/nonexistent}/bin/java" ] && [ -x "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi

NAME=$(scripts/version.sh --name)
BUILD=$(scripts/version.sh --build)
ON_TAG=$(scripts/version.sh --on-tag)
if [ "$ON_TAG" != "1" ]; then
  echo "⚠ HEAD がバージョンタグ上ではありません（version=${NAME}）。先に \`git tag vX.Y.Z && git push --tags\` を実行してください" >&2
  # DRY_RUN はビルド確認用なのでタグ無しでも続行する。
  [ "${DRY_RUN:-0}" = "1" ] || exit 1
fi
TAG="v$NAME"
ASSETS=()

# ---- macOS（Compose Desktop dmg）----
if [ "$SKIP_MAC" != "1" ]; then
  echo "==> Building dmg (version=$NAME)"
  # JAVA_HOME を切り替えても、Gradle は起動済みの JBR(21) デーモンを使い回しうる（#438）。
  # jpackage 入りの JDK を dmg 用に明示する。
  ./gradlew :composeApp:packageDmg -q -PpackagingJavaHome="$JAVA_HOME"
  # [#730] 同梱ランタイムに java.sql が無いと、起動時に DB を開けず落ちる（v0.6.18〜0.6.20 の dmg で実際に起きた）。
  # build.gradle.kts の modules(...) から漏れたら、配る前にここで止める。
  RUNTIME_RELEASE="composeApp/build/compose/binaries/main/app/Nostrism.app/Contents/runtime/Contents/Home/release"
  if ! grep -E '^MODULES=' "$RUNTIME_RELEASE" | grep -q 'java.sql'; then
    echo "✗ dmg の同梱ランタイムに java.sql が入っていません（SQLite ドライバを読めず起動できない）: $RUNTIME_RELEASE" >&2
    grep -E '^MODULES=' "$RUNTIME_RELEASE" >&2
    exit 1
  fi
  # jpackage の制約で packageVersion は 1.0.0 固定（MAJOR>0 必須）。実バージョンはファイル名で示す。
  SRC=$(ls composeApp/build/compose/binaries/main/dmg/*.dmg | head -1)
  DMG="composeApp/build/compose/binaries/main/dmg/Nostrism-$NAME-macos.dmg"
  [ "$SRC" = "$DMG" ] || cp "$SRC" "$DMG"
  ASSETS+=("$DMG")
  echo "==> $DMG"
fi

# ---- Android（release APK）----
if [ "$SKIP_ANDROID" != "1" ]; then
  echo "==> Building Android APK (version=$NAME build=$BUILD)"
  # versionCode/Name は CI と同じく明示的に渡す（未指定だと build.gradle.kts の既定 1 / 0.1.0 になる）。
  # lintVital は AGP と Kotlin のバージョン差で解析クラッシュするため除外する
  # （コード自体は CI で全ターゲット検証済み。恒常化するなら lint 側の整合を取ること）。
  ./gradlew :composeApp:assembleRelease -q \
    -PversionCode="$BUILD" -PversionName="$NAME" \
    -x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease
  APK="composeApp/build/outputs/apk/release/Nostrism-$NAME-android.apk"
  cp composeApp/build/outputs/apk/release/composeApp-release.apk "$APK"
  ASSETS+=("$APK")
  echo "==> $APK"
fi

if [ "${DRY_RUN:-0}" = "1" ]; then
  echo "==> DRY_RUN: GitHub Release へのアップロードはスキップ"
  exit 0
fi

# Release ノート = whatsnew + 配布物ごとの注意（毎回同じ注意を書き忘れないよう自動生成する）。
NOTES=$(mktemp)
{
  cat distribution/whatsnew/whatsnew-ja-JP
  echo
  echo "---"
  echo
  echo "### ダウンロード（プレビュー配布）"
  echo
  if [ "$SKIP_ANDROID" != "1" ]; then
    echo "- **Android**: \`Nostrism-${NAME}-android.apk\`（直接インストール用）"
    echo "  ⚠️ Play 版（クローズドテスト）とは**署名が異なる**ため、上書き更新はできません。入れ替える場合は一度アンインストールしてください（データも消えます）。Play でテスト参加中の方は Play 版の更新をお使いください。"
  fi
  if [ "$SKIP_MAC" != "1" ]; then
    echo "- **macOS**: \`Nostrism-$NAME-macos.dmg\`"
    echo "  ⚠️ 現在未署名です。初回起動時は Finder で**右クリック→開く**を選択してください。"
  fi
  echo "- **iOS**: TestFlight（build ${BUILD}）で配信しています。"
} > "$NOTES"

if gh release view "$TAG" >/dev/null 2>&1; then
  gh release edit "$TAG" --notes-file "$NOTES" >/dev/null
else
  gh release create "$TAG" --title "Nostrism $NAME" --notes-file "$NOTES"
fi
rm -f "$NOTES"

for a in "${ASSETS[@]}"; do gh release upload "$TAG" "$a" --clobber; done
echo "==> uploaded: $(gh release view "$TAG" --json url -q .url)"
gh release view "$TAG" --json assets --jq '.assets[] | "    \(.name)"'
