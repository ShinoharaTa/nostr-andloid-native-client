package app.nostrdeck.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [#872] アップロード先の方式と、Blossom（BUD-01 / BUD-02）の認証・記述子の扱い。 */
class UploadProtocolTest {
    @Test
    fun protocol_is_read_from_the_stored_id_and_defaults_to_nip96() {
        assertEquals(UploadProtocol.BLOSSOM, UploadProtocol.of("blossom"))
        assertEquals(UploadProtocol.NIP96, UploadProtocol.of("nip96"))
        assertEquals(UploadProtocol.NIP96, UploadProtocol.of(null))
        assertEquals(UploadProtocol.NIP96, UploadProtocol.of("something-new"))
    }

    @Test
    fun upload_auth_is_kind_24242_with_upload_hash_and_expiration() {
        val e = Blossom.uploadAuth("ab".repeat(32), "photo.webp", now = 1_000)
        assertEquals(24242, e.kind)
        assertEquals(1_000, e.createdAt)
        assertEquals("Upload photo.webp", e.content)
        assertEquals(
            listOf(listOf("t", "upload"), listOf("x", "ab".repeat(32)), listOf("expiration", "1300")),
            e.tags,
        )
    }

    @Test
    fun authorization_header_is_base64url_without_padding() {
        assertEquals("Nostr ab-_cd", Blossom.authorizationHeader("ab+/cd=="))
    }

    @Test
    fun upload_url_and_sha256() {
        assertEquals("https://blossom.band/upload", Blossom.uploadUrl(" https://blossom.band/ "))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Blossom.sha256Hex("abc".encodeToByteArray()),
        )
    }

    @Test
    fun descriptor_is_used_only_when_hash_and_size_match() {
        val sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val d = Blossom.parseDescriptor("""{"url":"https://cdn.example/$sha.webp","sha256":"${sha.uppercase()}","size":3,"type":"image/webp"}""")!!
        assertEquals("https://cdn.example/$sha.webp", d.url)
        assertTrue(Blossom.matches(d, sha, 3))
        assertFalse(Blossom.matches(d, sha, 4))                                     // サイズが違う
        assertFalse(Blossom.matches(d.copy(sha256 = "00"), sha, 3))                  // 中身が違う
        assertTrue(Blossom.matches(d.copy(size = null), sha, 3))                     // size が無ければ sha256 だけ
        assertNull(Blossom.parseDescriptor("""{"sha256":"$sha"}"""))                 // url が無い
        assertNull(Blossom.parseDescriptor("""{"url":"ftp://x","sha256":"$sha"}"""))
        assertNull(Blossom.parseDescriptor("not json"))
    }
}
