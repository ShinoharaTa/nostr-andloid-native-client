package app.nostrdeck.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [#840] AboutLibraries の JSON（aboutlibraries.json）を一覧にする [parseOssLibraries]。 */
class OssLicensesTest {

    private val json = """
        {
          "libraries": [
            {
              "uniqueId": "io.ktor:ktor-client-core-jvm",
              "artifactVersion": "3.2.3",
              "name": "ktor-client-core",
              "website": "https://github.com/ktorio/ktor",
              "developers": [{ "name": "Jetbrains Team", "organisationUrl": "https://www.jetbrains.com" }],
              "licenses": ["Apache-2.0"],
              "unknownField": 1
            },
            {
              "uniqueId": "androidx.activity:activity",
              "artifactVersion": "1.10.1",
              "name": "Activity",
              "developers": [{ "name": "The Android Open Source Project" }],
              "organization": { "name": "The Android Open Source Project" },
              "licenses": ["Apache-2.0"]
            },
            {
              "uniqueId": "net.java.dev.jna:jna",
              "artifactVersion": "5.14.0",
              "name": "Java Native Access",
              "developers": [{ "name": "Timothy Wall" }, { "name": "Matthias Bläsing" }, { "name": "Timothy Wall" }],
              "licenses": ["LGPL-2.1-or-later", "Apache-2.0"]
            },
            {
              "uniqueId": "com.google.mlkit:translate",
              "artifactVersion": "17.0.3",
              "name": "",
              "developers": [],
              "licenses": ["9962f44671a86e167c028415c7e21548"]
            }
          ],
          "licenses": {
            "Apache-2.0": { "hash": "Apache-2.0", "spdxId": "Apache-2.0", "name": "Apache License 2.0", "url": "https://spdx.org/licenses/Apache-2.0.html", "content": "Apache License\nVersion 2.0" },
            "LGPL-2.1-or-later": { "hash": "LGPL-2.1-or-later", "spdxId": "LGPL-2.1-or-later", "name": "GNU Lesser General Public License v2.1 or later", "content": "GNU LESSER GENERAL PUBLIC LICENSE" },
            "9962f44671a86e167c028415c7e21548": { "hash": "9962f44671a86e167c028415c7e21548", "name": "ML Kit Terms of Service", "url": "https://developers.google.com/ml-kit/terms" }
          }
        }
    """.trimIndent()

    private val libs = parseOssLibraries(json)

    @Test
    fun sorted_by_display_name_ignoring_case() {
        // 名前の無いもの（ML Kit）は group:artifact を名前にする。
        assertEquals(
            listOf("Activity", "com.google.mlkit:translate", "Java Native Access", "ktor-client-core"),
            libs.map { it.name },
        )
    }

    @Test
    fun copyright_holder_prefers_organization_then_developers() {
        val byId = libs.associateBy { it.id }
        assertEquals("The Android Open Source Project", byId.getValue("androidx.activity:activity").holder)
        assertEquals("Jetbrains Team", byId.getValue("io.ktor:ktor-client-core-jvm").holder)
        // 同じ名前は 1 回だけ。
        assertEquals("Timothy Wall, Matthias Bläsing", byId.getValue("net.java.dev.jna:jna").holder)
        assertNull(byId.getValue("com.google.mlkit:translate").holder)
    }

    @Test
    fun licenses_are_resolved_from_the_license_map() {
        val byId = libs.associateBy { it.id }
        val ktor = byId.getValue("io.ktor:ktor-client-core-jvm")
        assertEquals("3.2.3", ktor.version)
        assertEquals("https://github.com/ktorio/ktor", ktor.website)
        assertEquals("Apache-2.0", ktor.licenseLabel)
        assertEquals("Apache License 2.0", ktor.licenses.single().name)
        assertEquals("Apache License\nVersion 2.0", ktor.licenses.single().text)

        // 二重ライセンスは並べて出す。
        assertEquals("LGPL-2.1-or-later, Apache-2.0", byId.getValue("net.java.dev.jna:jna").licenseLabel)

        // SPDX に無い規約は名前を出し、原文は無い（画面は URL を案内する）。
        val mlkit = byId.getValue("com.google.mlkit:translate").licenses.single()
        assertEquals("ML Kit Terms of Service", mlkit.name)
        assertNull(mlkit.spdxId)
        assertNull(mlkit.text)
        assertEquals("https://developers.google.com/ml-kit/terms", mlkit.url)
        assertEquals("ML Kit Terms of Service", byId.getValue("com.google.mlkit:translate").licenseLabel)
    }
}
