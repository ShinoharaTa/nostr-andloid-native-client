package app.nostrdeck.model

import app.nostrdeck.crypto.Nip19
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** [#578] PROFILE カラムの入力（npub / nprofile / hex）を authors 用の hex に直す。 */
class ProfileColumnInputTest {

    private val hex = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
    private val npub = Nip19.hexToNpub(hex)

    // NIP-19 仕様の nprofile 例（リレーヒント 2 件付き。pubkey は上の hex）
    private val nprofileSpec = "nprofile1qqsrhuxx8l9ex335q7he0f09aej04zpazpl0ne2cgukyawd24mayt8gpp4mhxue69uhhytnc9e3k7mgpz4mhxue69uhkg6nzv9ejuumpv34kytnrdaksjlyr9p"

    @Test
    fun npubをhexに直す() {
        assertEquals(hex, profileInputToHex(npub))
        assertEquals(hex, profileInputToHex("  $npub  "))
    }

    @Test
    fun nprofileはそのpubkeyを使う() {
        assertEquals(hex, profileInputToHex(nprofileSpec))
        assertEquals(hex, profileInputToHex(Nip19.hexToNprofile(hex, listOf("wss://relay.example.com"))))
    }

    @Test
    fun nostr接頭辞付きも受け付ける() {
        assertEquals(hex, profileInputToHex("nostr:$npub"))
        assertEquals(hex, profileInputToHex("nostr:$nprofileSpec"))
        assertEquals(hex, profileInputToHex("nostr:$hex"))
    }

    @Test
    fun hexはそのまま_大文字は小文字にそろえる() {
        assertEquals(hex, profileInputToHex(hex))
        assertEquals(hex, profileInputToHex(hex.uppercase()))
    }

    @Test
    fun 読めない入力はnull() {
        assertNull(profileInputToHex(""))
        assertNull(profileInputToHex("nostr:"))
        assertNull(profileInputToHex("jack"))
        assertNull(profileInputToHex(hex.dropLast(1)))                   // 63 桁
        assertNull(profileInputToHex("g" + hex.drop(1)))                 // hex でない文字
        assertNull(profileInputToHex(npub.dropLast(1) + (if (npub.last() == 'q') 'p' else 'q')))   // チェックサム不一致
        assertNull(profileInputToHex(Nip19.hexToNsec(hex)))              // 秘密鍵は受け付けない
        assertNull(profileInputToHex(Nip19.hexToNote(hex)))              // note は pubkey ではない
    }

    @Test
    fun PROFILEテンプレはauthorsにhexを入れる() {
        val spec = ColumnTemplate.PROFILE.build(input = "nostr:$npub")
        assertEquals(listOf(hex), spec.filter.authors)
        assertEquals(ColumnKind.PROFILE, spec.kind)
        assertFalse(spec.title.startsWith("nostr:"))
        // 編集ダイアログには npub に戻して出す
        assertEquals(npub, spec.editText())
    }

    @Test
    fun 追加ボタンはPROFILEだけpubkeyとして読めるときに有効() {
        assertTrue(ColumnTemplate.PROFILE.acceptsInput(npub))
        assertTrue(ColumnTemplate.PROFILE.acceptsInput(hex))
        assertFalse(ColumnTemplate.PROFILE.acceptsInput("jack"))
        assertFalse(ColumnTemplate.PROFILE.acceptsInput(""))
        assertTrue(ColumnTemplate.HASHTAG.acceptsInput("nostr"))
        assertFalse(ColumnTemplate.HASHTAG.acceptsInput("  "))
        assertTrue(ColumnTemplate.FOLLOWING.acceptsInput(""))
    }

    @Test
    fun 保存済みのnpubのPROFILEカラムを読み込み時にhexへ直す() {
        val broken = ColumnSpec("c1", "npub1…", "profile", ColumnKind.PROFILE, ColumnRenderer.FEED,
            ReqFilter(kinds = listOf(1), authors = listOf(npub)))
        assertEquals(listOf(hex), broken.withHexProfileAuthors().filter.authors)

        // 正常なもの・PROFILE 以外・直せない値は変えない
        val ok = broken.copy(filter = broken.filter.copy(authors = listOf(hex)))
        assertSame(ok, ok.withHexProfileAuthors())
        val list = broken.copy(kind = ColumnKind.LIST)
        assertSame(list, list.withHexProfileAuthors())
        val junk = broken.copy(filter = broken.filter.copy(authors = listOf("jack")))
        assertEquals(listOf("jack"), junk.withHexProfileAuthors().filter.authors)
    }
}
