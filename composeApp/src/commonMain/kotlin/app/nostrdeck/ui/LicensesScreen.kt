package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.i18n.stringResource
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.licenses_count_fmt
import nostr_deck_client.composeapp.generated.resources.licenses_desc
import nostr_deck_client.composeapp.generated.resources.licenses_load_failed
import nostr_deck_client.composeapp.generated.resources.licenses_no_text_fmt
import nostr_deck_client.composeapp.generated.resources.loading

/*
 * [#840] オープンソースライセンス（設定 → このアプリについて → オープンソースライセンス）。Web の /licenses（#688）と揃える。
 * 一覧は、ビルド時に AboutLibraries の Gradle プラグインが依存から書き出した JSON（composeApp/build.gradle.kts の
 * aboutLibraries / compose.resources）を Compose Resources から読む。Android・iOS・Desktop で同じ JSON。
 * 一覧には表示名・版・ライセンス名、タップでモーダルに著作権表示とライセンス原文を出す。
 * JSON はアプリの kotlinx.serialization で読む（aboutlibraries-core は kotlinx-serialization の版を上げるので入れない）。
 */

/** 生成した JSON の場所（`Res.readBytes` に渡すパス）。 */
internal const val OSS_LICENSES_PATH = "files/aboutlibraries.json"

/** 一覧の 1 件（AboutLibraries の 1 ライブラリ）。 */
internal data class OssLibrary(
    /** `group:artifact` */
    val id: String,
    val name: String,
    val version: String?,
    val website: String?,
    /** 著作権表示に出す権利者。POM の organization、無ければ developers の名前（どちらも無ければ null）。 */
    val holder: String?,
    val licenses: List<OssLicense>,
) {
    /** 一覧の右に出す短いライセンス名（SPDX ID が無いものは名前。複数は「, 」でつなぐ）。 */
    val licenseLabel: String get() = licenses.joinToString(", ") { it.spdxId ?: it.name }
}

internal data class OssLicense(
    val spdxId: String?,
    val name: String,
    val url: String?,
    /** ライセンス原文。プラグインが取れなかったもの（SPDX に無い規約など）は null。 */
    val text: String?,
)

// AboutLibraries が書き出す JSON の形（使う項目だけ）。
@Serializable
private class LibsJson(
    val libraries: List<LibJson> = emptyList(),
    val licenses: Map<String, LicenseJson> = emptyMap(),
)

@Serializable
private class LibJson(
    val uniqueId: String,
    val artifactVersion: String? = null,
    val name: String? = null,
    val website: String? = null,
    val developers: List<NamedJson> = emptyList(),
    val organization: NamedJson? = null,
    val licenses: List<String> = emptyList(),
)

@Serializable
private class NamedJson(val name: String? = null)

@Serializable
private class LicenseJson(
    val name: String? = null,
    val url: String? = null,
    val content: String? = null,
    val spdxId: String? = null,
)

private val ossJson = Json { ignoreUnknownKeys = true }

/** AboutLibraries の JSON を一覧にする。表示名の順（大文字小文字を区別しない）。 */
internal fun parseOssLibraries(json: String): List<OssLibrary> {
    val root = ossJson.decodeFromString<LibsJson>(json)
    return root.libraries.map { lib ->
        OssLibrary(
            id = lib.uniqueId,
            name = lib.name?.trim()?.takeIf { it.isNotEmpty() } ?: lib.uniqueId,
            version = lib.artifactVersion?.takeIf { it.isNotBlank() },
            website = lib.website?.takeIf { it.isNotBlank() },
            holder = lib.organization?.name?.trim()?.takeIf { it.isNotEmpty() }
                ?: lib.developers.mapNotNull { it.name?.trim()?.takeIf(String::isNotEmpty) }.distinct()
                    .joinToString(", ").takeIf { it.isNotEmpty() },
            licenses = lib.licenses.map { key ->
                val license = root.licenses[key]
                OssLicense(
                    spdxId = license?.spdxId?.takeIf { it.isNotBlank() },
                    name = license?.name?.takeIf { it.isNotBlank() } ?: key,
                    url = license?.url?.takeIf { it.isNotBlank() },
                    text = license?.content?.takeIf { it.isNotBlank() },
                )
            },
        )
    }
        .distinctBy { it.id }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, OssLibrary::name).thenBy { it.id })
}

/** 一度読んだ一覧（開き直すたびに 100KB の JSON を読み直さない）。 */
private var ossLibrariesCache: List<OssLibrary>? = null

