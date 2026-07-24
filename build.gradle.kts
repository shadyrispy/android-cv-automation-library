import java.net.URI

// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    repositories {
        // 国内镜像:优先使用阿里云镜像避免 dl.google.com SSL 失败
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
    dependencies {
        classpath(libs.androidGradleBuildTools)
        classpath(libs.kotlinGradlePlugin)
    }
    configurations.classpath {
        resolutionStrategy {
            // 临时降级:本地缓存中只有这些版本(网络不通无法下载 1.18.1 / 0.0.9-alpha03)
            force("com.android.tools.build:bundletool:1.17.1")
            force("com.google.testing.platform:core-proto:0.0.9-alpha02")
        }
    }
}

plugins {
    alias(libs.plugins.ktlint) apply false
}

extra["minSdkVersion"] = libs.versions.app.minSdk.get().toInt()
extra["targetSdkVersion"] = libs.versions.app.targetSdk.get().toInt()
extra["compileSdkVersion"] = libs.versions.app.compileSdk.get().toInt()

tasks.register("clean", Delete::class.java) {
    delete(layout.buildDirectory)
}