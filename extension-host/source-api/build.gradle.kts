import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

// The Tachiyomi/Mihon extension API surface (eu.kanade.tachiyomi.*) and the extension-loading
// utilities, compiled from the pinned Suwayomi-Server submodule (MPL-2.0 / Apache-2.0).
// Server-coupled files are excluded and re-implemented under the same FQCN in src/overlay/kotlin.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

private val vendorDir = "../vendor/suwayomi/server/src/main/kotlin"

val syncVendorSources = tasks.register<Sync>("syncVendorSources") {
    from(vendorDir) {
        include(
            "eu/kanade/tachiyomi/App.kt",
            "eu/kanade/tachiyomi/AppModule.kt",
            "eu/kanade/tachiyomi/network/**",
            "eu/kanade/tachiyomi/source/**",
            "eu/kanade/tachiyomi/util/**",
            "suwayomi/tachidesk/manga/impl/util/lang/RxCoroutineBridge.kt",
            "suwayomi/tachidesk/manga/impl/util/lang/JsonObject.kt",
            "suwayomi/tachidesk/manga/impl/util/BytecodeEditor.kt",
            "suwayomi/tachidesk/manga/impl/util/ChildFirstURLClassLoader.kt",
            "suwayomi/tachidesk/manga/impl/util/AndroidManifestParser.kt",
            "suwayomi/tachidesk/manga/impl/util/ResourceArscIconParser.kt",
            "suwayomi/tachidesk/manga/impl/extension/github/**",
            "suwayomi/tachidesk/manga/model/dataclass/ExtensionStore.kt",
            "suwayomi/tachidesk/manga/model/dataclass/ExtensionInfo.kt",
        )
        exclude(
            // Server features we do not ship (local source lives in Swift).
            "eu/kanade/tachiyomi/source/local/**",
            "eu/kanade/tachiyomi/util/storage/EpubFile.kt",
            // Overlaid (see src/overlay/kotlin).
            "eu/kanade/tachiyomi/network/NetworkHelper.kt",
            "eu/kanade/tachiyomi/network/PersistentCookieStore.kt",
            "eu/kanade/tachiyomi/network/interceptor/CloudflareInterceptor.kt",
            "eu/kanade/tachiyomi/network/interceptor/UserAgentInterceptor.kt",
        )
    }
    into(layout.buildDirectory.dir("vendor-src/kotlin"))
}

sourceSets.main {
    kotlin.srcDirs(syncVendorSources, "src/overlay/kotlin", "src/main/kotlin")
    // Patched third-party classes shadowing their library copies (checked at host boot).
    java.srcDir("src/overlay/java")
}

dependencies {
    api(project(":android-compat"))

    // Libraries extensions are compiled against (versions locked by the vendored catalog).
    api(libs.bundles.okhttp)
    api(libs.okio)
    api(libs.jsoup)
    api(libs.rxjava)
    api(libs.injekt)
    api(libs.serialization.json)
    api(libs.serialization.json.okio)
    api(libs.serialization.protobuf)
    api(libs.serialization.xml.core)
    api(libs.serialization.xml)
    api(libs.bouncycastle)

    implementation(libs.asm)
    implementation(libs.cache4k)
    implementation(libs.commonscompress)
    implementation(libs.sort)
    implementation(libs.apksig)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(hostLibs.junit.jupiter)
    testImplementation(hostLibs.mockwebserver)
    testImplementation(hostLibs.junit.params)
    testRuntimeOnly(hostLibs.junit.launcher)
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions.suppressWarnings = true
}
