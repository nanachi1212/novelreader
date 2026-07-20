import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    androidTarget()
    jvm("desktop")

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                // CMP 1.9 起 material3 不再帶 icons，需另外引用（最後的多平台版本是 1.7.3）
                implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        // 共用程式碼放在 src/jvmShared/kotlin，同時掛進兩個平台 source set：
        // 兩邊都是 JVM，可直接用 java.io 等 API，避開中間 source set 無法使用 JDK 的限制
        val androidMain by getting {
            kotlin.srcDir("src/jvmShared/kotlin")
            dependencies {
                implementation(libs.androidx.core.ktx)
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.documentfile)
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.juniversalchardet)
                implementation(libs.opencc4j)
                implementation(libs.jsoup)
            }
        }
        val desktopMain by getting {
            kotlin.srcDir("src/jvmShared/kotlin")
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.juniversalchardet)
                implementation(libs.opencc4j)
                implementation(libs.jsoup)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

android {
    namespace = "app.novelreader"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.novelreader"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("release") {
            val ksFile = rootProject.file("keystore/novelreader-release.jks")
            if (ksFile.exists()) {
                // CI 未設 secret 時環境變數是空字串而非 null，需一併視為未設定
                val ksPass = System.getenv("KEYSTORE_PASSWORD")
                    .takeUnless { it.isNullOrBlank() } ?: "novelreader"
                storeFile = ksFile
                storePassword = ksPass
                keyAlias = "novelreader"
                keyPassword = ksPass
            }
        }
    }

    buildTypes {
        release {
            // opencc4j 依賴資源檔字典，先不開 minify 以免被剝離
            isMinifyEnabled = false
            if (rootProject.file("keystore/novelreader-release.jks").exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES"
            )
        }
    }
}

compose.desktop {
    application {
        mainClass = "app.novelreader.MainKt"

        buildDir = project.rootDir.resolve("release")

        buildTypes.release.proguard {
            // opencc4j / juniversalchardet 依賴資源檔，避免被 proguard 剝離
            isEnabled.set(false)
        }

        nativeDistributions {
            outputBaseDir.set(project.rootDir.resolve("release/dist"))
            targetFormats(TargetFormat.Msi)
            packageName = "NovelReader"
            packageVersion = "1.0.0"
            windows {
                menuGroup = "NovelReader"
            }
        }
    }
}
