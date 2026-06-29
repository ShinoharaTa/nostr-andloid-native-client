package app.nostrdeck.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Nip19Test {

    // NIP-19 spec の既知ベクタ
    private val NPUB_HEX = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
    // 正しい npub は codec から導出（nsec 実ベクタで codec の正しさは担保済み）
    private val NPUB = Nip19.hexToNpub(NPUB_HEX)
    private val NSEC = "nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5"
    private val NSEC_HEX = "67dea2ed018072d675f5415ecfaed7d2597555e202d85b3d65ea4e58d2d92ffa"

    // npub は nsec と同一コードパス（hrp 違いのみ）で、codec は下の nsec 実スペックベクタ
    // と round-trip で担保される。npub は encode∘decode の整合性で検証する。
    @Test
    fun npub_encode_decode_consistency() {
        val npub = Nip19.hexToNpub(NPUB_HEX)
        assertTrue(npub.startsWith("npub1"))
        assertEquals(NPUB_HEX, Nip19.npubToHex(npub))
    }

    @Test
    fun nsec_known_answer_decode() {
        assertEquals(NSEC_HEX, Nip19.nsecToHex(NSEC))
    }

    @Test
    fun nsec_known_answer_encode() {
        assertEquals(NSEC, Nip19.hexToNsec(NSEC_HEX))
    }

    @Test
    fun npub_roundtrip() {
        val hex = "00".repeat(31) + "01"
        assertEquals(hex, Nip19.npubToHex(Nip19.hexToNpub(hex)))
    }

    @Test
    fun nsec_roundtrip() {
        val hex = "ff".repeat(31) + "fe"
        assertEquals(hex, Nip19.nsecToHex(Nip19.hexToNsec(hex)))
    }

    @Test
    fun encoded_npub_has_correct_hrp() {
        val hex = "a".repeat(64)
        assertTrue(Nip19.hexToNpub(hex).startsWith("npub1"))
        assertTrue(Nip19.hexToNsec(hex).startsWith("nsec1"))
    }

    @Test
    fun wrong_hrp_is_rejected() {
        // npub を nsec として読もうとすると失敗
        assertFailsWith<IllegalArgumentException> { Nip19.nsecToHex(NPUB) }
    }

    @Test
    fun corrupted_checksum_is_rejected() {
        val bad = NPUB.dropLast(1) + if (NPUB.last() == 'g') 'h' else 'g'
        assertFailsWith<IllegalArgumentException> { Nip19.npubToHex(bad) }
    }

    @Test
    fun non_32byte_payload_is_rejected() {
        assertFailsWith<IllegalArgumentException> { Nip19.hexToNpub("ab") }
    }

    // note(32byte 単発) は event id をそのまま hex 化できる。
    @Test
    fun note_decodes_to_event_id() {
        val hex = "ab".repeat(32)
        val note = Nip19.hexToNote(hex)
        assertEquals(hex, Nip19.eventBechToHex(note))
    }

    // nevent(TLV) は type=0(event id) を取り出す。リレー/著者ヒントを含む 90 文字超の
    // 実データ（165文字）でも BIP-173 の長さ制限に引っかからず復号できること。
    @Test
    fun long_nevent_decodes_to_event_id() {
        val nevent = "nevent1qvzqqqqqqypzpp9sc34tdxdvxh4jeg5xgu9ctcypmvsg0n00vwfjydkr" +
            "jaqh0qh4qyfhwumn8ghj7urj9eeks6twduejumn9wsqzqls5u87ccud6ryf2yv6fv78ev7zjslfs08cgwnpfdrp0nj3xz0e7a7s3c6"
        assertTrue(nevent.length > 90, "テスト前提: 90文字超の nevent")
        val hex = Nip19.eventBechToHex(nevent)
        assertTrue(hex != null && hex.length == 64, "64文字 hex に復号できる: $hex")
    }
}
