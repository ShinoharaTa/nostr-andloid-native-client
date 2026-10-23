# ストア資材（App Store / Google Play の本番公開用）

スクリーンショット・フィーチャーグラフィック・アイコン・掲載文と、提出時に答える項目をまとめる。
画面はすべて実際のアプリ（`App` コンポーザブル）を描いたもので、表示内容は架空のデモ用ユーザーとデモ用の投稿だけ。
実在のユーザー・リレーの投稿は写っていない。

- 掲載文（日本語 / 英語）: [listing.md](listing.md)
- 提出前チェックリストと審査リスク: このファイルの後半

## 成果物

`out/` と `raw/` は生成物なので Git には入れていない。下の「作り直す」の手順でいつでも同じものができる。

| ストア | 枠 | サイズ（px） | 枚数 | 場所 |
|---|---|---|---|---|
| App Store | iPhone 6.9"（Dynamic Island 大） | 1320×2868 | 6 | `out/appstore/<lang>/iphone-6.9/` |
| App Store | iPhone 6.3"（Dynamic Island 中・**必須**） | 1206×2622 | 6 | `out/appstore/<lang>/iphone-6.3/` |
| App Store | iPad 13"（iPad 対応なので**必須**） | 2752×2064 | 4 | `out/appstore/<lang>/ipad-13/` |
| App Store | iPhone Duo 内側 | 2007×2853 | 5 | `out/appstore/<lang>/iphone-duo-inner/` |
| App Store | iPhone Duo 外側 | 1398×2034 | 6 | `out/appstore/<lang>/iphone-duo-outer/` |
| Play | スマートフォン（9:16） | 1080×1920 | 6 | `out/play/<lang>/phone/` |
| Play | 7 インチタブレット（16:9） | 1920×1080 | 5 | `out/play/<lang>/tablet-7/` |
| Play | 10 インチタブレット（16:9） | 2560×1440 | 5 | `out/play/<lang>/tablet-10/` |
| Play | 折りたたみ（予備・16:9） | 2560×1440 | 4 | `out/play/<lang>/foldable/` |
| Play | フィーチャーグラフィック | 1024×500 | 1 | `out/play/<lang>/feature-1024x500.jpg`（代替テキストは `feature-alt.txt`） |
| Play | アイコン（32bit PNG） | 512×512 | 1 | `out/play/icon-512.png` |

`<lang>` は `ja` と `en`。
App Store のアイコンはビルドに含まれる `AppIcon` が使われるので、別に用意しなくてよい。

画像の並び順は、ファイル名の番号どおりにアップロードする。
場面は「ホーム → 通知 → スレッド（リアクションの木）→ パブリックチャット → プロフィール（ステータス）→ 着せ替え」。
大画面の枠は「デッキ → スレッド → プロフィール → 着せ替え → 折りたたみ（閉じた画面と開いた画面）」。

**Play の折りたたみについて。**
Play Console には折りたたみ専用の枠がない。
折りたたみの見せ方（閉じるとスマホ表示、開くとデッキ表示）は、7 インチと 10 インチのタブレットの枠の 5 枚目に入れた。
`foldable/` は、その場面を 1 枚目に置いた並びの予備。10 インチの枠を差し替えたいときに使う。

## 要件（2026-10 時点）

