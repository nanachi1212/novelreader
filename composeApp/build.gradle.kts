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
                implementation(libs.compose.resources)
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
                // 壓縮檔匯入（僅桌面）：zip/7z 用 commons-compress；
                // xz 是它的 optional 依賴，7z 的 LZMA2 解壓必需，須明列
                implementation(libs.commons.compress)
                implementation(libs.xz)
                implementation(libs.junrar)
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
        versionCode = 16
        versionName = "1.3.9"
    }

    signingConfigs {
        create("release") {
            val ksFile = rootProject.file("keystore/novelreader-release.jks")
            val ksPass = System.getenv("KEYSTORE_PASSWORD").takeUnless { it.isNullOrBlank() }
            if (ksFile.exists() && ksPass != null) {
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
            if (rootProject.file("keystore/novelreader-release.jks").exists() &&
                !System.getenv("KEYSTORE_PASSWORD").isNullOrBlank()
            ) {
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

    lint {
        // AGP 8.7 lint 的 lifecycle detector 與 Kotlin 2.2 analysis API 不相容。
        disable += "NullSafeMutableLiveData"
    }
}

compose.desktop {
    application {
        mainClass = "app.novelreader.MainKt"

        // buildDir 用預設的 build/（已 gitignore），避免 Gradle 中繼產物跟真正要打包的東西混在一起，
        // 之前混在 release/ 裡導致使用者在一堆 classes/generated/intermediates 資料夾裡找不到 exe
        buildTypes.release.proguard {
            // opencc4j / juniversalchardet 依賴資源檔，避免被 proguard 剝離
            isEnabled.set(false)
        }

        nativeDistributions {
            outputBaseDir.set(layout.buildDirectory.dir("native-dist"))
            targetFormats(TargetFormat.Msi)
            packageName = "NovelReader"
            packageVersion = "1.3.9"
            // jlink 只靜態分析 bytecode 偵測所需模組，Charset.forName() 是執行期字串查找，
            // 偵測不到 jdk.charsets（Big5/GBK 都在這個模組，只有 GB18030 內建在 java.base），
            // 必須手動加，不然打包版永遠無法真正使用 Big5/GBK
            modules("jdk.charsets")
            windows {
                menuGroup = "NovelReader"
            }
        }
    }
}

compose.resources {
    packageOfResClass = "app.novelreader.generated.resources"
}

// 產生可直接複製到隨身碟使用的免安裝資料夾：release/NovelReader/NovelReader.exe（路徑淺、資料夾裡沒有多餘雜物）
tasks.register<Sync>("packagePortable") {
    group = "distribution"
    description = "打包成隨身碟可直接用的免安裝資料夾 release/NovelReader/"
    dependsOn("createReleaseDistributable")
    from(layout.buildDirectory.dir("native-dist/main-release/app/NovelReader"))
    into(project.rootDir.resolve("release/NovelReader"))
}
