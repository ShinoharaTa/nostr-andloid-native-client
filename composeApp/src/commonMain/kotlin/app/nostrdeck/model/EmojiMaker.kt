package app.nostrdeck.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import org.kotlincrypto.hash.sha2.SHA256

/**
 * [#775] 文字から作る絵文字（Web #750 の `/api/emoji.png`）の URL 規約。
 *
 * Web の `web/server/emoji/params.ts`（サーバーと作成ページが共有する正規化）と `autoShortcode.ts` をそのまま移したもの。
 * 同じ入力なら Web と同じ URL・同じ自動の名前になる（画像はサーバーが作る。端末はフォントを持たない）。
 * 仕様: `web/docs/emoji-maker.md` §4。Web 側を変えたらここも揃えること（テストは Web と同じ例で固定してある）。
 */
object EmojiMaker {
    /** 画像を作るサーバー（Web 版と同じ）。 */
    const val ORIGIN = "https://nostrism.shino3.net"
    const val API_PATH = "/api/emoji.png"

    const val DEFAULT_COLOR = "000000"
    /** 正規化後の行数・1 行のコードポイント数・生の text のコードポイント数の上限。 */
    const val MAX_LINES = 4
    const val MAX_LINE_LENGTH = 10
    const val MAX_RAW_TEXT_LENGTH = 200

    /** ピッカーで作った絵文字の自動のショートコードの頭（Web #768）。 */
    const val AUTO_SHORTCODE_PREFIX = "nostrism_"

    /** フォント（id はサーバーの `font` パラメータ。名前は固有名詞なので翻訳しない）。 */
    enum class Font(val id: String, val label: String) {
        NOTOSANS("notosans", "Noto Sans JP"),
        MPLUSROUNDED("mplusrounded", "M PLUS Rounded 1c"),
        DELAGOTHIC("delagothic", "Dela Gothic One"),
    }

    val DEFAULT_FONT = Font.NOTOSANS

    /**
     * 作成フォームの入力。色は入力欄の文字列のまま持ち、[parse] で検証・正規化する。
     * 縁取りはオン・オフと色を別に持つ（オフの間も色は覚えておく）。初期値は黒文字 + 白縁取り（明暗どちらの地でも見える）。
     */
    data class Input(
        val text: String = "",
        val font: Font = DEFAULT_FONT,
        val color: String = DEFAULT_COLOR,
        val strokeOn: Boolean = true,
        val stroke: String = "ffffff",
    )

    /** 正規化した指定。[color] / [stroke] は小文字の hex（6 桁、またはアルファが ff 以外の 8 桁）。 */
    data class Params(val lines: List<String>, val color: String, val stroke: String?, val font: Font)

    /** 検証の失敗。[code] はサーバーの 400 の `error` と同じ。 */
    enum class Error(val code: String) {
        TEXT_TOO_LONG("text_too_long"),
        EMPTY_TEXT("empty_text"),
        TOO_MANY_LINES("too_many_lines"),
        LINE_TOO_LONG("line_too_long"),
        INVALID_TEXT("invalid_text"),
        INVALID_COLOR("invalid_color"),
        INVALID_STROKE("invalid_stroke"),
    }

    sealed interface Parsed {
        data class Ok(val params: Params) : Parsed
        data class Invalid(val error: Error) : Parsed
    }

    sealed interface TextResult {
        data class Ok(val lines: List<String>) : TextResult
        data class Invalid(val error: Error) : TextResult
    }

