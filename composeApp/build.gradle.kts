import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties
import javax.imageio.ImageIO

// リリース署名の資格情報は keystore.properties（.gitignore 済み）から読む。無ければ未署名。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

// [#252] バージョン運用: versionName は git tag `vX.Y.Z` 由来、versionCode はコミット数。
// scripts/version.sh と同じ規則。CI は -PversionName/-PversionCode で明示上書きする。
fun gitOutput(vararg args: String): String? = runCatching {
    val proc = ProcessBuilder(*args).directory(rootDir).redirectErrorStream(false).start()
    val out = proc.inputStream.bufferedReader().readText().trim()
    if (proc.waitFor() == 0 && out.isNotEmpty()) out else null
}.getOrNull()

val gitVersionName: String =
    gitOutput("git", "describe", "--tags", "--abbrev=0", "--match", "v[0-9]*")
        ?.removePrefix("v") ?: "0.0.0"
val gitVersionCode: Int = gitOutput("git", "rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.serialization)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.aboutlibraries)
}

kotlin {

    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // expect/actual class は Beta 警告が出る。意図的な利用なので抑制。
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

    // [#215] CMP 1.11 は iosX64(Intel シミュ)を廃止。arm64 実機＋arm64 シミュのみ。
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    // [#218] Desktop(Mac/JVM) ターゲット。commonMain の Compose デッキ UI をそのまま動かす。
    jvm("desktop") {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)
            implementation(libs.androidx.lifecycle.viewmodel)

            implementation(projects.nostrCore)          // [#183] UI/DB 非依存のプロトコル層

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
            implementation(libs.colorpicker.compose)   // [#268] HSVカラーピッカー
            implementation(libs.secp256k1)
            implementation(libs.kotlincrypto.sha2)
            implementation(libs.multiplatform.settings)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.window)          // FoldingFeature
            implementation(libs.androidx.exifinterface)   // [#322] 圧縮時に EXIF の向きを焼き込む
            implementation(libs.coil.gif)                  // アニメGIF/WebP デコーダ
            implementation(libs.androidx.media3.exoplayer)  // 動画インライン再生
            implementation(libs.androidx.media3.ui)         // PlayerView（コントローラ付き）
            implementation(libs.androidx.media3.transformer) // [#248] 動画トランスコード
            implementation(libs.androidx.media3.effect)      // [#248] Presentation(解像度変更)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.android)
            implementation(libs.secp256k1.jni.android)     // secp256k1 ネイティブ実体
            implementation(libs.androidx.credentials)                 // [#Nosskey] パスキー(WebAuthn PRF)
            implementation(libs.androidx.credentials.play.services)    // GMS 経由の passkey provider
            implementation(libs.mlkit.translate)                       // [#356] オンデバイス翻訳
            implementation(libs.mlkit.language.id)                     // [#356] 翻訳元言語の判定
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.ktor.client.cio)
                implementation(libs.sqldelight.sqlite.driver)
                implementation(libs.secp256k1.jni.jvm)
                implementation(libs.kotlinx.coroutines.swing)   // Dispatchers.Main（Compose Desktop）
                // [#218] JNA for Windows Credential Manager native API
                implementation(libs.jna)
                implementation(libs.jna.platform)
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// [#218] Windows .ico をソース PNG (icon-512.png) から自動生成するタスク
// Linux/macOS/Android と同じアイコンを Windows でも使うため
tasks.register("generateWindowsIco") {
    val sourcePng = rootProject.file("docs/store/icon-512.png")
    val targetIco = rootProject.file("docs/store/icon.ico")
    doLast {
        if (!sourcePng.exists()) {
            throw GradleException("Source PNG not found: $sourcePng")
        }
        // Java でマルチ解像度 .ico を生成
        val sizes = intArrayOf(16, 24, 32, 48, 64, 128, 256)
        val buf = ByteArrayOutputStream()
        
        // ICO header
        buf.write(shortToBytes(0)) // reserved
        buf.write(shortToBytes(1)) // type: 1 = ICO
        buf.write(shortToBytes(sizes.size)) // count
        
        val imageData = mutableListOf<ByteArray>()
        var offset = 6 + sizes.size * 16 // header + directory entries
        
        for (size in sizes) {
            val resized = resizePng(sourcePng.absolutePath, size, size)
            imageData.add(resized)
            // Directory entry
            buf.write(size) // width (0 = 256)
            buf.write(size) // height (0 = 256)
            buf.write(0) // color count
            buf.write(0) // reserved
            buf.write(shortToBytes(1)) // color planes
            buf.write(shortToBytes(32)) // bits per pixel
            buf.write(intToBytes(resized.size)) // size in bytes
            buf.write(intToBytes(offset)) // offset
            offset += resized.size
        }
        
        // Write image data
        for (data in imageData) {
            buf.write(data)
        }
        
        targetIco.parentFile.mkdirs()
        targetIco.writeBytes(buf.toByteArray())
        println("Generated $targetIco from $sourcePng (${sizes.size} resolutions)")
    }
}

