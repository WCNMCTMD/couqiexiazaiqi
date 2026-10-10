import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 读取签名口令：优先 local.properties（不入库），避免口令硬编码进代码库
val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    localPropsFile.inputStream().use { stream -> localProps.load(stream as java.io.InputStream) }
}

android {
    namespace = "com.java.myapplication"
    compileSdk = 35
    buildToolsVersion = "36.0.0"

    signingConfigs {
        create("release") {
            val ksFile = rootProject.file("keystore/multi-downloader.jks")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = localProps.getProperty("MD_STORE_PASSWORD") ?: ""
                keyAlias = localProps.getProperty("MD_KEY_ALIAS") ?: "multidl"
                keyPassword = localProps.getProperty("MD_KEY_PASSWORD")
                    ?: localProps.getProperty("MD_STORE_PASSWORD") ?: ""
            }
            // 显式开启 v1/v2/v3，兼容小米/MIUI 等对 v1 要求严格的机型
            // （AGP 默认在 minSdk>=24 时关闭 v1，导致红米报 packageInfo is null）
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    defaultConfig {
        applicationId = "com.java.myapplication"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Cronet 的 AAR 含 arm64-v8a / armeabi-v7a / x86 / x86_64 四套 .so（合计约 17MB）。
        // 本项目只面向 arm64 真机分发，仅保留 arm64-v8a，避免 APK 体积膨胀。
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // Cronet 的 .so 需要被解压到 nativeLibraryDir 才能加载（历史兼容 API 24–35）
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Force use of ARM64 binaries for AAPT2 in Proot environment
// 注意：Kotlin DSL 中字符串插值应写 $requested.version，不能写成 ${'$'}{...}，否则会变成字面量而失效
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "com.android.tools.build" && requested.name == "aapt2") {
            useTarget("com.android.tools.build:aapt2:${requested.version}:linux-aarch64")
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // Cronet：Chromium 网络栈（TLS 指纹 = Chrome），用于过 Cloudflare 的 JA3 指纹拦截。
    // 仅 hentaipaw 站点使用（见 engine/CronetHttp.kt），不影响其它站点。
    implementation(libs.cronet.embedded)
    // ffmpeg-kit（min 档）：内置 ffmpeg 原生库，用于把 HLS 分片(.ts)转封装为可播放 .mp4。
    // 见 engine/HlsDownloader.kt#muxTsToMp4。min 档仅含 ffmpeg 本体，体积最小。
    implementation(libs.ffmpeg.kit.min)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
