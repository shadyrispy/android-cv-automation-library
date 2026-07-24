import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    alias(libs.plugins.ktlint)
}

ktlint {
    android = true
    ignoreFailures = true
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
    }
}

android {
    namespace = "com.steve1316.automation_library"
    compileSdk = libs.versions.app.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.app.buildToolsVersion.get()

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        minSdk = libs.versions.app.minSdk.get().toInt()
        buildConfigField("String", "VERSION_NAME", "\"${libs.versions.app.versionName.get()}\"")
        consumerProguardFiles("consumer-rules.pro")

        // 本地精简版 OpenCV: 多 ABI (由本地 jniLibs 提供 libopencv_java4.so)
        //   arm64-v8a    : 现代手机(64位 ARM, 主流)
        //   armeabi-v7a  : 老旧手机(32位 ARM, NEON 加速)
        //   x86_64       : 模拟器测试用
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

version = libs.versions.app.versionName.get()

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components.getByName("release"))
                groupId = "com.github.steve1316"
                artifactId = "automation_library"
                version = libs.versions.app.versionName.get()
            }
        }
		
        // Add Maven Local repository for local testing without publishing to JitPack.
        repositories {
            mavenLocal()
        }
    }
}

dependencies {
    // ////// Dependencies available to the project ////////

    api(libs.bundles.androidApp)

    // CardView for permission guide UI cards.
    api(libs.androidx.cardview)

    // Activity-ktx for registerForActivityResult in permission guide.
    api(libs.androidx.activity)

    // OpenCV Android for image processing.
    // 注:已切换为本地精简版 OpenCV(仅 core/imgproc/imgcodecs + Java 绑定)
    // - Java 源码:app/src/main/java/org/opencv/
    // - native 库:app/src/main/jniLibs/arm64-v8a/libopencv_java4.so (8.1MB,官方约 40MB)
    // 原依赖:api(libs.opencv.android.sdk)

    // string-similarity to compare the string from OCR to the strings in data.
    api(libs.stringSimilarity)

    // Kord for Discord integration.
    api(libs.kord.core)

    // Klaxon to parse JSON data files.
    api(libs.klaxon)

    // EventBus to communicate between modules and to the Javascript frontend.
    api(libs.eventbus)

    // ONNX Runtime for PP-OCRv6 inference (replacing Tesseract + ML Kit).
    api(libs.onnxruntime.android)

    // Twitter4j is used to connect to the Twitter API.
    api(libs.twitter4j.core)

    // AppUpdater for notifying users when there is a new update available.
    api(libs.appUpdater)
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.app.jvm.toolchain.get().toInt()))
    }
}
