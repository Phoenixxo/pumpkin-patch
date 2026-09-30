plugins {
    id("net.fabricmc.fabric-loom") version "1.18.2" apply false
}

allprojects {
    group = "dev.pumpkinmc.patch"
    version = "0.1.0"
}

subprojects {
    repositories {
        maven(uri(System.getProperty("user.home") + "/.m2-pumpkin-patch")) {
            name = "EndiveLocal"
            content { includeGroupByRegex("run\\.endive.*") }
        }
        mavenCentral()
    }
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(25))
        }
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(25)
            options.encoding = "UTF-8"
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
        }
    }
}
