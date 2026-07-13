# リリース手順書（AI 向け / Claude・Cursor 実行用）

このリポジトリのリリースは **自動ではない**。`main` への push でストア配信は**走らない**。
リリースは **AI エージェント（または人）が、この手順書に従って明示的に実行**する。

- Android → Play Console クローズドテスト（`alpha` トラック）
- iOS → TestFlight

> **AI へ**: 「リリースして」「ベータ出して」等を頼まれたら、この手順書のとおり実行すること。
> リリースノート（ユーザー表示文）は**必ず**書く。バージョン採番は git のコミット数（単調増加）に依存するため、
> **main の履歴を rewrite（force-push / squash / rebase）しないこと**（ストアが番号の減少・再利用を拒否する）。

---

## 0. リリース前チェック（両プラットフォーム共通）

1. リリースに含める変更が `main`（または配信対象 ref）に入っているか確認。
2. **リリースノートを書く**（下記スタイル）。Android は `distribution/whatsnew/whatsnew-ja-JP` を更新してコミット。
   iOS の「テスト内容」も同じ文面を流用する。
3. 破壊的変更・既知の不具合があれば、リリースノート末尾かテスト内容に明記。

### リリースノートの書き方
- **日本語が主**（アプリの主対象）。ユーザー目線で「何が良くなったか」を簡潔に。実装用語は避ける。
- 箇条書き、1項目1行、Play は**1言語あたり500字以内**。
- 例:
  ```
  ・パブリックチャットの入力が快適に（キーボード追従・複数行・リプライ/絵文字）
  ・プロフィールでフォロー/フォロワーやミュートが可能に
  ・クラッシュ修正と省メモリ化
  ```

---

## 1. Android（Play クローズドテスト）

**署名鍵・Play 認証（WIF）は GitHub Secrets にあり、配信は GitHub Actions で走る**（ローカルに鍵は無い）。
AI はワークフローを起動して結果を見るだけ。

### 手順
```bash
# (1) リリースノートを更新してコミット（配信対象 ref に入れる）
#     distribution/whatsnew/whatsnew-ja-JP を編集
git add distribution/whatsnew/whatsnew-ja-JP
git commit -m "リリースノート更新: <一言>"
git push origin main            # または配信対象ブランチ

# (2) ワークフローを起動（トリガーは workflow_dispatch のみ）
gh workflow run release-beta.yml --ref main \
  -f track=alpha -f status=completed
#   track:  alpha=クローズド / internal=内部 / beta=オープン / production=製品版
#   status: completed=即公開 / draft=下書き / inProgress=段階公開
#   version_name= を渡さなければ 0.2.0-beta.<コミット数> で自動採番

# (3) 実行を監視
sleep 5
RUN=$(gh run list --workflow=release-beta.yml --limit 1 --json databaseId -q '.[0].databaseId')
gh run watch "$RUN" --exit-status
```

### 確認
- Play Console → 対象アプリ → テスト → クローズドテスト（Alpha）に新バージョンが出ているか。
- 失敗時は `gh run view "$RUN" --log-failed` でログ確認。よくある詰まり:
  - `whatsnew-<lang>` の言語コードが Play 掲載言語と不一致 → ファイル名を掲載言語に合わせる。
  - 署名/WIF は Secrets 依存（[[play-beta-ci-setup]] 参照）。

---

## 2. iOS（TestFlight）

**ローカルの Mac で `iosApp/scripts/testflight.sh` を実行**（Xcode + Admin API キーが必要）。

### 必要な環境変数（値は開発者の安全な手元メモ / エージェントメモリを参照。リポジトリには置かない）
```bash
export TEAM_ID=…                 # Apple Developer チームID（10桁）
export ASC_KEY_ID=…              # App Store Connect API キーID（★Admin ロール★）
export ASC_ISSUER_ID=…           # Issuer ID（チーム共通・不変）
export ASC_KEY_PATH=…/AuthKey_XXXXXXXXXX.p8   # .p8 の絶対パス（gitignore 済み）
```
> ★重要★ **配布署名には Admin ロールの API キーが必須**。Developer ロールだと archive は通るが
> export で「cloud-managed distribution certificates へのアクセス無し」で失敗する。

### 手順
```bash
cd iosApp
./scripts/testflight.sh
#  xcodegen 再生成 → Release アーカイブ → App Store Connect へ export+upload。
#  build 番号(CFBundleVersion)は git コミット数で自動採番。versionName は project.yml の MARKETING_VERSION。
```

### アップロード後（App Store Connect / Web）
1. **TestFlight** タブでビルドの処理完了を待つ（数分〜数十分）。
2. ビルドに「**テスト内容（What to Test）**」を記入（Android と同じリリースノート文面を流用）。
3. 配布先:
   - **内部テスト**（App Store Connect ユーザー最大100名）: 審査不要で即配布。
   - **外部テスト**（公開リンク等・最大10,000名）: 初回ビルドは **Beta App Review** が必要（通常1日程度）。
     公開リンクは 外部グループ > 「公開リンクを有効化」で発行（`https://testflight.apple.com/join/xxxxxxxx`）。

### 詳細
- iOS ビルド基盤・詰まりどころは [[ios-testflight-setup]] と `iosApp/README.md`。

---

## 3. バージョン採番のルール
- **versionCode / build 番号 = 実行 ref の git コミット数**（`git rev-list --count HEAD`）。両ストア共通の考え方。
- 単調増加が前提。**履歴 rewrite 厳禁**（減少・重複するとストアが拒否し、その番号は二度と使えない）。
- Android の versionName 既定は `0.2.0-beta.<code>`（`workflow_dispatch` の `version_name` で上書き可）。
  iOS の表示版は `iosApp/project.yml` の `MARKETING_VERSION`。

## 4. なぜ main 自動配信をやめたか
- 「あらゆるマージ＝配信」だと iOS 専用/ドキュメントだけの変更でも Android が出てしまう、リリース内容の
  再現性が低い、ホットフィックスを単独で出せない、等のリスクがあるため。
- 代わりに **AI がこの手順書に沿って、リリースノート込みで明示実行**する運用にした。
  `main` から出す必要はなく、任意の ref を対象にできる。
