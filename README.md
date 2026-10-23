# store-assets

ストア登録用の画像だけを置くブランチ（main には入れない生成物）。
`.github/workflows/store-listing.yml`（Google Play の掲載情報の登録）がここの `play/` を読む。

作り方は main の `distribution/store/README.md`。作り直したら `distribution/store/out/play/` の中身（foldable を除く）をここの `play/` へ入れてコミットする。
