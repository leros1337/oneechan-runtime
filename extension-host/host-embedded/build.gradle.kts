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
}

tasks.test {
    useJUnitPlatform()
    jvmArgs(JvmFlags.extensionHost)
}
