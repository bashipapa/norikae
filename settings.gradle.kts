pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        kotlin("jvm") version "2.1.21"
        kotlin("plugin.serialization") version "2.1.21"
        kotlin("android") version "2.1.21"
        kotlin("plugin.compose") version "2.1.21"
        id("com.android.application") version "8.10.1"
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "norikae"

// 純Kotlinのロジック（時刻表・経路・アラーム計画・状態遷移）。どこでもビルド/テストできる
include(":core")

// Androidアプリ。Android SDK がある環境（Android Studio など）でだけ読み込む
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
if (hasAndroidSdk) {
    include(":app")
}
