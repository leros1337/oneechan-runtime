rootProject.name = "oneechan-extension-host"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        // Library versions are pinned to the vendored Suwayomi-Server: extensions are compiled
        // against these APIs, so binary compatibility depends on matching them exactly.
        create("libs") { from(files("vendor/suwayomi/gradle/libs.versions.toml")) }
        create("hostLibs") { from(files("gradle/host.versions.toml")) }
    }
    repositories {
        mavenCentral()
        google()
        maven("https://jitpack.io")
    }
}

include(":compat-config")
include(":android-stubs")
include(":android-compat")
include(":source-api")
include(":host-core")
include(":host-desktop")
include(":host-embedded")
