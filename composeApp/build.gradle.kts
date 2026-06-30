import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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

    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)
            implementation(libs.androidx.lifecycle.viewmodel)

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
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.android)
            implementation(libs.secp256k1.jni.android)     // secp256k1 ネイティブ実体
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "app.nostrdeck"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.nostrdeck"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