出典: [App Store のスクリーンショットの仕様](https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications)、
[Google Play のプレビュー アセット](https://support.google.com/googleplay/android-developer/answer/9866151)。

**App Store**
- iPhone は 6.3"（1206×2622 または 1179×2556）が必須。6.9" を出すと小さい端末にも縮小して使われる
- iPad 対応アプリは 13"（2064×2752 または 2048×2732、横向き可）が必須
- iPhone Duo（内側 2007×2853・外側 1398×2034）は、iOS 27.1 SDK でビルドしたアプリでは 2027 年 4 月から必須
- 各サイズ最大 10 枚。JPEG か PNG で、透過は不可（そのため JPEG で書き出している）

**Google Play**
- スマートフォンは 2〜8 枚。辺は 320〜3840px で、長い辺は短い辺の 2 倍まで。おすすめ枠の対象になるには 1080×1920 以上の 9:16 を 4 枚以上
- 7 インチ・10 インチのタブレットは各 4〜8 枚。辺は 1080〜7680px で、16:9 か 9:16
- フィーチャーグラフィックは 1024×500 の JPEG か 24bit PNG（透過なし）。要素は中央に寄せ、純白・黒・濃い灰色の背景と端末の画像は避ける
- アイコンは 512×512 の 32bit PNG（アルファ付き）で 1024KB 以下
- 画像の文字は全体の 20% 以内。「無料」「No.1」などの言葉と行動喚起は入れない。端末の枠は付けない

今回の画像はすべてこの要件に合わせてある。
文字は上部の 19〜20% の帯に収め、言語や枠によってはみ出すときは自動で縮めている。

## 作り直す

アプリの画面が変わったときや、コピーを直したときに使う。

```bash
# 1. デモ用の素材（アバター・写真・カスタム絵文字）を作る。結果は demo-media/ に入っていてコミット済みなので、普段は不要
node distribution/store/scripts/make-media.mjs
```

```bash
# 2. アプリの素の画面を描く（デスクトップのテスト環境で App を描画する。ネットワークには出ない）
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
STORE_SHOTS=ja ./gradlew :composeApp:desktopTest --rerun --tests 'app.nostrdeck.store.StoreScreenshots'
STORE_SHOTS=en ./gradlew :composeApp:desktopTest --rerun --tests 'app.nostrdeck.store.StoreScreenshots'
```

```bash
# 3. コピーと背景を付けてストア用の画像にする（Chrome のヘッドレスで HTML を撮る。約 2 分）
node distribution/store/scripts/build-store-images.mjs ja en
```

```bash
# 4. 掲載文の文字数を確かめる
node distribution/store/scripts/check-listing.mjs
```

しくみ:
- デモ用のユーザーと投稿は `composeApp/src/desktopTest/kotlin/app/nostrdeck/store/DemoWorld.kt` にある。決まった種から作った鍵で正しく署名したイベントを、リレーから届いたときと同じ取り込み処理に流している
- 画像は手元の HTTP サーバー（127.0.0.1:18765）から配る。画像プロキシは手元のアドレスを素通しにしている
- リポジトリは `offline = true` で作り、リレーにはつながない
- 端末ごとの画面サイズと密度、場面の一覧は `StoreScreenshots.kt` にある。`STORE_VIEWPORTS=iphone,ipad` で端末を絞れる
- コピー・背景の色・枠の一覧は `scripts/build-store-images.mjs` の先頭にある。`--only <枠>` で 1 つの枠だけ作り直せる

## App Store Connect へ登録する（コマンド）

掲載文と画像は App Store Connect API で登録できる（`scripts/asc.mjs`）。認証は TestFlight の配信と同じ環境変数（`ASC_KEY_ID` / `ASC_ISSUER_ID` / `ASC_KEY_PATH`。Admin ロールの API キー）。
審査への提出はしない。

```bash
node distribution/store/scripts/asc.mjs status
node distribution/store/scripts/asc.mjs metadata          # 名前・サブタイトル・説明・キーワード・プロモーションテキスト・URL（日英）とカテゴリ
node distribution/store/scripts/asc.mjs screenshots       # iPhone 6.9" / 6.3" と iPad 13"（日英）。既存があればスキップ、差し替えは --replace
```

どちらも `--dry-run` で送る内容だけを確かめられる。
API でできないもの（コンソールの画面で行う）: App のプライバシー、iPhone Duo の画像（API にまだ枠の種類が無い）、規約・税・口座。

## Google Play へ登録する（ワークフロー）

Play の認証は GitHub Actions の中にしかないので、掲載情報の登録はワークフロー `store-listing.yml` で行う（`scripts/play-listing.mjs`）。
文言は `listing.md` の Google Play の節、画像は `store-assets` ブランチの `play/` から取る（画像は生成物なので main には入れない）。
既定は確定と同時に審査へ送る。「管理対象の公開」がオンなので、承認後に Play Console の「公開の概要」で「変更を公開」を押すまで掲載には反映されない。

```bash
gh workflow run store-listing.yml --ref main -f dry_run=true    # 送る内容だけを確かめる
gh workflow run store-listing.yml --ref main -f dry_run=false   # 登録して審査に送る（承認後に「変更を公開」で反映）
gh workflow run store-listing.yml --ref main -f dry_run=false -f send_for_review=false   # 審査に送らずに置いておく
```

`send_for_review=false` は管理対象の公開がオンのときだけ使える。オフのアプリでは確定せずに止まる。

画像を作り直したら、`out/play/` の中身（foldable を除く）を `store-assets` ブランチの `play/` へ入れてコミットする。
サービスアカウントに Play Console の「ストアでの表示の管理」の権限が要る。

## 提出前チェックリスト

### 両ストア共通
- [ ] **アカウント削除の機能を入れる（要実装）**。下の「審査リスク」を参照
- [ ] 掲載文を [listing.md](listing.md) から貼る（日本語と英語）
- [ ] プライバシーポリシーの URL が開けることを確かめる（https://nostrism.shino3.net/privacy-policy.html）
- [ ] 旧素材 `docs/store/screenshot-*.png` と `feature-1024x500.png` には実在のユーザーが写っているので、コンソールに残っていれば今回の画像に差し替える

### App Store Connect
- [ ] スクリーンショット: 6.9"・6.3"・iPad 13"・iPhone Duo（内側・外側）を日本語と英語で登録
- [ ] 名前・サブタイトル・プロモーションテキスト・説明・キーワード（[listing.md](listing.md)）
- [ ] サポート URL・マーケティング URL・プライバシーポリシー URL
- [ ] カテゴリ: ソーシャルネットワーキング
- [ ] 著作権表示（例: `2026 <Apple Developer の登録名>`）
- [ ] 年齢区分の質問票: 利用者が作るコンテンツ（あり）・メッセージとチャット（あり）・広告（なし）に正直に答える。投稿は中央で管理されないため、高めの区分になる見込み
- [ ] App のプライバシー（栄養ラベル）: 下の「データの扱いの回答」を参照
- [ ] 輸出コンプライアンス: Info.plist で `ITSAppUsesNonExemptEncryption = false` にしている。DM に NIP-44 の暗号を使っているので、この回答でよいか一度確かめる
- [ ] App Review への情報: 下の「審査メモ」を貼る。連絡先の氏名・電話番号・メール
- [ ] 価格と配信地域: 無料・全地域
- [ ] Info.plist の利用目的の文（写真ライブラリへの保存・ローカルネットワーク）が日本語だけ。英語の審査員にも読めるよう、英語版（InfoPlist.strings）を足すことを勧める

### Google Play Console
- [ ] スクリーンショット: スマートフォン・7 インチ・10 インチを日本語と英語（en-US）で登録
- [ ] フィーチャーグラフィックと代替テキスト、アイコン（`out/play/icon-512.png` は今のランチャーアイコンと同じ絵柄。`docs/store/icon-512.png` は古い絵柄なので使わない）
- [ ] アプリ名・簡単な説明・詳しい説明（[listing.md](listing.md)）
- [ ] データ セーフティ: 下の「データの扱いの回答」を参照。クローズドテストで出した回答と食い違わないようにする
- [ ] コンテンツのレーティング（IARC）: ソーシャル / コミュニケーションとして、ユーザー同士の交流ありで答える
- [ ] ターゲット ユーザー: 18 歳以上を推奨（13 歳未満は対象外とプライバシーポリシーに書いてある。13〜17 歳を含めると追加の要件がかかる）
- [ ] 広告: なし
- [ ] アプリのアクセス権: 制限なし（アプリ内で鍵を作れば全機能を使える）
- [ ] 金融機能の申告: Zap はウォレット接続（NIP-47）で外部のウォレットに支払いを頼むだけで、アプリは資金を預からない。この前提で答える
- [ ] 児童の安全に関する基準: https://nostrism.shino3.net/child-safety.html
- [ ] `docs/.well-known/assetlinks.json` の本番の署名鍵の SHA-256（Issue の公開前チェックリストに残っている項目）
- [ ] 製品版トラックの国と地域

## 審査メモ（App Review / Play のアプリのアクセス権に貼る）

```text
Nostrism is a client for Nostr, an open, decentralized social protocol. There is no account on our servers.

How to try the app:
1. On the first screen, choose "Private key" and tap "Generate new key".
2. The app connects to public Nostr relays. Use Search to find and follow accounts, or open the Global/hashtag columns.

User-generated content:
- Users can mute users and words, and report posts (NIP-56) from the "..." menu on every post.
- Posts marked with a content warning are collapsed until tapped.
- Contact: tshinoharawork@gmail.com

Account deletion:
- (アカウント削除を実装したら、ここに手順を書く。例: Settings > Account > Delete account)
```

新しい鍵ではタイムラインが空に近いので、審査用にフォローの多いデモ用アカウントを用意して、その鍵（nsec）を審査用のログイン情報の欄に入れるとよい。

## データの扱いの回答（推奨）

プライバシーポリシーの内容に合わせた回答。
「開発者が運営する既定のリレー（日本語の端末のみ）」と「文字から絵文字を作るサーバー」があるので、「収集しない」ではなく、保守的に申告する。

**App Store（App のプライバシー）**
- トラッキング: なし
- ユーザに関連付けられたデータ:
  - ユーザコンテンツ > その他のユーザコンテンツ（投稿・リアクション。開発者のリレーに保存されうる）: App の機能
  - ID > ユーザ ID（公開鍵）: App の機能
  - ユーザコンテンツ > 写真またはビデオ（利用者が選んだアップロード先に送る）: App の機能

**Google Play（データ セーフティ）**
- 収集するデータ: 「その他のユーザー作成コンテンツ」「写真」「動画」「ユーザー ID」。目的は「アプリの機能」。任意（利用者が投稿したときだけ）
- 共有: なし（利用者の操作で選んだリレーやアップロード先に送るのは、共有の例外にあたる）
- 転送時の暗号化: はい（wss / https）
- 削除の依頼: アカウント削除を実装したあと、その手順を書いた Web ページの URL を登録する

## 審査リスク

**アカウント削除がない（両ストア・最優先）。**
アプリ内で新しい鍵を作れる（＝アカウントを作れる）が、アカウントを削除する手段がない。
App Store の審査ガイドライン 5.1.1(v) は、アカウントを作れるアプリにアプリ内での削除を求めている。
Google Play も、アカウントを作れるアプリにアプリ内と Web の両方で削除の依頼手段を求めている。
いまある「ログアウト」は端末から鍵を消すだけで、削除の要件を満たすとは言いにくい。
案: 設定に「アカウントを削除」を足し、確認のうえで (1) 削除の依頼（NIP-62 の Request to Vanish）を書き込み先のリレーに送り、(2) プロフィールを空にし、(3) 端末から鍵とデータを消す。
あわせて、手順を書いた Web ページ（例: `docs/account-deletion.html`）を用意して Play に登録する。

**投稿への Zap（App Store）。**
2023 年に Damus が、投稿への Zap を「デジタルコンテンツに結びついた支払い」とされ、ガイドライン 3.1.1 で指摘を受けた前例がある。
指摘を受けた場合は、iOS 版だけ投稿への Zap を隠してプロフィールへの Zap に限る、という対応が考えられる。

**利用者が作るコンテンツ（App Store 1.2）。**
通報・ミュート・内容の警告の折りたたみ・連絡先はそろっている。審査メモでその場所を説明しておく。
