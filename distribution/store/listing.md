# ストア掲載文（App Store / Google Play・日本語 / 英語）

各欄はコンソールにそのまま貼れるよう、コードブロックに入れてある。
文字数の上限は欄名に書いた。`node distribution/store/scripts/check-listing.mjs` で上限を超えていないか確かめられる。

表現の規則（両ストア共通）: 「無料」「No.1」「最高」などの順位・価格・受賞の言葉と、「今すぐダウンロード」などの行動喚起は使わない。
他社の名前・ロゴは出さない（Nostr はプロトコル名なので使ってよい）。

---

## App Store（日本語）

### 名前（30）
```text
Nostrism
```

### サブタイトル（30）
```text
タイムラインを並べる Nostr クライアント
```

### プロモーションテキスト（170）
審査なしでいつでも差し替えられる欄。新機能の告知に使う。
```text
リアクションへのリアクションも、スレッドでツリー表示に。リアクションの通知を押すと元の投稿が開いて、相手の行が光ります。
```

### キーワード（100）
アプリ名とカテゴリ名は自動で検索対象になるので入れない。区切りはカンマのみ（空白を入れない）。
```text
nostr,ノストル,分散型,SNS,タイムライン,デッキ,リレー,カスタム絵文字,パブリックチャット,zap,ステータス,テーマ
```

### 説明（4000）
```text
Nostrism は、分散型 SNS のプロトコル「Nostr」のクライアントです。
フォロー中のタイムライン、ハッシュタグ、パブリックチャット、通知を、見やすく、心地よく。

■ タイムラインを並べて一望
iPad や折りたたみの iPhone の広い画面では、フォロー中・ハッシュタグ・チャット・通知などのカラムを横に並べた「デッキ」表示になります。iPhone では 1 カラムで、下のナビゲーションから切り替えます。カラムは追加・並べ替えができます。

■ 誰が、何に反応したかがわかる通知
返信・メンション・リアクション・リポスト・Zap をまとめて確認できます。リアクションの通知を押すと、元の投稿のスレッドが開いて相手の行が光ります。

■ リアクションもスレッドでつながる
スレッドでは、リアクションへのリアクションやリポストが、元の絵文字の下にツリーで表示されます。

■ カスタム絵文字
カスタム絵文字でリアクションしたり、投稿に使ったりできます。文字から絵文字を作って、自分の絵文字リストに加えることもできます。

■ パブリックチャット
パブリックチャット（NIP-28）に参加して、ゆるく会話できます。

■ ステータス
フォロー中の人がいま何をしているか、何を聴いているかが、プロフィールやステータスのカラムでわかります（NIP-38）。

■ 好きな色に着せ替え
背景・文字・アクセントの 3 色を選ぶだけで、自分だけのテーマを作れます。ほかの人が公開したテーマを使うこともできます。

■ そのほかの機能
・写真・動画の投稿（NIP-96 / Blossom。画像は送信前に位置情報などのメタデータを取り除きます）
・暗号化されたダイレクトメッセージ（NIP-17）
・Zap（NIP-57）と、ウォレット接続（NIP-47）による送金（毎回確認します）
・キーワード検索（NIP-50）
・ブックマーク、固定投稿、長文記事（NIP-23）の閲覧
・ミュート（ワード / ユーザー）、通報（NIP-56）、内容の警告が付いた投稿の折りたたみ
・リレーの設定（NIP-65）
・キーボードショートカット
・ログイン方法: 秘密鍵（端末の安全な領域に保存）、リモート署名（NIP-46）
・日本語 / 英語

■ はじめての方へ
アカウントの登録は不要です。アプリ内で新しい鍵を作るだけで始められます。鍵は端末の中にだけ保存され、開発者のサーバーには送られません。鍵をなくすとアカウントを取り戻せないため、作ったら必ず控えを保管してください。

■ プライバシー
本アプリは解析ツール・広告・トラッキングを使いません。投稿などの公開イベントは、利用者が設定したリレーに送られて公開されます。詳しくはプライバシーポリシーをご覧ください。
```

---

## App Store（English）

### Name (30)
```text
Nostrism
```

### Subtitle (30)
```text
A comfortable Nostr client
```

### Promotional Text (170)
```text
Reactions to reactions now show up as a tree in threads. Tap a reaction notification to open the original post with that reaction highlighted.
```

