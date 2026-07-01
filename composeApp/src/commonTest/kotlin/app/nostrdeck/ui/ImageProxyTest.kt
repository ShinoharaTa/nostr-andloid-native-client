package app.nostrdeck.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageProxyTest {

    @Test
    fun proxied_wraps_url_in_wsrv() {
        val p = ImageProxy.proxied("https://dev.sabotenism.cc/img/ruri.webp")
        assertTrue(p.startsWith("https://wsrv.nl/?url="), "should route through wsrv.nl")
        assertTrue(p.contains("output=webp"))
    }

    @Test
    fun originOf_recovers_source_url() {
        val src = "https://dev.sabotenism.cc/img/ruri.webp"
        val proxied = ImageProxy.proxied(src)
        // フォールバックで元 URL を正確に復元できること（.cc 拒否時の直取得用）。
        assertEquals(src, ImageProxy.originOf(proxied))
    }

    @Test
    fun originOf_recovers_url_with_query_params() {
        // 元 URL に ? や & が含まれても、エンコード済みなので境界を誤らない。
        val src = "https://cdn.example.com/i?id=42&w=1"
        val proxied = ImageProxy.proxied(src, width = 300, quality = 80, animated = true)
        assertEquals(src, ImageProxy.originOf(proxied))
    }

    @Test
    fun originOf_returns_null_for_non_proxy_input() {
        assertNull(ImageProxy.originOf("https://dev.sabotenism.cc/img/ruri.webp"))
        assertNull(ImageProxy.originOf(null))
        assertNull(ImageProxy.originOf(42))
    }
}
