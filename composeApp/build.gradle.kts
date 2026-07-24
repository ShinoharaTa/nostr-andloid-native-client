import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties

// リリース署名の資格情報は keystore.properties（.gitignore 済み）から読む。無ければ未署名。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.serialization)
    alias(libs.plugins.sqldelight)
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
            implementation(libs.secp256k1)
            implementation(libs.kotlincrypto.sha2)
            implementation(libs.multiplatform.settings)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.window)          // FoldingFeature
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
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// [#218] Compose Desktop 配布設定。`./gradlew :composeApp:run` で起動、packageDmg で .dmg。
compose.desktop {
    application {
        mainClass = "app.nostrdeck.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "Nostrism"
            packageVersion = "1.0.0"
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
        // CI(ベータ自動リリース)は -PversionCode/-PversionName で上書きする
        // （versionCode は main のコミット数由来で単調増加）。ローカルは既定値のまま。
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
