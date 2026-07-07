# Nosskey（パスキー / WebAuthn PRF）セットアップ

パスキーで nsec を保護する Nosskey は、WebAuthn の仕様上 **アプリと RP ドメインの関連付け（Digital Asset Links）** が必須です。これが無いと Credential Manager がパスキー作成を拒否します。

## RP ドメイン
`NosskeyBridge.RP_ID = "nostrism.shino3.net"`

## 必要な作業（ユーザー側）
1. `docs/.well-known/assetlinks.json` を **`https://nostrism.shino3.net/.well-known/assetlinks.json`** で配信する
   （Cloudflare Pages で docs/ を配信しているなら `.well-known/assetlinks.json` として公開されるよう配置）。
2. `net.shino3.nostrism`（release）の指紋を **Play Console → アプリの完全性 → アプリ署名鍵証明書の SHA-256** に置き換える
   （現在は `REPLACE_WITH_PLAY_APP_SIGNING_SHA256` のプレースホルダ）。
3. debug ビルド（`net.shino3.nostrism.debug`）の指紋は登録済み：
   `B5:19:32:48:DE:38:5E:60:92:B5:95:BC:CD:AA:06:EB:B6:B6:BB:6C:AB:12:F0:8B:B3:82:D5:61:F8:9B:0A:B0`

## 動作要件（端末）
- Google Play 開発者サービス + パスキープロバイダ（Google パスワードマネージャー）
- 画面ロック（生体認証 or PIN）設定済み
- **WebAuthn PRF 拡張対応**のプロバイダ（比較的新しい Play サービス）

## 確認手順
設定 → ログイン方法 → **ローカル鍵の状態で**「パスキーで保護する」→ パスキー作成 → 生体認証。
再起動後は「パスキーで解錠」で復号。assetlinks 未配信だと作成時にドメインエラーになる。
