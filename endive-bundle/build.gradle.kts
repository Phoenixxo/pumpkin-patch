// Endive and Endive CM merged into one jar for the Fabric mod to nest.
//
// Both publish artifacts named `runtime` and `wasm-tools`. Jar-in-jar nests by file name, so
// including them one by one silently drops one of each pair. Merging sidesteps the collision.
// The classes are all under distinct packages. Their module-info descriptors overlap, but Fabric
// loads nested jars on the classpath, where those are unused. Each jar's THIRD-PARTY.txt differs,
// so every one is kept under META-INF/licenses/ with its artifact's name.
import java.util.zip.ZipFile

plugins {
    `java-library`
}

val endive = providers.gradleProperty("endive_version").get()
val merged: Configuration by configurations.creating { isTransitive = false }

dependencies {
    for (a in listOf(
        "run.endive.cm:runtime", "run.endive.cm:parser", "run.endive.cm:types", "run.endive.cm:canonical-abi",
        "run.endive.cm:wasm-tools", "run.endive:runtime", "run.endive:wasm", "run.endive:compiler",
        "run.endive:wasm-tools", "run.endive:wasi", "run.endive:log",
    )) {
        merged("$a:$endive")
    }
}

val licenses = tasks.register("thirdPartyLicenses") {
    val out = layout.buildDirectory.dir("third-party")
    inputs.files(merged)
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        for (a in merged.resolvedConfiguration.resolvedArtifacts) {
            val id = a.moduleVersion.id
            val zip = ZipFile(a.file)
            zip.use { z ->
                z.getEntry("THIRD-PARTY.txt")?.let { e ->
                    z.getInputStream(e).use { dir.resolve("${id.group}.${id.name}-THIRD-PARTY.txt").writeBytes(it.readBytes()) }
                }
            }
        }
    }
}

tasks.jar {
    dependsOn(merged)
    from(licenses) { into("META-INF/licenses") }
    from(merged.map { zipTree(it) }) {
        exclude("module-info.class", "META-INF/MANIFEST.MF", "THIRD-PARTY.txt")
    }
    duplicatesStrategy = DuplicatesStrategy.FAIL
}
