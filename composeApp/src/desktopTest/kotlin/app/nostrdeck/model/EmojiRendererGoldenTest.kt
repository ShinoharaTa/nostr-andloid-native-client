package app.nostrdeck.model

import app.nostrdeck.crypto.toHex
import app.nostrdeck.data.EmojiFonts
import org.kotlincrypto.hash.sha2.SHA256
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [#879] 端末で描く絵文字のプレビュー（[EmojiRenderer]）が、サーバー（`/api/emoji.png`。Web の `web/server/emoji/`）と
 * 同じ画素になるか。基準値はリポジトリの Web の描画処理から作る（`node scripts/emoji-golden.mjs`。RGBA の SHA-256）。
 * フォントはリポジトリの `web/public/fonts/`（端末が取ってくるものと同じファイル）を読む。
 * Web の描画処理・フォントの版が変わったら、ここ（と [EmojiRenderer] / [EmojiFonts.FILES]）を揃えるまで落ちる。
 */
class EmojiRendererGoldenTest {

    private val webDir = File("../web")

    private class Case(val font: EmojiMaker.Font, val lines: List<String>, val color: String, val stroke: String?)

    /** scripts/emoji-golden.mjs の CASES と同じ並び・同じ値。 */
    private val cases = listOf(
        Case(EmojiMaker.Font.NOTOSANS, listOf("草"), "000000", null),
        Case(EmojiMaker.Font.NOTOSANS, listOf("草"), "000000", "ffffff"),
        Case(
            EmojiMaker.Font.NOTOSANS,
            listOf("議識護警競露騰欄織職", "臓醸譲覧観顧驚魔曜艦", "懸繊鐘騒瀬鶏厳藤籍躍", "麗襲籠鑑響灘癒繭穫顕"),
            "000000", "ffffff",
        ),
        Case(EmojiMaker.Font.MPLUSROUNDED, listOf("Nostr 2026"), "1d9bf0", null),
        Case(EmojiMaker.Font.DELAGOTHIC, listOf("おつかれさまでした"), "ffffff", "ff0000"),
        Case(EmojiMaker.Font.NOTOSANS, listOf("WWWWWWWWWW"), "000000", "ffffff"),
        Case(EmojiMaker.Font.MPLUSROUNDED, listOf("上", "", "下"), "ff000080", "0000ff80"),
        Case(EmojiMaker.Font.DELAGOTHIC, listOf("…"), "333333", null),
        Case(EmojiMaker.Font.NOTOSANS, listOf("ーー", "a b"), "00ff00", "000000"),
        Case(EmojiMaker.Font.MPLUSROUNDED, listOf("🍣"), "000000", null),
        Case(EmojiMaker.Font.NOTOSANS, listOf(" "), "000000", null),
    )

    /** `node scripts/emoji-golden.mjs` の出力（RENDER_VERSION = 2）。 */
    private val golden = listOf(
        "128x128:e6a787c5e053d7b332f34ac8105e692d3898743a99a1b442264c58728519d9d6",
        "128x128:2a9e0fd86c658d3dc93ccd191a35e025b44fddaf3ffe431831234f5807fede2f",
        "289x128:7b65a4557436065297b92da8c148c1ab02c790524bcfc3b9e056dbc7f9699fc5",
        "754x128:b7d7a55d5d639e6f2d37b809b53689b301bb5923bd052c75e411c9c6a72510f5",
        "1210x128:dfda087d77c02a39409efb5845543ef8cdc2286fbeaabede6edfcb6b07dc4d98",
        "1080x128:12670af96e5c12a399b50fd62b54615812dec5978b2f1f179b7a440f1b42edab",
        "128x128:8ef468b10907844c8fd2452a5343b4f017149bc963b9b6ba2de5c97eabe0acb2",
        "128x128:a647ac6f4a10e3efddbd61f597252a266d932f7acfbfbe069fa0c5992d04e847",
        "128x128:7522073a3a4ed8bfbc1cbc345179558d09572e3afd6b1976ba96c24b509f6525",
        "unsupported:🍣",
        "empty_text",
    )

    private val fonts = HashMap<EmojiMaker.Font, EmojiFontData>()

    private fun fontOf(font: EmojiMaker.Font): EmojiFontData = fonts.getOrPut(font) {
        val file = EmojiFonts.FILES.getValue(font)
        val data = EmojiFonts.parseVerified(File(webDir, "public/fonts/${file.name}").readBytes(), file)
        assertNotNull(data, "web/public/fonts/${file.name} のサイズか SHA-256 が EmojiFonts.FILES と合わない")
    }

    private fun describe(result: EmojiRenderer.Result): String = when (result) {
        is EmojiRenderer.Result.Ok ->
            "${result.image.width}x${result.image.height}:${SHA256().digest(result.image.rgba).toHex()}"
        is EmojiRenderer.Result.UnsupportedChar -> "unsupported:${result.char}"
        EmojiRenderer.Result.Empty -> "empty_text"
    }

    @Test
    fun renders_the_same_pixels_as_the_server() {
        assertEquals(golden.size, cases.size)
        cases.forEachIndexed { i, c ->
            val params = EmojiMaker.Params(c.lines, c.color, c.stroke, c.font)
            assertEquals(golden[i], describe(EmojiRenderer.render(fontOf(c.font), params)), "case $i: ${c.lines}")
        }
    }

    /** Web の描画処理の版（render.ts の RENDER_VERSION）が、移したときの版のままか。 */
    @Test
    fun render_version_matches_web() {
        val source = File(webDir, "server/emoji/render.ts").readText()
        val version = Regex("""export const RENDER_VERSION = (\d+);""").find(source)?.groupValues?.get(1)?.toInt()
        assertEquals(EmojiRenderer.RENDER_VERSION, version, "Web の描画処理が変わった。EmojiRenderer を揃えて基準値を作り直す")
    }

    /** サーバーが読むフォントのファイル名（/fonts/<id>.v1.bin）と、端末が取ってくるファイル名が同じか。 */
    @Test
    fun font_files_match_what_the_server_reads() {
        val source = File(webDir, "functions/api/emoji.png.ts").readText()
        val pattern = Regex("""/fonts/\$\{id\}(\.v\d+\.bin)""").find(source)?.groupValues?.get(1)
        assertNotNull(pattern)
        EmojiMaker.Font.entries.forEach { font ->
            assertEquals("${font.id}$pattern", EmojiFonts.FILES.getValue(font).name)
        }
    }

    @Test
    fun corrupted_font_data_is_rejected() {
        val file = EmojiFonts.FILES.getValue(EmojiMaker.Font.DELAGOTHIC)
        val bytes = File(webDir, "public/fonts/${file.name}").readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        assertNull(EmojiFonts.parseVerified(bytes, file))
        assertNull(EmojiFonts.parseVerified(bytes.copyOf(bytes.size - 4), file))
    }
}