fun shortToBytes(value: Int): ByteArray = byteArrayOf(
    (value and 0xFF).toByte(),
    ((value shr 8) and 0xFF).toByte()
)

fun intToBytes(value: Int): ByteArray = byteArrayOf(
    (value and 0xFF).toByte(),
    ((value shr 8) and 0xFF).toByte(),
    ((value shr 16) and 0xFF).toByte(),
    ((value shr 24) and 0xFF).toByte()
)

fun resizePng(inputPath: String, width: Int, height: Int): ByteArray {
    val img = ImageIO.read(File(inputPath))
    val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g = buffered.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    g.drawImage(img, 0, 0, width, height, null)
    g.dispose()
    val baos = ByteArrayOutputStream()
    ImageIO.write(buffered, "PNG", baos)
    return baos.toByteArray()
}

// [#218] Compose Desktop 配布設定。各ターゲット別のネイティブ配布形式を指定。
// Windows: MSI, Linux: DEB/RPM, macOS: DMG
compose.desktop {
    application {
        mainClass = "app.nostrdeck.MainKt"
        // [#438] dmg の jpackage / jlink に使う JDK。既定は Gradle デーモンの JDK だが、デーモンは
        // gradle-daemon-jvm.properties の「Java 21・ベンダー問わず」で使い回されるため、jpackage の無い
        // JBR のデーモンに当たると packageDmg が落ちる。scripts/release-github.sh が明示的に渡す。
        (findProperty("packagingJavaHome") as String?)?.let { javaHome = it }
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Dmg)
            packageName = "Nostrism"
            packageVersion = "1.0.0"
            description = "Nostr Decentralized Client"
            vendor = "Nostrism"
            copyright = "Copyright 2025 Nostrism"
            // [#730] jlink が同梱するモジュール。Compose Desktop はここに書いたものしか入れない（依存からの自動検出はしない）。
            // java.sql が無いと SQLDelight の JDBC ドライバ（org.sqlite.JDBC: java.sql.Driver）を読めず、起動時に DB を開けない。
            // 一覧は `./gradlew :composeApp:suggestRuntimeModules`（jdeps）の結果 + java.naming（#729 で追加）。依存を足したら再実行して揃える。
            modules("java.instrument", "java.management", "java.naming", "java.sql", "jdk.unsupported")
            linux {
                debMaintainer = "Nostrism <noreply@nostrism.example>"
                menuGroup = "Network;Chat;"
                iconFile.set(rootProject.file("docs/store/icon-512.png"))
            }
            macOS {
                bundleID = "net.shino3.nostrism"
                iconFile.set(rootProject.file("docs/store/icon.icns"))
            }
            windows {
                menuGroup = "Nostrism"
                upgradeUuid = "e8f5b9c2-3d4a-4f7e-8b1c-2d5e6f7a8b9c"
                iconFile.set(rootProject.file("docs/store/icon.ico"))
            }
        }
    }
}

// [#183] :nostr-core（Compose 非依存）へ移した NostrEvent に @Immutable の代わりに
// stable 指定を与え、フィードの再コンポーズ最適化を維持する。
composeCompiler {
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose_stability.conf"))
}

