plugins {
    id("net.fabricmc.fabric-loom")
}

base { archivesName.set("pumpkin-patch") }

repositories {
    maven(uri(System.getProperty("user.home") + "/.m2-pumpkin-patch")) {
        content { includeGroupByRegex("run\\.endive.*") }
    }
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")

    implementation(project(":core"))
    implementation(project(":engine-endive"))
    implementation(project(":engine-java"))
    // Jar-in-jar: core, the runtime adapter, Endive, and their pure-Java dependencies.
    include(project(":core"))
    include(project(":engine-endive"))
    include(project(":engine-java"))
    // Endive is merged into one jar first; see endive-bundle/build.gradle.kts.
    include(project(":endive-bundle"))
    include("io.roastedroot:zerofs:0.1.0")
    include("org.tomlj:tomlj:1.1.1")
    include("org.antlr:antlr4-runtime:4.11.1")
    include("org.checkerframework:checker-qual:3.33.0")
}

loom {
    runs {
        named("client") {
            programArgs("--username", "PatchTester")
            vmArg("-Xmx3G")
            // -Dpumpkinpatch.* on the Gradle command line reaches the game JVM.
            providers.systemPropertiesPrefixedBy("pumpkinpatch.").get().forEach { (k, v) -> vmArg("-D$k=$v") }
        }
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") { expand("version" to project.version) }
}
