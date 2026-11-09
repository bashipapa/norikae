plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

android {
    namespace = "norikae.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "norikae.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // CI でビルドするたびに署名が変わると上書きインストールできないため、固定のデバッグ鍵を使う
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // data/timetable.json をアプリに同梱する
    sourceSets["main"].assets.srcDir(rootProject.file("data"))
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")

    // 時刻表の週1回の自動更新
    implementation("androidx.work:work-runtime-ktx:2.10.1")

    // 位置情報・駅の範囲判定（ジオフェンス）・乗り物/歩行の判定
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