### Keywords (100)
```text
nostr,decentralized,social,timeline,deck,relay,custom emoji,public chat,zap,status,theme
```

### Description (4000)
```text
Nostrism is a client for Nostr, the decentralized social protocol.
Your timeline, hashtags, public chats and notifications, clean and comfortable.

ALL YOUR TIMELINES, SIDE BY SIDE
On iPad and the larger screen of a foldable iPhone, Nostrism becomes a deck: Following, hashtags, chats and notifications sit next to each other in columns. On iPhone you get a single column and switch with the bottom navigation. Add and reorder columns as you like.

NOTIFICATIONS THAT MAKE SENSE
See replies, mentions, reactions, reposts and Zaps in one place. Tap a reaction notification to open the original thread with that person highlighted.

REACTIONS, THREADED
In a thread, reactions to reactions and reposts appear as a tree under the original emoji.

CUSTOM EMOJI
React and post with custom emoji. You can even make your own emoji from text and add them to your emoji list.

PUBLIC CHATS
Join public chats (NIP-28) and hang out.

STATUS
See what people you follow are up to, or listening to, on their profiles and in the status column (NIP-38).

MAKE IT YOURS
Pick three colors (background, text and accent) to build your own theme, or use themes others have shared.

AND MORE
- Photo and video posts (NIP-96 / Blossom); location and other metadata are removed from images before upload
- Encrypted direct messages (NIP-17)
- Zaps (NIP-57) and payments through a connected wallet (NIP-47), confirmed every time
- Keyword search (NIP-50)
- Bookmarks, pinned posts and long-form articles (NIP-23)
- Mute words and users, report (NIP-56), and collapsed posts with content warnings
- Relay settings (NIP-65)
- Keyboard shortcuts
- Sign in with a private key (kept in the device's secure storage) or a remote signer (NIP-46)
- English and Japanese

GETTING STARTED
No sign-up needed. Just create a new key in the app. Your key stays on your device and is never sent to our servers. If you lose your key, you lose the account, so keep a backup as soon as you create one.

PRIVACY
Nostrism has no analytics, ads or tracking. Public events such as posts are sent to the relays you choose and are public. See the privacy policy for details.
```

---

## Google Play（日本語）

### アプリ名（30）
```text
Nostrism
```

### 簡単な説明（80）
```text
タイムラインを並べて一望できる、心地よい Nostr クライアント。通知もリアクションも見やすく。
```

### 詳しい説明（4000）
```text
Nostrism は、分散型 SNS のプロトコル「Nostr」のクライアントです。
フォロー中のタイムライン、ハッシュタグ、パブリックチャット、通知を、見やすく、心地よく。

■ タイムラインを並べて一望
タブレットや折りたたみスマホの広い画面では、フォロー中・ハッシュタグ・チャット・通知などのカラムを横に並べた「デッキ」表示になります。折りたたみスマホは、閉じればスマホ表示、開けばデッキ表示に切り替わります。カラムは追加・並べ替えができます。

■ 誰が、何に反応したかがわかる通知
返信・メンション・リアクション・リポスト・Zap をまとめて確認できます。リアクションの通知を押すと、元の投稿のスレッドが開いて相手の行が光ります。

■ リアクションもスレッドでつながる
スレッドでは、リアクションへのリアクションやリポストが、元の絵文字の下にツリーで表示されます。

■ カスタム絵文字
カスタム絵文字でリアクションしたり、投稿に使ったりできます。文字から絵文字を作って、自分の絵文字リストに加えることもできます。

■ パブリックチャット
パブリックチャット（NIP-28）に参加して、ゆるく会話できます。

■ ステータス
フォロー中の人がいま何をしているか、何を聴いているかが、プロフィールやステータスのカラムでわかります（NIP-38）。

■ 好きな色に着せ替え
背景・文字・アクセントの 3 色を選ぶだけで、自分だけのテーマを作れます。ほかの人が公開したテーマを使うこともできます。

■ そのほかの機能
・写真・動画の投稿（NIP-96 / Blossom。画像は送信前に位置情報などのメタデータを取り除きます）
・暗号化されたダイレクトメッセージ（NIP-17）
・Zap（NIP-57）と、ウォレット接続（NIP-47）による送金（毎回確認します）
・キーワード検索（NIP-50）
・ブックマーク、固定投稿、長文記事（NIP-23）の閲覧
・ミュート（ワード / ユーザー）、通報（NIP-56）、内容の警告が付いた投稿の折りたたみ
・リレーの設定（NIP-65）
・キーボードショートカット
・ログイン方法: 秘密鍵（端末の安全な領域に保存）、外部署名アプリ（NIP-55）、リモート署名（NIP-46）
・日本語 / 英語

■ はじめての方へ
アカウントの登録は不要です。アプリ内で新しい鍵を作るだけで始められます。鍵は端末の中にだけ保存され、開発者のサーバーには送られません。鍵をなくすとアカウントを取り戻せないため、作ったら必ず控えを保管してください。

■ プライバシー
本アプリは解析ツール・広告・トラッキングを使いません。投稿などの公開イベントは、利用者が設定したリレーに送られて公開されます。詳しくはプライバシーポリシーをご覧ください。
```

