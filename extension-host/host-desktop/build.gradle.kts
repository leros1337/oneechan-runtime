plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadowjar)
    application
}

dependencies {
    implementation(project(":host-core"))

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(hostLibs.junit.jupiter)
    testRuntimeOnly(hostLibs.junit.launcher)
}

application {
    mainClass = "oneechan.host.desktop.MainKt"
    applicationDefaultJvmArgs = JvmFlags.extensionHost
}

tasks.shadowJar {
    archiveBaseName = "oneechan-extension-host"
    archiveClassifier = ""
    archiveVersion = ""
    mergeServiceFiles()
    manifest { attributes("Main-Class" to "oneechan.host.desktop.MainKt") }
}

tasks.named<JavaExec>("run") {
    // ./gradlew :host-desktop:run --args="--data /tmp/oneechan-host"
    workingDir = rootProject.projectDir
}

tasks.test {
    jvmArgs(JvmFlags.extensionHost)
}
