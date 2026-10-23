# リリース手順書（AI 向け / Claude・Cursor 実行用）

このリポジトリのリリースは **自動ではない**。`main` への push でストア配信は**走らない**。
リリースは **AI エージェント（または人）が、この手順書に従って明示的に実行**する。

- Android → Play Console クローズドテスト（`alpha` トラック）
- iOS → TestFlight（**macOS への提供はしない**。[#221] で `SUPPORTS_MAC_DESIGNED_FOR_IPHONE_IPAD=NO`）
- macOS / Android → **GitHub Releases**（**プレビュー配布**。dmg + APK。`scripts/release-github.sh`）

**3系統は毎回セットで出す**（同じタグ・同じ build 番号）。GitHub Releases は
「ストアのテスターでない人にも手早く触ってもらう」ためのプレビュー用で、ストア配信の代わりではない。

> **AI へ**: 「リリースして」「ベータ出して」等を頼まれたら、この手順書のとおり実行すること。
> リリースノート（ユーザー表示文）は**必ず**書く。build 番号は git のコミット数（単調増加）に依存するため、
> **main の履歴を rewrite（force-push / squash / rebase）しないこと**（ストアが番号の減少・再利用を拒否する）。
>
> **配信後は必ず `scripts/release-info.sh` の出力をそのまま会話に貼ること**。
> TestFlight の「テスト内容」記入など**人の操作が残る**ため、コピペできる形で渡す必要がある。

---

## 0. リリース前チェック（全プラットフォーム共通）

1. リリースに含める変更が `main`（または配信対象 ref）に入っているか確認。
1. **バージョンタグを打つ**（[#252]）: `git tag vX.Y.Z && git push --tags`
   バグ修正=PATCH / 機能追加=MINOR / 破壊的変更=MAJOR。`scripts/version.sh --on-tag` が `1` になることを確認。
2. **リリースノートを書く**（下記スタイル）。Android は `distribution/whatsnew/whatsnew-ja-JP` を更新してコミット。
   iOS の「テスト内容」も同じ文面を流用し、GitHub Releases のノートにも使われる。英語版
   `whatsnew-en-US` も併せて更新する。
   **`distribution/whatsnew/test-focus-ja` も毎回書き換えること。** TestFlight の「テスト内容」
   末尾に【重点確認】として出る、そのリリースで見てほしい点。ここは `whatsnew` と違って
   更新を忘れても配信は通ってしまい、**前回の確認項目が残ったまま**テスターに届く
   （v0.6.6/v0.6.7 で実際に v0.6.5 の項目が出ていた）。
3. 破壊的変更・既知の不具合があれば、リリースノート末尾かテスト内容に明記。

### 実行順（1タグ = 3系統。同じ build 番号で揃える）
```bash
# 1. Android（Play クローズドテスト）… §1
gh workflow run release-beta.yml --ref main -f track=alpha -f status=completed
# 2. iOS（TestFlight）… §2   ※1と並行実行してよい
cd iosApp && ./scripts/testflight.sh; cd ..
# 3. GitHub Releases（プレビュー配布: dmg + APK）… §3
scripts/release-github.sh
# 4. 人へ渡す情報を出力（TestFlight のテスト内容など）… §6
scripts/release-info.sh
```

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
#     distribution/whatsnew/whatsnew-ja-JP と whatsnew-en-US を編集
#     （TestFlight 用の test-focus-ja も忘れず更新する。§0-2 参照）
git add distribution/whatsnew/whatsnew-ja-JP
git commit -m "リリースノート更新: <一言>"
git push origin main            # または配信対象ブランチ

# (2) ワークフローを起動（トリガーは workflow_dispatch のみ）
gh workflow run release-beta.yml --ref main \
  -f track=alpha -f status=completed
#   ※ track は非推奨の警告が出るが現状は動作する（将来 tracks へ移行が必要）
#   track:  alpha=クローズド / internal=内部 / beta=オープン / production=製品版
#   status: completed=即公開 / draft=下書き / inProgress=段階公開
#   version_name= を渡さなければ scripts/version.sh 由来（直近の git tag vX.Y.Z）

# (3) 実行を監視
sleep 5
RUN=$(gh run list --workflow=release-beta.yml --limit 1 --json databaseId -q '.[0].databaseId')
gh run watch "$RUN" --exit-status
```

### 公開（管理対象の公開がオン）
2026-10 に Play Console の「**管理対象の公開**」をオンにした（審査にはすぐ送り、反映の時期は人が決める運用）。
ワークフローの配信は確定と同時に審査に送られるが、`status=completed` でも**テスターにはすぐ届かない**。人が Play Console で公開する:

1. 公開の概要 → 審査が通って「変更の公開準備完了」に移ったら「**変更を公開**」（数分で反映）
2. 「審査にまだ送信されていない変更」に残っているものがあれば「**審査に送信**」

内部テストトラックは管理対象の公開の対象外（すぐ届く）。

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
export ASC_KEY_PATH="$HOME/.appstoreconnect/private_keys/AuthKey_XXXXXXXXXX.p8"   # .p8 の絶対パス
```
> ★重要★ **配布署名には Admin ロールの API キーが必須**。Developer ロールだと archive は通るが
> export で「cloud-managed distribution certificates へのアクセス無し」で失敗する。
>
> ★.p8 の置き場所★ **`~/.appstoreconnect/private_keys/` に置く**（chmod 600）。
> `~/Downloads` に置いたままだと macOS の TCC 保護下のため、xcodebuild が
> `Invalid authentication key credential specified … couldn't be opened (Interrupted system call)`
> で失敗することがある（v0.6.1 の配信で実際に発生。同じパスで以前は成功していたので再現性は低いが、
> 保護外の場所に置けば起きない）。

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

## 3. GitHub Releases（プレビュー配布: macOS dmg + Android APK）

**位置づけ: プレビュー配布**。ストアのテスターに登録していない人へ「とりあえず触ってもらう」ための
配布口で、Play/TestFlight の代わりではない（**両方出す**）。
[#221] Mac 版はここが唯一の配布口（App Store には出さない。TestFlight の macOS 提供も切ってある）。

### 手順
```bash
# Play/TestFlight と同じタグ・同じ build 番号で（version.sh --on-tag = 1 の状態で）:
scripts/release-github.sh
#   dmg（packageDmg）と APK（assembleRelease）をビルド → Nostrism-<version>-{macos.dmg,android.apk}
#   → Release v<version> を作成/更新（ノート = whatsnew-ja-JP + 配布物ごとの注意を自動生成）→ 添付。
# ビルド確認だけ    : DRY_RUN=1 scripts/release-github.sh
# 片方だけ          : SKIP_MAC=1 / SKIP_ANDROID=1
```

### 注意（Release ノートにも自動で入るが、意味を理解しておくこと）
- **Android APK は Play 版と署名が違う**（こちらは*アップロード鍵*、Play 配信版は Play App Signing の
  *アプリ署名鍵*）。したがって**相互に上書き更新できない**。入れ替えるにはアンインストールが必要で、
  ローカルデータ（鍵・DB）も消える。Play のテスターには Play 版の更新を案内する。
- **dmg は未署名**（Developer ID 署名/公証は #221 の残タスク）。利用者は初回のみ
  「右クリック→開く」（または `xattr -dr com.apple.quarantine`）が必要。
- dmg 内部の packageVersion は jpackage の制約（MAJOR>0）で 1.0.0 固定。
  実バージョンはファイル名（`Nostrism-<version>-macos.dmg`）とアプリ内表示で判別する。
- APK ビルドでは `lintVital*` を除外している（AGP と Kotlin のバージョン差で lint 解析が
  クラッシュするため。コードは CI で全ターゲット検証済み）。恒常化するなら lint 側の整合を取る。
- versionCode/Name はスクリプトが `version.sh` の値を `-PversionCode/-PversionName` で渡す。
  手で `assembleRelease` すると既定値（1 / 0.1.0）になるので注意。

---

## 4. バージョン採番のルール（[#252] セマンティックバージョニング）

**単一の真実は `scripts/version.sh`**。Gradle / iOS / CI すべてがこの規則で導出する。

| 項目 | 導出元 | 例 |
|---|---|---|
| versionName（表示版・両OS共通） | 直近の **git tag `vX.Y.Z`** | `0.3.0` |
| versionCode / CFBundleVersion（build番号） | **コミット数** `git rev-list --count HEAD` | `436` |

```bash
scripts/version.sh            # "0.3.0 436 1"（name build on-tag）
scripts/version.sh --name     # 0.3.0
scripts/version.sh --build    # 436
scripts/version.sh --on-tag   # 1=HEADがタグ上（リリース版） / 0=タグより進んでいる
```

### 番号の上げ方（リリース前に必ずタグを打つ）
```bash
git tag v0.3.1 && git push --tags     # ← これを忘れると前の版の versionName で配信される
```
- **PATCH**（`0.3.0` → `0.3.1`）: バグ修正のみ
- **MINOR**（`0.3.1` → `0.4.0`）: 機能追加（後方互換）
- **MAJOR**（`0.4.0` → `1.0.0`）: 破壊的変更・正式公開の節目

タグを打たずに配信しようとすると、`testflight.sh` と CI が**警告を出す**（build 番号は進むので配信自体は通る）。
バグ修正だけのリリースでも PATCH を上げてタグを打つ運用にする。

- 単調増加が前提。**履歴 rewrite 厳禁**（減少・重複するとストアが拒否し、その番号は二度と使えない）。
- `workflow_dispatch` の `version_name` を渡せばタグを無視して任意の versionName にできる（緊急時のみ）。

## 5. なぜ main 自動配信をやめたか
- 「あらゆるマージ＝配信」だと iOS 専用/ドキュメントだけの変更でも Android が出てしまう、リリース内容の
  再現性が低い、ホットフィックスを単独で出せない、等のリスクがあるため。
- 代わりに **AI がこの手順書に沿って、リリースノート込みで明示実行**する運用にした。
  `main` から出す必要はなく、任意の ref を対象にできる。

---

## 6. 配信後（人の操作が残る部分）

**`scripts/release-info.sh` を実行し、出力をそのまま会話へ貼る。**

```bash
scripts/release-info.sh
```

出力に含まれるもの:
- バージョン / build 番号 / タグ状態（タグ未打ちなら警告）
- リリースノート ja / en（コピペ用）
- **iOS TestFlight「テスト内容（What to Test）」に貼る文面**（whatsnew + `distribution/whatsnew/test-focus-ja` の重点確認）
- App Store Connect / Play Console での操作手順（build 番号入り）

重点確認したい項目は `distribution/whatsnew/test-focus-ja` に書いておくと、テスト内容へ自動で連結される。
