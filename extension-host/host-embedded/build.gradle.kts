plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadowjar)
}

dependencies {
    implementation(project(":host-core"))

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(hostLibs.junit.jupiter)
    testRuntimeOnly(hostLibs.junit.launcher)
}

// The jar the iOS app embeds (jvm-ios/package.sh copies it into the app bundle).
tasks.shadowJar {
    archiveBaseName = "oneechan-host-embedded"
    archiveClassifier = ""
    archiveVersion = ""
    mergeServiceFiles()

    // Desktop-only resources that iOS can never use. An app cannot load a native library out of a
    // jar, and these are desktop builds anyway: on iOS images are decoded by the native backend,
    // HTTP bodies are decoded by the app, and nothing else calls JNA. The Java classes of
    // webp-imageio, zstd-kmp and JNA stay in the jar; only their platform binaries go.
    //   native/**   webp-imageio (Windows, Linux, macOS libraries)
    //   jni/**      zstd-kmp (Windows, Linux, macOS libraries)
    //   com/sun/jna/**/*jnidispatch*   JNA's platform binaries (not its Java classes)
    exclude("native/**", "jni/**", "com/sun/jna/**/*jnidispatch*")
    // Noto fonts from Suwayomi's AndroidCompat. Only its Typeface loads them, and the jar has
    // Oneechan's own android.graphics.Typeface (android-compat/src/overlay), which only names a
    // font; the native backend resolves it through CoreText. The desktop host keeps the fonts.
    exclude("font/**")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs(JvmFlags.extensionHost)
}
