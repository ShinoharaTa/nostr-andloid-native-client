package app.nostrdeck.model

/**
 * 本文内リンクの埋め込み表示に関する設定（設定 > 表示）。app_setting(KV) に永続。
 *  - [youtube]/[spotify] : それぞれの埋め込みカードを出すか
 *  - [ogp]               : 一般リンクの OGP カードを出すか
 *  - [ogpImages]         : OGP カードで画像を読み込むか（通信量を抑えたい場合は false）
 */
data class EmbedPrefs(
    val youtube: Boolean = true,
    val spotify: Boolean = true,
    val ogp: Boolean = true,
    val ogpImages: Boolean = true,
    val video: Boolean = true,       // 動画(.mp4/.webm/.mov 等)の直リンクをインライン再生するか
)

/** OGP(OpenGraph) メタ情報。取得できた範囲のみ埋める。 */
data class OgpData(
    val url: String,
    val title: String? = null,
    val description: String? = null,
    val image: String? = null,
    val siteName: String? = null,
)

/** 本文リンクの種別（表示カードの出し分けに使う）。 */
enum class EmbedKind { YOUTUBE, SPOTIFY, OGP, VIDEO }

/** 検出した1リンク。 */
data class LinkEmbed(val url: String, val kind: EmbedKind, val youtubeId: String? = null)

private val URL_RE = Regex("""https?://[^\s<>"'）】」]+""", RegexOption.IGNORE_CASE)
private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif")
private val VIDEO_EXT = setOf("mp4", "webm", "mov", "m4v")

/**
 * 本文からリンク埋め込み候補を抽出する（純ロジック・テスト可能）。
 *  - 画像 URL（拡張子で判定）は [NoteImages] が別途表示するため除外。
 *  - 末尾の句読点/閉じ括弧を落として正規化し、URL 単位で重複排除。上限 [max] 件。
 */
fun detectEmbeds(content: String, max: Int = 4): List<LinkEmbed> {
    val out = LinkedHashMap<String, LinkEmbed>()
    for (m in URL_RE.findAll(content)) {
        val url = m.value.trimEnd('.', ',', '、', '。', ')', '】', '」', '！', '？', '!', '?', ';', ':')
        if (out.containsKey(url)) continue
        val lowerPath = url.substringAfterLast('/').substringBefore('?').lowercase()
        val ext = lowerPath.substringAfterLast('.', "")
        if (ext in IMAGE_EXT) continue                 // 画像は別枠で表示
        val yid = youtubeId(url)
        val kind = when {
            ext in VIDEO_EXT -> EmbedKind.VIDEO         // 動画の直リンクはインラインプレイヤーで再生
            yid != null -> EmbedKind.YOUTUBE
            isSpotify(url) -> EmbedKind.SPOTIFY
            else -> EmbedKind.OGP
        }
        out[url] = LinkEmbed(url, kind, yid)
        if (out.size >= max) break
    }
    return out.values.toList()
}

/**
 * [NIP-92] imeta タグから「メディア URL → サムネイル URL」の対応を取り出す。
 * imeta は ["imeta", "url https://…", "thumb https://…", "blurhash …", …] の形式で、
 * 2要素目以降が「キー 値」の空白区切り。thumb が無ければ image で代用する。
 * アップローダー(nostr.build 等)が生成したサムネをそのまま使えるので、
 * 動画から1フレーム取得するより速く通信も少ない。
 */
fun imetaThumbs(tags: List<List<String>>): Map<String, String> {
    val out = HashMap<String, String>()
    for (tag in tags) {
        if (tag.firstOrNull() != "imeta") continue
        var url: String? = null
        var thumb: String? = null
        var image: String? = null
        for (field in tag.drop(1)) {
            val sp = field.indexOf(' ')
            if (sp <= 0) continue
            val value = field.substring(sp + 1).trim()
            if (value.isEmpty()) continue
            when (field.substring(0, sp)) {
                "url" -> url = value
                "thumb" -> thumb = value
                "image" -> image = value
            }
        }
        val u = url ?: continue
        (thumb ?: image)?.let { out[u] = it }
    }
    return out
}

private fun isSpotify(url: String): Boolean =
    Regex("""^https?://open\.spotify\.com/""", RegexOption.IGNORE_CASE).containsMatchIn(url)

/** YouTube の動画 ID を取り出す（watch?v= / youtu.be / shorts / embed）。非 YouTube は null。 */
fun youtubeId(url: String): String? {
    val patterns = listOf(
        Regex("""youtu\.be/([A-Za-z0-9_-]{11})""", RegexOption.IGNORE_CASE),
        Regex("""youtube\.com/watch\?[^ ]*v=([A-Za-z0-9_-]{11})""", RegexOption.IGNORE_CASE),
        Regex("""youtube\.com/shorts/([A-Za-z0-9_-]{11})""", RegexOption.IGNORE_CASE),
        Regex("""youtube\.com/embed/([A-Za-z0-9_-]{11})""", RegexOption.IGNORE_CASE),
    )
    for (p in patterns) p.find(url)?.let { return it.groupValues[1] }
    return null
}