private suspend fun loadOssLibraries(): List<OssLibrary> = ossLibrariesCache
    ?: withContext(Dispatchers.Default) { parseOssLibraries(Res.readBytes(OSS_LICENSES_PATH).decodeToString()) }
        .also { ossLibrariesCache = it }

/**
 * [#840] 設定の「オープンソースライセンス」の節の中身（タイトルと ← は SettingsContent が出す）。
 * 説明・件数・ライブラリの一覧。行をタップすると [LicenseDetailSheet]。
 */
@Composable
fun LicensesScreen() {
    // null = 読み込み中。読めなかったら空（失敗の文言を出す）。
    val libraries by produceState(ossLibrariesCache) {
        if (value == null) {
            value = try {
                loadOssLibraries()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
    var selected by remember { mutableStateOf<OssLibrary?>(null) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            HintText(stringResource(Res.string.licenses_desc))
            Spacer(Modifier.size(DeckSpace.Md))
        }
        val list = libraries
        when {
            list == null -> item { HintText(stringResource(Res.string.loading)) }
            list.isEmpty() -> item { HintText(stringResource(Res.string.licenses_load_failed)) }
            else -> {
                item {
                    Text(
                        stringResource(Res.string.licenses_count_fmt, list.size.toString()),
                        color = DeckColors.Text3, fontSize = DeckType.Label,
                        modifier = Modifier.padding(bottom = DeckSpace.Xs),
                    )
                    HorizontalDivider(color = DeckColors.Border)
                }
                items(list, key = { it.id }) { lib -> LicenseRow(lib, onClick = { selected = lib }) }
            }
        }
        item { Spacer(Modifier.size(DeckSpace.Xl)) }
    }

    selected?.let { LicenseDetailSheet(it, onDismiss = { selected = null }) }
}

/** 一覧の 1 行: 表示名・版（左）とライセンス名（右）。 */
@Composable
private fun LicenseRow(lib: OssLibrary, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = DeckSpace.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                lib.name, color = DeckColors.Text, fontSize = DeckType.Sub, fontWeight = DeckWeight.Strong,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            lib.version?.let {
                Text(it, color = DeckColors.Text3, fontSize = DeckType.Micro, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(DeckSpace.Sm))
        Text(
            lib.licenseLabel, color = DeckColors.Text3, fontSize = DeckType.Label,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp),
        )
        Spacer(Modifier.width(DeckSpace.Xs))
        Text("›", color = DeckColors.Text3, fontSize = DeckType.Title)
    }
    HorizontalDivider(color = DeckColors.Border)
}

/**
 * 1 件の詳細（モーダル）: `group:artifact` と版、著作権表示、Web サイト、ライセンスごとの名前と原文。
 * 原文は長いのでモーダルの中でスクロールする。原文が無いもの（ML Kit の規約など）は URL を案内する。
 */
@Composable
private fun LicenseDetailSheet(lib: OssLibrary, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AppModalSheet(title = lib.name, onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(
                listOfNotNull(lib.id, lib.version).joinToString(" "),
                color = DeckColors.Text3, fontSize = DeckType.Label,
            )
            lib.holder?.let {
                Spacer(Modifier.size(DeckSpace.Xs))
                Text("Copyright © $it", color = DeckColors.Text, fontSize = DeckType.Sub)
            }
            lib.website?.let { url ->
                Spacer(Modifier.size(DeckSpace.Xs))
                Text(
                    url, color = DeckColors.Accent, fontSize = DeckType.Label, fontWeight = DeckWeight.Link,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { runCatching { uriHandler.openUri(url) } },
                )
            }
            lib.licenses.forEach { license ->
                Spacer(Modifier.size(DeckSpace.Md))
                Text(license.name, color = DeckColors.Text, fontSize = DeckType.Sub, fontWeight = DeckWeight.Strong)
                Spacer(Modifier.size(DeckSpace.Xs))
                val text = license.text
                if (text != null) {
                    Text(
                        text, color = DeckColors.Text2, fontSize = DeckType.Label, lineHeight = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(DeckRadius.Sm))
                            .background(DeckColors.Surface2).padding(DeckSpace.Sm),
                    )
                } else {
                    val url = license.url ?: license.spdxId?.let { "https://spdx.org/licenses/$it.html" }
                    HintText(url?.let { stringResource(Res.string.licenses_no_text_fmt, it) } ?: license.name)
                }
            }
            Spacer(Modifier.size(DeckSpace.Xl))
        }
    }
}