android {
    namespace = "app.nostrdeck"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        // Play 上のアプリID。コードのパッケージ(namespace=app.nostrdeck)とは独立でよい。
        applicationId = "net.shino3.nostrism"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // [#252] 既定は git 由来（tag=versionName / コミット数=versionCode）。
        // CI や手元で固定したい場合は -PversionCode/-PversionName で上書きできる。
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: gitVersionCode
        versionName = (project.findProperty("versionName") as String?) ?: gitVersionName
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // [#264] BuildConfig.VERSION_NAME を使う（配布テーマの minAppVersion 判定）。
    buildFeatures {
        buildConfig = true
    }
    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            // R8 は proguard ルール整備後に別途有効化する（beta は未圧縮 release で十分高速）。
            isMinifyEnabled = false
            signingConfig = if (keystorePropsFile.exists()) signingConfigs.getByName("release") else signingConfig
        }
        debug {
            // debug は別パッケージ(...debug)にして、Play/release 版(net.shino3.nostrism)と端末上で共存させる。
            applicationIdSuffix = ".debug"
        }
    }
    // [#26] ネイティブ .so を非圧縮で梱包し 16KB ページ境界に揃える（AGP が整列）。
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

sqldelight {
    databases {
        create("NostrDb") {
            packageName.set("app.nostrdeck.db")
            // マイグレーション運用: スキーマ変更のたびに version を上げ <prev>.sqm を追加する。
            // SQLDelight は .sqm ファイルからスキーマ version を導出する（最大の <n>.sqm + 1）。
            // verifyMigrations: .sqm を順に適用した結果が Nostr.sq の現行スキーマと一致するか検証。
            verifyMigrations.set(true)
        }
    }
}

// [#840] オープンソースライセンスの一覧（設定 → このアプリについて → オープンソースライセンス）。
// AboutLibraries の exportLibraryDefinitions で、依存の一覧とライセンス文を
// build/generated/aboutLibraries/composeResources/files/aboutlibraries.json に書き出す（build/ 配下なのでコミットしない）。
// ライセンス文は SPDX の原文をタスクの実行時にネットから取る（取れなければ空になり、画面は URL を出す）。
aboutLibraries {
    collect {
        // 配布物に入るものだけ: Android の release・Desktop・iOS（Kotlin/Native は *CompileClasspath を持たないので、
        // iosMain のメタデータの classpath（commonMain の依存も含む）で拾う）。debug・テスト・Compose Hot Reload の dev 用は外す。
        // 3 つの合算を全プラットフォームで同じ JSON として出す（Android だけの依存が Desktop の一覧に載る等は許容）。
        filterVariants.addAll("release", "desktop", "metadataIosMain")
        // BOM（kotlin-bom 等の platform 依存）は版合わせだけで中身が無いので載せない。
        includePlatform = false
    }
    library {
        // 自分のアプリ（app.nostrdeck）と内部モジュール（:nostr-core）は載せない。プロジェクト依存は元々集めないが、念のため。
        exclusionPatterns.addAll(Regex("""^app\.nostrdeck""").toPattern(), Regex(""":nostr-core$""").toPattern())
    }
    export {
        outputFile = layout.buildDirectory.file("generated/aboutLibraries/composeResources/files/aboutlibraries.json")
        // 画面で使わない項目を落として小さくする（使うのは名前・版・Web サイト・開発者/組織・ライセンス）。
        excludeFields.addAll("description", "funding", "scm")
    }
}

compose.resources {
    // [#840] 生成した aboutlibraries.json を Android / iOS / Desktop の Compose Resources に載せ、
    // commonMain から Res.readBytes("files/aboutlibraries.json") で読む。
    // customDirectory はそのソースセットの composeResources を「置き換える」ので、自前の composeResources を持たない
    // androidMain / iosMain / desktopMain に付ける（commonMain に付けると文字列などが消える）。
    // タスクの出力から作った Provider なので、リソースを使うタスクより先に exportLibraryDefinitions が走る。
    val licensesDir = tasks.named("exportLibraryDefinitions").map {
        layout.buildDirectory.dir("generated/aboutLibraries/composeResources").get()
    }
    listOf("androidMain", "iosMain", "desktopMain").forEach { customDirectory(it, licensesDir) }
}
