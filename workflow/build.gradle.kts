import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "com.steve1316.automation_library.workflow"
    compileSdk = libs.versions.app.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.app.buildToolsVersion.get()

    defaultConfig {
        minSdk = libs.versions.app.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 编排层依赖原子动作层(仅接口,不依赖实现)
    api(project(":app"))

    // JSON 序列化(Scenario 可选持久化到文件)
    api(libs.kotlinx.serialization.json)

    // 协程(用于异步条件求值和 delay)
    api(libs.kotlinx.coroutines.android)

    // 单元测试
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

version = libs.versions.app.versionName.get()

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components.getByName("release"))
                groupId = "com.github.shadyrispy"
                artifactId = "automation_library-workflow"
                version = libs.versions.app.versionName.get()
            }
        }
        repositories {
            mavenLocal()
        }
    }
}
