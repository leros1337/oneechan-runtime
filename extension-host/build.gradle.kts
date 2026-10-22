import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

subprojects {
    group = "oneechan"
    version = "0.1.0"

    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            val javaVersion = JavaVersion.toVersion(rootProject.libs.versions.jvmTarget.get())
            sourceCompatibility = javaVersion
            targetCompatibility = javaVersion
        }
        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            // Vendored Android stubs/compat code produces a lot of noise we do not own.
            options.compilerArgs.addAll(listOf("-Xlint:none", "-nowarn"))
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            // Images are drawn off screen only. Without this, AWT on macOS starts a GUI "java" app and
            // asks for Input Monitoring and Screen Recording access.
            systemProperty("java.awt.headless", "true")
            testLogging {
                events("failed", "skipped")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }

    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget = JvmTarget.fromTarget(rootProject.libs.versions.jvmTarget.get())
        }
    }
}