---

## Google Play（English, en-US）

### App name (30)
```text
Nostrism
```

### Short description (80)
```text
A comfortable Nostr client with side-by-side timelines and clear notifications.
```

### Full description (4000)
```text
Nostrism is a client for Nostr, the decentralized social protocol.
Your timeline, hashtags, public chats and notifications, clean and comfortable.

ALL YOUR TIMELINES, SIDE BY SIDE
On tablets and foldables, Nostrism becomes a deck: Following, hashtags, chats and notifications sit next to each other in columns. Fold your phone and you get the phone layout; unfold it and you get the deck. Add and reorder columns as you like.

NOTIFICATIONS THAT MAKE SENSE
See replies, mentions, reactions, reposts and Zaps in one place. Tap a reaction notification to open the original thread with that person highlighted.

REACTIONS, THREADED
In a thread, reactions to reactions and reposts appear as a tree under the original emoji.

CUSTOM EMOJI
React and post with custom emoji. You can even make your own emoji from text and add them to your emoji list.

PUBLIC CHATS
Join public chats (NIP-28) and hang out.

STATUS
See what people you follow are up to, or listening to, on their profiles and in the status column (NIP-38).

MAKE IT YOURS
Pick three colors (background, text and accent) to build your own theme, or use themes others have shared.

AND MORE
- Photo and video posts (NIP-96 / Blossom); location and other metadata are removed from images before upload
- Encrypted direct messages (NIP-17)
- Zaps (NIP-57) and payments through a connected wallet (NIP-47), confirmed every time
- Keyword search (NIP-50)
- Bookmarks, pinned posts and long-form articles (NIP-23)
- Mute words and users, report (NIP-56), and collapsed posts with content warnings
- Relay settings (NIP-65)
- Keyboard shortcuts
- Sign in with a private key (kept in the device's secure storage), an external signer app (NIP-55) or a remote signer (NIP-46)
- English and Japanese

GETTING STARTED
No sign-up needed. Just create a new key in the app. Your key stays on your device and is never sent to our servers. If you lose your key, you lose the account, so keep a backup as soon as you create one.

PRIVACY
Nostrism has no analytics, ads or tracking. Public events such as posts are sent to the relays you choose and are public. See the privacy policy for details.
```

---

## フィーチャーグラフィックの代替テキスト（140）
`out/play/<lang>/feature-alt.txt` と同じ内容。

```text
Nostrism のタイムラインとスレッドの画面。写真つきの投稿とリアクションの木が並ぶ
```

```text
Nostrism timeline and thread screens showing photo posts and threaded reactions
```

## 共通の情報

| 欄 | 値 |
|---|---|
| パッケージ名 / Bundle ID | `net.shino3.nostrism` |
| カテゴリ | App Store: ソーシャルネットワーキング / Play: ソーシャル |
| 価格 | 無料・広告なし・アプリ内課金なし |
| サポート URL | https://nostrism.shino3.net/ （GitHub の Issue へのリンクあり） |
| マーケティング URL | https://nostrism.shino3.net/ |
| プライバシーポリシー | https://nostrism.shino3.net/privacy-policy.html |
| 児童の安全に関する基準（Play） | https://nostrism.shino3.net/child-safety.html |
| 連絡先メール | tshinoharawork@gmail.com |
