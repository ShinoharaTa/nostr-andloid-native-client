#!/usr/bin/env bash
# [#252] リリース時に「人が手で操作する部分」をコピペしやすい形で出力する。
#
# 使い方: scripts/release-info.sh
#   バージョン・リリースノート（ja/en）・TestFlight のテスト内容・Play/ASC の操作手順を
#   まとめて表示する。AI/人どちらが実行しても同じ内容が出る。
set -euo pipefail
cd "$(dirname "$0")/.."

NAME=$(scripts/version.sh --name)
BUILD=$(scripts/version.sh --build)
ON_TAG=$(scripts/version.sh --on-tag)
TAG=$(scripts/version.sh --tag)
SHA=$(git rev-parse --short HEAD)

bar() { printf '%s\n' "────────────────────────────────────────────────────────"; }

bar
echo "リリース情報  version=$NAME  build=$BUILD  commit=$SHA"
if [ "$ON_TAG" = "1" ]; then
  echo "タグ: ${TAG}（HEAD 上・リリース版）"
else
  echo "!! HEAD は $TAG より進んでいます。リリース版なら先に:"
  echo "     git tag vX.Y.Z && git push --tags"
  echo "   （バグ修正=PATCH / 機能追加=MINOR / 破壊的変更=MAJOR）"
fi
bar

echo
echo "■ リリースノート（日本語 / Play 用・そのまま反映済み）"
echo "--- copy from here ---"
cat distribution/whatsnew/whatsnew-ja-JP
echo "--- to here ---"

echo
echo "■ リリースノート（English）"
echo "--- copy from here ---"
cat distribution/whatsnew/whatsnew-en-US
echo "--- to here ---"

echo
echo "■ iOS TestFlight「テスト内容（What to Test）」に貼る文面"
echo "--- copy from here ---"
cat distribution/whatsnew/whatsnew-ja-JP
if [ -f distribution/whatsnew/test-focus-ja ]; then
  echo
  echo "【重点確認】"
  cat distribution/whatsnew/test-focus-ja
fi
echo "--- to here ---"

echo
echo "■ 人の操作（iOS / App Store Connect）"
cat <<EOS
  1. App Store Connect → マイApp → Nostrism → TestFlight
  2. build $BUILD の処理完了を待つ（数分〜数十分）
  3. build $BUILD を選択 → 「テスト内容」に上の文面を貼り付け → 保存
  4. 配布: 内部テスト＝審査不要で即 / 外部テスト＝初回のみ Beta App Review（1日程度）
EOS

echo
echo "■ 人の操作（Android / Play Console）"
cat <<EOS
  ※ 「管理対象の公開」がオンなので、Play Console で公開するまでテスターに届かない（リリースノートは whatsnew から自動反映）。
  1. Play Console → Nostrism → 公開の概要
  2. 「審査にまだ送信されていない変更」にリリース $NAME ($BUILD) があれば「審査に送信」
  3. 審査が通って「変更の公開準備完了」に移ったら「変更を公開」
  4. テスト → クローズドテスト（Alpha）で $NAME ($BUILD) が「配信中」か確認
  5. 製品版へ出す場合: リリースダッシュボード → 製品版へ昇格（同じく公開の概要から公開）
EOS
bar
