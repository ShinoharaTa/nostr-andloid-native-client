package app.nostrdeck.model

import app.nostrdeck.model.Nip96Upload.Availability
import app.nostrdeck.model.Nip96Upload.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [#894] NIP-96 の応答の読み取りと遅延処理の判断（nostrcheck-server と eHagaki の挙動に合わせた例）。 */
class Nip96UploadTest {

    private fun nip94(url: String, extra: String = "") =
        """{"status":"success","message":"ok",$extra"nip94_event":{"tags":[["url","$url"],["ox","ab"]]}}"""

    // ---- アップロードの応答 ----

    @Test
    fun created_with_url_is_done() {
        assertEquals(Outcome.Done("https://m.example/a.webp"), Nip96Upload.parseUpload(201, nip94("https://m.example/a.webp")))
        assertEquals(Outcome.Done("https://m.example/a.webp"), Nip96Upload.parseUpload(200, nip94("https://m.example/a.webp")))
    }

    @Test
    fun accepted_with_processing_url_is_processing() {
        val body = """{"status":"success","message":"File enqueued for processing","processing_url":"https://nostrcheck.me/api/v2/media/123","percentage":0,"nip94_event":{"tags":[["url","https://nostrcheck.me/media/x/y.webp"]]}}"""
        assertEquals(Outcome.Processing("https://nostrcheck.me/api/v2/media/123", 0), Nip96Upload.parseUpload(202, body))
    }

    @Test
    fun accepted_without_processing_url_uses_the_url() {
        // nostrcheck は no_transform のとき processing_url を空にして 202 を返す。配られているかは HEAD で確かめる
        val body = """{"status":"success","processing_url":"","nip94_event":{"tags":[["url","https://nostrcheck.me/media/x/y.mp4"]]}}"""
        assertEquals(Outcome.Done("https://nostrcheck.me/media/x/y.mp4"), Nip96Upload.parseUpload(202, body))
    }

    @Test
    fun top_level_url_fallback_and_failures() {
        assertEquals(Outcome.Done("https://m.example/b.png"), Nip96Upload.parseUpload(200, """{"url":"https://m.example/b.png"}"""))
        assertEquals(Outcome.Failed("Payment required"), Nip96Upload.parseUpload(402, """{"status":"error","message":"Payment required"}"""))
        assertEquals(Outcome.Failed("HTTP 413"), Nip96Upload.parseUpload(413, """{"status":"success"}"""))
        assertEquals(Outcome.Failed("no url in response"), Nip96Upload.parseUpload(201, """{"status":"success"}"""))
        assertTrue(Nip96Upload.parseUpload(200, "<html>") is Outcome.Failed)
        assertEquals(Outcome.Failed("HTTP 500"), Nip96Upload.parseUpload(500, "<html>"))
    }

    // ---- processing_url の応答 ----

    @Test
    fun poll_waits_while_processing_or_not_yet_recorded() {
        assertEquals(Outcome.Pending, Nip96Upload.parsePoll(404, ""))
        assertEquals(Outcome.Pending, Nip96Upload.parsePoll(202, """{"status":"processing","message":"The requested file is processing","percentage":40}"""))
        assertEquals(Outcome.Pending, Nip96Upload.parsePoll(200, """{"status":"processing","percentage":15}"""))
        assertEquals(Outcome.Pending, Nip96Upload.parsePoll(200, "{}"))
        assertEquals(Outcome.Pending, Nip96Upload.parsePoll(200, "not json"))
    }

    @Test
    fun poll_done_takes_the_url_from_that_response() {
        assertEquals(Outcome.Done("https://nostrcheck.me/media/x/final.webp"), Nip96Upload.parsePoll(201, nip94("https://nostrcheck.me/media/x/final.webp")))
        assertEquals(Outcome.Done("https://m.example/c.webp"), Nip96Upload.parsePoll(200, nip94("https://m.example/c.webp")))
    }

    @Test
    fun poll_failures() {
        assertEquals(Outcome.Failed("It was a problem processing this file"), Nip96Upload.parsePoll(200, """{"status":"error","message":"It was a problem processing this file"}"""))
        assertEquals(Outcome.Failed("HTTP 500"), Nip96Upload.parsePoll(500, "{}"))
        assertEquals(Outcome.Failed("no url in response"), Nip96Upload.parsePoll(201, """{"status":"success"}"""))
    }

    // ---- 最終 URL の HEAD ----

    @Test
    fun not_found_banner_is_unavailable_even_with_200_and_image_type() {
        assertEquals(Availability.UNAVAILABLE, Nip96Upload.availability(200, "image/webp", "File not found", "image/jpeg"))
        assertEquals(Availability.UNAVAILABLE, Nip96Upload.availability(200, "image/webp", "File not active", "image/jpeg"))
        assertEquals(Availability.UNAVAILABLE, Nip96Upload.availability(200, "video/mp4", "Processing", "video/mp4"))
    }

    @Test
    fun converted_type_in_the_same_family_is_available() {
        assertEquals(Availability.AVAILABLE, Nip96Upload.availability(200, "image/webp", null, "image/jpeg"))
        assertEquals(Availability.AVAILABLE, Nip96Upload.availability(200, "video/mp4; charset=binary", null, "video/quicktime"))
        assertEquals(Availability.AVAILABLE, Nip96Upload.availability(200, null, null, "image/png"))
        assertEquals(Availability.UNAVAILABLE, Nip96Upload.availability(200, "text/html", null, "image/png"))
        assertEquals(Availability.UNAVAILABLE, Nip96Upload.availability(404, "image/webp", null, "image/png"))
    }

    @Test
    fun servers_that_refuse_head_are_inconclusive() {
        assertEquals(Availability.INCONCLUSIVE, Nip96Upload.availability(405, null, null, "image/png"))
        assertEquals(Availability.INCONCLUSIVE, Nip96Upload.availability(501, null, null, "image/png"))
        assertEquals(Availability.INCONCLUSIVE, Nip96Upload.availability(403, "text/html", null, "image/png"))
    }

    // ---- オリジン ----

    @Test
    fun same_origin_ignores_path_and_default_port() {
        assertTrue(Nip96Upload.sameOrigin("https://nostrcheck.me/api/v2/media/123", "https://nostrcheck.me/api/v2/media"))
        assertTrue(Nip96Upload.sameOrigin("https://Nostrcheck.me:443/a", "https://nostrcheck.me/b"))
        assertFalse(Nip96Upload.sameOrigin("https://cdn.nostrcheck.me/a", "https://nostrcheck.me/b"))
        assertFalse(Nip96Upload.sameOrigin("http://nostrcheck.me/a", "https://nostrcheck.me/b"))
        assertFalse(Nip96Upload.sameOrigin("https://a.example:8443/a", "https://a.example/b"))
        assertNull(Nip96Upload.originOf("not a url"))
    }
}
