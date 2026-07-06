# プライバシーポリシー / Privacy Policy — Nostrism

最終更新 / Last updated: 2026-07-06

---

## 日本語

**Nostrism**（以下「本アプリ」）は、分散型プロトコル [Nostr](https://nostr.com/) のクライアントアプリです。本アプリは、開発者が運営するサーバーを持たず、利用者の個人情報を開発者側で収集・保存しません。

### 1. 開発者が収集する情報
**ありません。** 本アプリは解析ツール・広告・トラッキングSDKを一切使用せず、開発者のサーバーへ利用者のデータを送信しません。

### 2. 端末内に保存される情報
以下は**利用者の端末内にのみ**保存されます（開発者は参照できません）。
- 秘密鍵（nsec）およびアカウント情報
- 取得したイベント（投稿等）のキャッシュ
- アプリの設定（リレー一覧、表示設定 等）

秘密鍵は署名・暗号化のために端末内で使用されます。アプリのデータ削除・アンインストールで消去されます。

### 3. 第三者へ送信される情報
Nostr は公開プロトコルです。利用者の操作により、以下が**利用者が選んだ第三者**へ送信されます。
- **リレー**：投稿・リアクション・フォロー等の公開イベントは、利用者が設定したリレーへ送信され、公開されます。ダイレクトメッセージは暗号化（NIP-17/NIP-44）されますが、送受信のメタデータはリレーを経由します。
- **メディアサーバー**：画像等をアップロードする場合、利用者が選んだサーバー（NIP-96/Blossom）へ送信されます。
- **画像プロキシ（wsrv.nl）**：タイムライン上の画像は、表示最適化のため公開画像プロキシ `wsrv.nl` を経由して読み込まれます。この際、画像URLと利用者のIPアドレスが当該プロキシに送信されます。設定で自前のプロキシへ変更可能です。

これら第三者はそれぞれ独自のプライバシーポリシーに従います。開発者はこれらのサービスを管理していません。

### 4. 子どものプライバシー
本アプリは13歳未満の子どもを対象としていません。

### 5. 本ポリシーの変更
本ポリシーは予告なく変更されることがあります。変更時は本ページの「最終更新」日を更新します。

### 6. お問い合わせ
本アプリ・本ポリシーに関するお問い合わせは、リポジトリの Issue または以下までご連絡ください。
- 連絡先: `<ここに公開用の連絡先メールを記載>`
- リポジトリ: https://github.com/ShinoharaTa/nostr-andloid-native-client

---

## English

**Nostrism** ("the App") is a client for the decentralized [Nostr](https://nostr.com/) protocol. The App has no developer-operated backend and the developer does **not** collect or store your personal data.

### 1. Information collected by the developer
**None.** The App uses no analytics, ads, or tracking SDKs, and sends no user data to any developer-operated server.

### 2. Information stored on your device
The following are stored **only on your device** (the developer cannot access them):
- Your private key (nsec) and account information
- Cached events (posts, etc.)
- App settings (relay list, display preferences, etc.)

Your private key is used on-device for signing and encryption. It is removed when you clear the app data or uninstall.

### 3. Information sent to third parties
Nostr is a public protocol. By your actions, data is sent to **third parties you choose**:
- **Relays**: Public events (posts, reactions, follows) are published to the relays you configure. Direct messages are encrypted (NIP-17/NIP-44), but delivery metadata passes through relays.
- **Media servers**: If you upload media, it is sent to the server you choose (NIP-96/Blossom).
- **Image proxy (wsrv.nl)**: Images in the timeline are loaded through the public image proxy `wsrv.nl` for optimization; the image URL and your IP address are sent to it. You can change this to your own proxy in settings.

These third parties operate under their own privacy policies. The developer does not control them.

### 4. Children's privacy
The App is not directed to children under 13.

### 5. Changes
This policy may change; the "Last updated" date will be revised accordingly.

### 6. Contact
- Contact: `<your public contact email here>`
- Repository: https://github.com/ShinoharaTa/nostr-andloid-native-client
