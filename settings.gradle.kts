pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "pumpkin-patch"

include("wit", "core", "engine-endive", "engine-java", "endive-bundle", "fabric")
