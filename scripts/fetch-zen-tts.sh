#!/usr/bin/env bash
# [#948] ZEN-TTS（https://github.com/zenryokukikai/zen-tts）のブラウザ用ランタイムを、固定したコミットから取ってきて
# ビルドし、アプリのリソース（composeApp/src/commonMain/composeResources/files/zen-tts/）へ置く。
#
# - 声のモデル（重み）は入れない。アプリが設定画面から公式の配布元（同じコミット）をダウンロードする
# - ZEN-TTS License 1.0: 改変せずに依存として取り込み、バンドルするのは可。再配布は不可なので、
#   取ってきたものはこのリポジトリには入れない（.gitignore）。ビルドのたびにここで取ってくる
# - ソースは改変しない。TypeScript を JavaScript に変換（tsc）するだけ
#
# 使い方: scripts/fetch-zen-tts.sh（同じコミットを取ってあれば何もしない）。Node.js（npx）と git が要る。
set -euo pipefail

COMMIT=14b404d527a3d074de8e7ef28d44b14514400a40   # v1.0.0
TS_VERSION=7.0.2                                  # zen-tts の devDependencies（typescript ^7.0.2）
REPO_URL=https://github.com/zenryokukikai/zen-tts.git

ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT="$ROOT/composeApp/src/commonMain/composeResources/files/zen-tts"
if [ -f "$OUT/COMMIT" ] && [ "$(cat "$OUT/COMMIT")" = "$COMMIT" ]; then
  echo "zen-tts: up to date ($COMMIT)"
  exit 0
fi

WORK="$ROOT/build/zen-tts"
rm -rf "$WORK"
mkdir -p "$WORK/src"
# 重み（models/。約 120 MB）は取らない: ソース・CPU カーネル・ライセンスだけを取り出す
git -C "$WORK/src" init -q
git -C "$WORK/src" remote add origin "$REPO_URL"
git -C "$WORK/src" fetch -q --depth 1 --filter=blob:none origin "$COMMIT"
git -C "$WORK/src" -c advice.detachedHead=false checkout -q FETCH_HEAD -- src cpu tsconfig.json LICENSE.md THIRD_PARTY_NOTICES.md
[ "$(git -C "$WORK/src" rev-parse FETCH_HEAD)" = "$COMMIT" ] || { echo "zen-tts: unexpected commit" >&2; exit 1; }

# TypeScript → JavaScript（ソースはそのまま。出力先だけ外に向ける）
(cd "$WORK" && npx -y -p "typescript@$TS_VERSION" tsc -p "$WORK/src/tsconfig.json" --outDir "$WORK/dist")

rm -rf "$OUT"
mkdir -p "$OUT/runtime" "$OUT/cpu"
cp "$WORK/dist/index.js" "$WORK/dist/version.js" "$OUT/"
cp "$WORK/dist/runtime/"*.js "$OUT/runtime/"
cp "$WORK/src/cpu/tts-cpu.wasm" "$OUT/cpu/"
cp "$WORK/src/LICENSE.md" "$WORK/src/THIRD_PARTY_NOTICES.md" "$OUT/"
echo "$COMMIT" > "$OUT/COMMIT"
echo "zen-tts: $COMMIT -> $OUT"
