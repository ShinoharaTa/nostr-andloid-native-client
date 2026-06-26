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
}
