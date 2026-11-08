plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // テストから data/timetable.json を読む
    systemProperty("timetable.path", rootProject.file("data/timetable.json").absolutePath)
    // 個人用のローカル時刻表（git管理外）。あれば中身の整合性もテストする
    systemProperty("sanyo.local.path", rootProject.file("data/local/sanyo.json").absolutePath)
}