    private val ESCAPED_NEWLINE = Regex("""[\\¥]n""")
    private val VARIATION_SELECTORS = Regex("[︎️]")
    private val TRAILING_SPACE = Regex("[ 　\t]+$")
    private val HEX = Regex("^#?([0-9a-fA-F]{3}|[0-9a-fA-F]{4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
    private val SHORTCODE = Regex("^[A-Za-z0-9_-]+$")

    /** text の正規化と検査（§4.3 の 1〜7。フォントに無い文字の検査はサーバーが行う）。 */
    fun normalizeText(text: String): TextResult {
        val unified = nfc(text).replace("\r\n", "\n").replace('\r', '\n').replace(ESCAPED_NEWLINE, "\n")
        // 異体字セレクタは末尾の空白を落とす前に除く（"a" + 空白 + U+FE0F の空白を残さないため）。
        val lines = unified.split("\n")
            .map { it.replace(VARIATION_SELECTORS, "").replace(TRAILING_SPACE, "").replace('\t', ' ') }
            .dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
        return when {
            lines.isEmpty() -> TextResult.Invalid(Error.EMPTY_TEXT)
            lines.size > MAX_LINES -> TextResult.Invalid(Error.TOO_MANY_LINES)
            lines.any { codePointCount(it) > MAX_LINE_LENGTH } -> TextResult.Invalid(Error.LINE_TOO_LONG)
            lines.any(::hasControlChar) -> TextResult.Invalid(Error.INVALID_TEXT)
            else -> TextResult.Ok(lines)
        }
    }

    /** 色の正規化。# の有無を問わない 3/4/6/8 桁の hex を小文字 6 桁（アルファが ff 以外なら 8 桁）にする。hex でなければ null。 */
    fun normalizeColor(value: String): String? {
        val match = HEX.matchEntire(value) ?: return null
        var hex = match.groupValues[1].lowercase()
        if (hex.length <= 4) hex = hex.map { "$it$it" }.joinToString("")
        if (hex.length == 8 && hex.endsWith("ff")) hex = hex.substring(0, 6)
        return hex
    }

    /** 入力を検証して正規化する（Web の parseMakerInput → parseEmojiParams と同じ順・同じ結果）。 */
    fun parse(input: Input): Parsed {
        if (codePointCount(input.text) > MAX_RAW_TEXT_LENGTH) return Parsed.Invalid(Error.TEXT_TOO_LONG)
        val lines = when (val t = normalizeText(input.text)) {
            is TextResult.Ok -> t.lines
            is TextResult.Invalid -> return Parsed.Invalid(t.error)
        }
        val color = normalizeColor(input.color) ?: return Parsed.Invalid(Error.INVALID_COLOR)
        // 縁取りの色が空なら「縁取りなし」（サーバーと同じ）。
        val stroke = if (input.strokeOn && input.stroke.isNotEmpty()) {
            normalizeColor(input.stroke) ?: return Parsed.Invalid(Error.INVALID_STROKE)
        } else null
        return Parsed.Ok(Params(lines, color, stroke, input.font))
    }

    /**
     * 正規化したクエリ（§4.4。text・color・stroke・font の順、既定値は省略）。先頭の ? は付けない。
     * エンコードは Web の URLSearchParams と同じ（application/x-www-form-urlencoded。空白は +、改行は %0A）。
     */
    fun canonicalQuery(params: Params): String = buildList {
        add("text" to params.lines.joinToString("\n"))
        if (params.color != DEFAULT_COLOR) add("color" to params.color)
        if (params.stroke != null) add("stroke" to params.stroke)
        if (params.font != DEFAULT_FONT) add("font" to params.font.id)
    }.joinToString("&") { (k, v) -> "${formEncode(k)}=${formEncode(v)}" }

    /** 画像の URL（絶対 URL。§4.4）。これがそのまま emoji タグの URL になる。 */
    fun imageUrl(params: Params): String = "$ORIGIN$API_PATH?${canonicalQuery(params)}"

    /**
     * 自動のショートコード（Web #768）。画像 URL の SHA-256 の先頭 8 桁（hex）を `nostrism_` に続ける。
     * 同じ絵文字（同じ URL）なら毎回同じ名前になり、英小文字・数字・_ だけなので NIP-30 の文字種に合う。
     */
    fun autoShortcode(url: String): String {
        val digest = SHA256().digest(url.encodeToByteArray())
        return AUTO_SHORTCODE_PREFIX + digest.take(4).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    /** 入力欄のショートコードを検証する（前後の : は外す。英数字と _ - だけ。Web の parseEmojiShortcode と同じ）。使えなければ null。 */
    fun parseShortcode(raw: String): String? {
        val value = raw.trim().removePrefix(":").removeSuffix(":")
        return value.takeIf { it.isNotEmpty() && SHORTCODE.matches(it) }
    }

    /** [#834] 前回の設定の保存値の版（Web の `lastMakerInput.ts` の `v`）。 */
    private const val LAST_VERSION = 1

    /**
     * [#834] 作成フォームの前回の設定（Web #783 の `lastMakerInput.ts` と同じ形。docs/emoji-maker.md §7.6）。
     * `{"v":1,"color":"rrggbb","stroke":"rrggbb"|null,"font":"notosans"|…}`。stroke が null なら縁取りなし。
     * 使った絵文字の正規化した指定から作るので、**テキストは入らない**。端末ごと（アカウントに紐付けない・同期しない）。
     */
    fun encodeLast(params: Params): String = buildJsonObject {
        put("v", LAST_VERSION)
        put("color", params.color)
        put("stroke", params.stroke)
        put("font", params.font.id)
    }.toString()

    /**
     * [#834] 前回の設定を初期値の入力にする（テキストは空）。Web の `loadLastMakerInput` と同じ規則:
     * 無い・JSON でない・`v` が 1 でない・色が読めない（[normalizeColor]）・フォントが無い、のどれか 1 つでもあれば
     * 全部捨てて既定（[Input] の初期値 = 黒文字 + 白縁取り、Noto Sans JP）。縁取りなしのときの縁取りの色は既定の色。
     */
    fun decodeLast(raw: String?): Input {
        val o = raw?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return Input()
        val v = (o["v"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
        val color = o["color"].stringOrNull()?.let(::normalizeColor)
        val font = o["font"].stringOrNull()?.let { id -> Font.entries.firstOrNull { it.id == id } }
        if (v != LAST_VERSION.toDouble() || color == null || font == null) return Input()
        val stroke = when (val s = o["stroke"]) {
            JsonNull -> null
            else -> s.stringOrNull()?.let(::normalizeColor) ?: return Input()   // 無い・文字列でない・色でない
        }
        return Input(font = font, color = color, strokeOn = stroke != null, stroke = stroke ?: Input().stroke)
    }

    private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** 色のパレット（よく使う色。細かい色は 16 進で入れる）。 */
    val PALETTE = listOf(
        "000000", "ffffff", "757575", "e53935", "d81b60", "fb8c00",
        "fdd835", "43a047", "00acc1", "1e88e5", "8e24aa", "6d4c41",
    )

    /** application/x-www-form-urlencoded のバイト列直列化（WHATWG。英数字と *-._ 以外を %XX、空白は +）。 */
    private fun formEncode(s: String): String = buildString {
        for (b in s.encodeToByteArray()) {
            val c = b.toInt() and 0xff
            when {
                c == 0x20 -> append('+')
                c in 0x30..0x39 || c in 0x41..0x5A || c in 0x61..0x7A || c == 0x2A || c == 0x2D || c == 0x2E || c == 0x5F ->
                    append(c.toChar())
                else -> append('%').append(HEX_DIGITS[c shr 4]).append(HEX_DIGITS[c and 0xf])
            }
        }
    }

    private const val HEX_DIGITS = "0123456789ABCDEF"

    /** 制御文字（U+0000–U+001F・U+007F–U+009F）を含むか。 */
    private fun hasControlChar(line: String): Boolean = line.any { it.code <= 0x1f || it.code in 0x7f..0x9f }

    /** コードポイントの数（サロゲートペアは 1 つと数える。Web の `[...line].length`）。 */
    internal fun codePointCount(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            i += if (s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) 2 else 1
            n++
        }
        return n
    }
}

/**
 * [#775] ピッカーの「絵文字を作る」で作った絵文字（リアクションに使う）。
 * [autoName] = ショートコードを自動で付けた（nostrism_…）、[save] = 「自分の絵文字リストにも保存」がオン。
 */
data class MadeEmoji(val shortcode: String, val url: String, val autoName: Boolean, val save: Boolean)

/** Unicode の NFC 正規化（端末の実装を使う）。 */
expect fun nfc(s: String): String
