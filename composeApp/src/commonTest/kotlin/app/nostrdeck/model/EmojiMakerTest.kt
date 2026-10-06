package app.nostrdeck.model

import app.nostrdeck.model.EmojiMaker.Error
import app.nostrdeck.model.EmojiMaker.Font
import app.nostrdeck.model.EmojiMaker.Input
import app.nostrdeck.model.EmojiMaker.Parsed
import app.nostrdeck.model.EmojiMaker.TextResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [#775] 文字から作る絵文字の URL 規約。Web の `web/test/functions/emoji-params.test.ts` と
 * `web/src/features/emoji/autoShortcode.test.ts` と同じ例で固定する（ずれると Web と別の URL・別の名前になる）。
 */
class EmojiMakerTest {

    private fun ok(vararg lines: String) = TextResult.Ok(lines.toList())

    private fun url(input: Input): String = when (val p = EmojiMaker.parse(input)) {
        is Parsed.Ok -> EmojiMaker.imageUrl(p.params)
        is Parsed.Invalid -> error(p.error.code)
    }

    // ---- normalizeText（§4.3） ----

    @Test
    fun escaped_and_real_newlines_become_line_breaks() {
        assertEquals(ok("a", "b"), EmojiMaker.normalizeText("a\\nb"))
        assertEquals(ok("a", "b"), EmojiMaker.normalizeText("a¥nb"))
        assertEquals(ok("a", "b"), EmojiMaker.normalizeText("a\nb"))
        assertEquals(ok("a", "b", "c"), EmojiMaker.normalizeText("a\r\nb\rc"))
    }

    @Test
    fun trailing_spaces_are_dropped_and_leading_ones_kept() {
        assertEquals(ok(" a", " b"), EmojiMaker.normalizeText(" a \u3000\t\\n\tb"))
    }

    @Test
    fun blank_lines_at_both_ends_are_dropped() {
        assertEquals(ok("a", "", "b"), EmojiMaker.normalizeText("\n \na\n\nb\n\u3000\n"))
    }

    @Test
    fun text_is_nfc_normalized() {
        // が = か + 濁点（U+3099）
        assertEquals(ok("\u304c"), EmojiMaker.normalizeText("\u304b\u3099"))
    }

    @Test
    fun variation_selectors_are_dropped() {
        assertEquals(ok("\u2764\u2764"), EmojiMaker.normalizeText("\u2764\uFE0F\u2764\uFE0E"))
        assertEquals(ok("0123456789"), EmojiMaker.normalizeText("0123456789\uFE0F"))
    }

    @Test
    fun limits_count_code_points() {
        assertEquals(ok("1", "2", "3", "4"), EmojiMaker.normalizeText("1\\n2\\n3\\n4"))
        assertEquals(TextResult.Invalid(Error.TOO_MANY_LINES), EmojiMaker.normalizeText("1\\n2\\n3\\n4\\n5"))
        assertEquals(ok("𠮷".repeat(10)), EmojiMaker.normalizeText("𠮷".repeat(10)))
        assertEquals(TextResult.Invalid(Error.LINE_TOO_LONG), EmojiMaker.normalizeText("あ".repeat(11)))
    }

    @Test
    fun empty_and_control_characters() {
        assertEquals(TextResult.Invalid(Error.EMPTY_TEXT), EmojiMaker.normalizeText(""))
        assertEquals(TextResult.Invalid(Error.EMPTY_TEXT), EmojiMaker.normalizeText(" \\n\u3000"))
        assertEquals(TextResult.Invalid(Error.INVALID_TEXT), EmojiMaker.normalizeText("a\u0001"))
        assertEquals(TextResult.Invalid(Error.INVALID_TEXT), EmojiMaker.normalizeText("a\u0085"))
    }

    // ---- normalizeColor（§4.2） ----

    @Test
    fun colors_expand_to_lowercase_and_drop_opaque_alpha() {
        assertEquals("ff00aa", EmojiMaker.normalizeColor("#F0A"))
        assertEquals("ff00aa", EmojiMaker.normalizeColor("f0a"))
        assertEquals("ff00aa88", EmojiMaker.normalizeColor("f0a8"))
        assertEquals("ff00aa", EmojiMaker.normalizeColor("f0af"))
        assertEquals("ff00aa", EmojiMaker.normalizeColor("FF00AA"))
        assertEquals("ff00aa", EmojiMaker.normalizeColor("ff00aaff"))
        assertEquals("ff00aa80", EmojiMaker.normalizeColor("ff00aa80"))
    }

    @Test
    fun non_hex_colors_are_rejected() {
        listOf("", "#", "ff", "fffff", "gggggg", "##fff", "red", " fff").forEach { assertNull(EmojiMaker.normalizeColor(it), it) }
    }

    // ---- parse と URL（§4.2・§4.4） ----

    @Test
    fun defaults_are_left_out_of_the_url() {
        // 既定（黒・Noto Sans JP・縁取りなし）は省く。空白は +。
        assertEquals(
            "https://nostrism.shino3.net/api/emoji.png?text=a+b",
            url(Input(text = "a b", color = "000", strokeOn = false)),
        )
        // 縁取りオンでも色が空なら縁取りなし（サーバーと同じ）。
        assertEquals(
            "https://nostrism.shino3.net/api/emoji.png?text=a",
            url(Input(text = "a", strokeOn = true, stroke = "")),
        )
    }

    @Test
    fun url_is_in_the_same_order_and_encoding_as_web() {
        // Web の例（docs/emoji-maker.md §4.4 と emoji-params.test.ts）と同じ文字列になる。
        assertEquals(
            "https://nostrism.shino3.net/api/emoji.png?text=%E3%81%9D%E3%82%8C%0A%E3%81%AA&color=00ff00&stroke=ffffff&font=delagothic",
            url(Input(text = "それ\\nな", font = Font.DELAGOTHIC, color = "0F0", strokeOn = true, stroke = "#FFF")),
        )
    }

    @Test
    fun ascii_symbols_are_encoded_like_url_search_params() {
        // 英数字と *-._ 以外は %XX（大文字）、空白は +。
        assertEquals(
            "https://nostrism.shino3.net/api/emoji.png?text=a*-._%21%7E%27%28%29%0A%2B%26%3D%2F%3F%23+x",
            url(Input(text = "a*-._!~'()\n+&=/?# x", strokeOn = false)),
        )
    }

    @Test
    fun parse_errors_follow_the_server_codes() {
        fun err(input: Input) = (EmojiMaker.parse(input) as Parsed.Invalid).error
        assertEquals(Error.TEXT_TOO_LONG, err(Input(text = "a".repeat(201))))
        assertEquals(Error.EMPTY_TEXT, err(Input(text = "\\n".repeat(100))))
        assertEquals(Error.EMPTY_TEXT, err(Input(text = "")))
        assertEquals(Error.INVALID_COLOR, err(Input(text = "a", color = "red")))
        assertEquals(Error.INVALID_COLOR, err(Input(text = "a", color = "")))
        assertEquals(Error.INVALID_STROKE, err(Input(text = "a", strokeOn = true, stroke = "12")))
    }

    @Test
    fun initial_input_is_black_text_with_white_outline() {
        assertEquals(
            "https://nostrism.shino3.net/api/emoji.png?text=%E8%8D%89&stroke=ffffff",
            url(Input(text = "草")),
        )
    }

    // ---- 自動のショートコード（Web #768） ----

    @Test
    fun auto_shortcode_is_prefix_plus_first_8_hex_of_sha256() {
        // 期待値は Python の hashlib で求めた SHA-256 の先頭 8 桁（Web のテストと同じ URL）。
        assertEquals("nostrism_529092cf", EmojiMaker.autoShortcode("https://nostrism.example/api/emoji.png?text=%E8%8D%89&stroke=ffffff"))
        assertEquals("nostrism_0501759a", EmojiMaker.autoShortcode(url(Input(text = "草"))))
    }

    @Test
    fun same_url_same_name_and_valid_shortcode() {
        val a = url(Input(text = "草"))
        val b = url(Input(text = "草", color = "ff0000"))
        assertEquals(EmojiMaker.autoShortcode(a), EmojiMaker.autoShortcode(a))
        assertNotEquals(EmojiMaker.autoShortcode(a), EmojiMaker.autoShortcode(b))
        listOf(a, b, "", "https://例え.jp/絵文字.png").forEach {
            val name = EmojiMaker.autoShortcode(it)
            assertTrue(Regex("^nostrism_[0-9a-f]{8}$").matches(name), name)
            assertEquals(name, EmojiMaker.parseShortcode(name))
        }
    }

    @Test
    fun shortcode_input_allows_letters_digits_underscore_and_hyphen() {
        assertEquals("party_parrot", EmojiMaker.parseShortcode(" :party_parrot: "))
        assertEquals("a-b_1", EmojiMaker.parseShortcode("a-b_1"))
        listOf("", "::", "あ", "a b", "a.b", "a:b").forEach { assertNull(EmojiMaker.parseShortcode(it), it) }
    }
}
