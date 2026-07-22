import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// [#183] UI/DB 非依存の Nostr プロトコル層を切り出す共通モジュール（第一段階: 純粋ドメイン）。
// composeApp から参照する。逆方向（core → composeApp）の依存は持たせない。
plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { it }

    // [#218] Desktop(Mac/JVM) ターゲット。composeApp の jvm("desktop") から参照される。
    jvm("desktop") {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            // crypto/ が使う暗号プリミティブ（NIP-01 署名 / SHA-256 / NIP-04・44 の JSON 解釈）
            implementation(libs.secp256k1)
            implementation(libs.kotlincrypto.sha2)
            implementation(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies {
            implementation(libs.secp256k1.jni.android)     // secp256k1 ネイティブ実体（Android）
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.secp256k1.jni.jvm)     // secp256k1 ネイティブ実体（JVM/Mac）
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "app.nostrdeck.core"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
