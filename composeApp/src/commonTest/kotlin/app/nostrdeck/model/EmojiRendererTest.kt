package app.nostrdeck.model

import app.nostrdeck.crypto.toHex
import org.kotlincrypto.hash.sha2.SHA256
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [#879] 端末で描く絵文字（[EmojiRenderer]）を、小さな合成フォントで Web の描画処理と突き合わせる。
 * 実フォントを読む EmojiRendererGoldenTest（desktopTest）と違い、iOS（Kotlin/Native）でも動く
 * （倍精度・単精度の扱いがプラットフォームで変わっても気付けるように）。
 * フォントと基準値は `node scripts/emoji-golden.mjs` の出力（Web の render.ts で描いた RGBA の SHA-256）。
 */
@OptIn(ExperimentalEncodingApi::class)
class EmojiRendererTest {

    private val font = EmojiFontData.parse(
        Base64.decode(
            "MU1FTgEAAAAEAAAAcAMAAIj///9wAAAAkAAAAAAAAAAgAAAAQQAAAEIAAAD9VgAALAEAAAAAAAAAAAAAAAAAAPQBMgAAAMIBvAIBAAAAAABYAgAAAAD0AfQB" +
                "AgABAAAA6AMyAM7/tgNSAwEAAwAAAAAAAAADAAAAAwAAAAQAAAAHAAAABAAAAAsAAAAEAAAAMgAAAMIBAAD6ALwCAAAAAPQBAAD0AfQBAAD0AWQAZABkAJAB" +
                "kAGQAZABZAAyAM7/tgPO/7YDUgMyAFID",
        ),
    )

    private class Case(val lines: List<String>, val color: String, val stroke: String?)

    /** scripts/emoji-golden.mjs の SYNTHETIC_CASES と同じ並び・同じ値。 */
    private val cases = listOf(
        Case(listOf("A"), "000000", null),
        Case(listOf("AB"), "ff000080", "ffffff"),
        Case(listOf("A", "", "B A"), "123456", "abcdefcc"),
        Case(listOf("BBBBBBBBBB"), "000000", "ffffff"),
        Case(listOf(" "), "000000", null),
        Case(listOf("AC"), "000000", null),
    )

    private val golden = listOf(
        "128x128:5fa95cad1a3456e1d7c29bac709b998e21888a792037c4f2a446be04baa7abb5",
        "134x128:710de51a246c9b0a8ac42903587f5770d4c6e8634702cad6e867d4ba8e64eb53",
        "128x128:2ff687529ccb0e475307c163261bf1b809831a1204495b3169a0afe1b4622713",
        "706x128:8159d1d55e3777f7788b91dfbea0d7406f17e07db4d4e7088ec769e1ef7a5d5b",
        "empty_text",
        "unsupported:C",
    )

    private fun describe(result: EmojiRenderer.Result): String = when (result) {
        is EmojiRenderer.Result.Ok ->
            "${result.image.width}x${result.image.height}:${SHA256().digest(result.image.rgba).toHex()}"
        is EmojiRenderer.Result.UnsupportedChar -> "unsupported:${result.char}"
        EmojiRenderer.Result.Empty -> "empty_text"
    }

    @Test
    fun renders_the_same_pixels_as_the_web_renderer() {
        assertEquals(golden.size, cases.size)
        cases.forEachIndexed { i, c ->
            val params = EmojiMaker.Params(c.lines, c.color, c.stroke, EmojiMaker.Font.NOTOSANS)
            assertEquals(golden[i], describe(EmojiRenderer.render(font, params)), "case $i: ${c.lines}")
        }
    }

    @Test
    fun unsupported_char_keeps_surrogate_pairs_whole() {
        val params = EmojiMaker.Params(listOf("A🍣"), "000000", null, EmojiMaker.Font.NOTOSANS)
        assertEquals("unsupported:🍣", describe(EmojiRenderer.render(font, params)))
    }

    @Test
    fun argb_is_straight_alpha_in_argb_order() {
        val params = EmojiMaker.Params(listOf("A"), "ff000080", null, EmojiMaker.Font.NOTOSANS)
        val image = (EmojiRenderer.render(font, params) as EmojiRenderer.Result.Ok).image
        val argb = image.toArgb()
        // 三角の中ほど（完全に塗られた画素）は色そのまま・α = 0x80
        val center = argb[64 * image.width + 64]
        assertEquals(0x80ff0000.toInt(), center)
    }

    @Test
    fun bad_font_data_is_rejected() {
        assertFailsWith<IllegalArgumentException> { EmojiFontData.parse(ByteArray(8)) }
        assertFailsWith<IllegalArgumentException> { EmojiFontData.parse(ByteArray(32)) }
    }
}
