package app.nostrdeck.ui

import kotlinx.coroutines.runBlocking
import nostr_deck_client.composeapp.generated.resources.Res
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [#840] ビルド時に AboutLibraries が生成した aboutlibraries.json が、アプリと同じ `Res.readBytes` で読めて、
 * 主要な依存が載り、自分のアプリと内部モジュールが載っていないこと。
 * （生成・同梱の配線は composeApp/build.gradle.kts の aboutLibraries / compose.resources）
 */
class OssLicensesResourceTest {

    private val libs by lazy {
        runBlocking { parseOssLibraries(Res.readBytes(OSS_LICENSES_PATH).decodeToString()) }
    }

    @Test
    fun generated_list_contains_the_main_dependencies() {
        val ids = libs.map { it.id }
        assertTrue(libs.size > 100, "件数: ${libs.size}")
        listOf(
            "io.ktor:ktor-client-core",
            "app.cash.sqldelight:runtime",
            "io.coil-kt.coil3:coil-compose",
            "org.jetbrains.compose.ui:ui",
            "org.kotlincrypto.hash:sha2",
            "fr.acinq.secp256k1:",
            "org.jetbrains.kotlinx:kotlinx-serialization-json",
            "com.russhwolf:multiplatform-settings",
        ).forEach { prefix -> assertTrue(ids.any { it.startsWith(prefix) }, "$prefix が一覧に無い") }
    }

    @Test
    fun own_app_and_internal_modules_are_not_listed() {
        libs.forEach { lib ->
            assertFalse(lib.id.startsWith("app.nostrdeck"), lib.id)
            assertFalse(lib.id.contains("nostr-core"), lib.id)
        }
    }

    @Test
    fun every_library_has_a_license() {
        libs.forEach { lib -> assertTrue(lib.licenses.isNotEmpty(), "${lib.id} にライセンスが無い") }
        // Apache-2.0 は原文が入っている（プラグインが SPDX から取る）。
        val apache = libs.flatMap { it.licenses }.first { it.spdxId == "Apache-2.0" }
        assertTrue(apache.text.orEmpty().contains("Apache License"), "Apache-2.0 の原文が無い")
    }
}
