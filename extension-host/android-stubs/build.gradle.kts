// Android API stub jar (android.* and dalvik.*, API 30), generated from the Apache-2.0 API signature
// in api/ (AOSP frameworks/base api/current.txt at android-11.0.0_r1, see api/METADATA). It replaces
// the repackaged SDK android.jar, whose licence does not allow redistribution.
//
//   src/generator  signature parser and Java stub writer (Kotlin)
//   main           the generated stub sources, compiled with javac to Java 8 class files like android.jar
//   test           tests of the generator and of the generated jar
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

val generator: SourceSet = sourceSets.create("generator")

dependencies {
    "generatorImplementation"(libs.kotlin.stdlib.jdk8)
    "generatorImplementation"(libs.asm)

    testImplementation(generator.output)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(hostLibs.junit.jupiter)
    testRuntimeOnly(hostLibs.junit.launcher)
}
configurations["testImplementation"].extendsFrom(configurations["generatorImplementation"])

val apiSignature = layout.projectDirectory.file("api/current.txt")
val enumOrder = layout.projectDirectory.file("api/enum-order.txt")

// Packages written to the jar. The other non-JDK packages android.* refers to are generated only so
// the stubs compile (org.apache.http.*, org.json, org.xmlpull.*, javax.microedition.khronos.*): like
// Suwayomi's android.jar, the jar leaves them out (AndroidCompat or extensions provide them).
val shippedPackages = listOf("android", "dalvik")
val compileOnlyPackages = listOf("javax.microedition.khronos", "org.apache.http", "org.json", "org.xmlpull")

val generateStubSources =
    tasks.register<JavaExec>("generateStubSources") {
        description = "Generates Java stub sources from the Android API signature."
        val out = layout.buildDirectory.dir("generated/stubs")
        classpath = generator.runtimeClasspath
        mainClass = "oneechan.stubs.GenerateStubsKt"
        inputs.file(apiSignature)
        inputs.file(enumOrder)
        outputs.dir(out)
        argumentProviders +=
            CommandLineArgumentProvider {
                listOf("--api", apiSignature.asFile.absolutePath, "--out", out.get().asFile.absolutePath, "--strict") +
                    listOf("--enum-order", enumOrder.asFile.absolutePath) +
                    (shippedPackages + compileOnlyPackages).flatMap { listOf("--include", it) }
            }
    }

// Refreshes api/enum-order.txt from the AOSP sources (network; only when the signature changes).
tasks.register<JavaExec>("updateEnumOrder") {
    description = "Reads the declaration order of the API's enum constants from the AOSP sources."
    classpath = generator.runtimeClasspath
    mainClass = "oneechan.stubs.EnumOrderKt"
    args("--api", apiSignature.asFile.absolutePath, "--out", enumOrder.asFile.absolutePath)
}

sourceSets.main {
    java.setSrcDirs(listOf(generateStubSources))
    kotlin.setSrcDirs(emptyList<Any>())
    resources.setSrcDirs(emptyList<Any>())
}

tasks.compileJava {
    // Java 8 class files (major 52) like android.jar. Not --release 8: API 30 also uses Java 9+ types
    // of Android's libcore (java.util.concurrent.Flow), so the stubs link against this JDK's API.
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
    options.compilerArgs.addAll(listOf("-Xlint:none", "-Xlint:-options", "-nowarn", "-Xmaxerrs", "10000"))
    options.isFork = true
    options.forkOptions.memoryMaximumSize = "2g"
}

tasks.jar {
    archiveBaseName = "android-stubs"
    archiveVersion = ""
    include(shippedPackages.map { "${it.replace('.', '/')}/**" } + "META-INF/**")
    // Compile-only nullness annotations (AndroidCompat ships the real ones).
    exclude("android/annotation/NonNull.class", "android/annotation/Nullable.class")
    from(layout.projectDirectory.dir("api")) {
        include("NOTICE", "LICENSE")
        into("META-INF/android-stubs")
    }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    systemProperty("oneechan.stubsJar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
    dependsOn(tasks.jar)
}
