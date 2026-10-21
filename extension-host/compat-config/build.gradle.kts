import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

// Vendored as-is: Suwayomi AndroidCompat/Config (MPL-2.0).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

private val vendorDir = "../vendor/suwayomi/AndroidCompat/Config/src/main/java"

sourceSets.main {
    kotlin.srcDir(vendorDir)
    java.srcDir(vendorDir)
}

dependencies {
    api(libs.kotlin.stdlib.jdk8)
    api(libs.coroutines.core)
    api(libs.serialization.json)
    api(libs.koin.core)
    api(libs.slf4japi)
    api(libs.logback)
    api(libs.kotlinlogging)
    api(libs.appdirs)
    api(libs.config)
    api(libs.config4k)
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions.suppressWarnings = true
}
