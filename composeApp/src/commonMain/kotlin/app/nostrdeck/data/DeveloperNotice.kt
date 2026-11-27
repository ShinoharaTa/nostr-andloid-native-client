package app.nostrdeck.data

import app.nostrdeck.crypto.Nip19

/**
 * [#961] 開発者への非公開の知らせ（App Store のガイドライン 1.2: ブロックしたら開発者にも知らせる。#951）。
 *
 * 公開の通報（kind:1984）は他のクライアントと同じく自分のリレーへだけ出し（ブロックでは出さない）、開発者には
 * NIP-17 の暗号化した DM でブロック・通報を知らせる。ブロックされた人・通報された人からは見えない
 * （以前は開発者のリレーに公開の通報を置いていて、ブロックされた側が誰にブロックされたかを引けた）。
 * NIP-44 を使えない署名者では送らない（2026-11-05 決定）。文面は Web の developerNotice と同じ。
 */
object DeveloperNotice {
    /** 受け取る開発者の公開鍵（npub1l60d6h2uvdwa9yq0r7r2suhgrnsadcst6nsx2j03xwhxhu2cjyascejxe5）。 */
    const val PUBKEY = "fe9edd5d5c635dd2900f1f86a872e81ce1d6e20bd4e06549f133ae6bf158913b"

    /**
     * 開発者の DM リレー（kind:10050）に加えて必ず送るリレー。DM リレーが取れないときの受け皿も兼ねる。
     * gift wrap は中身も送り手（使い捨ての鍵）も見えないので、誰でも読めるリレーに置いてよい。
     */
    const val FALLBACK_RELAY = "wss://relay-jp.shino3.net"

    /** ブロックの知らせ。[noteId] は投稿の ⋯ からブロックしたときの、その投稿。 */
    fun block(target: String, noteId: String?): String = buildString {
        append("[Nostrism] block")
        append("\nuser: nostr:").append(Nip19.hexToNpub(target))
        if (noteId != null) append("\npost: nostr:").append(Nip19.hexToNote(noteId))
    }

    /** 通報の知らせ。[type] は NIP-56 の種類（spam など）。[noteId] は投稿の通報のときの、その投稿。 */
    fun report(target: String, noteId: String?, type: String, reason: String = ""): String = buildString {
        append("[Nostrism] report: ").append(type)
        append("\nuser: nostr:").append(Nip19.hexToNpub(target))
        if (noteId != null) append("\npost: nostr:").append(Nip19.hexToNote(noteId))
        if (reason.isNotBlank()) append("\nreason: ").append(reason)
    }
}
