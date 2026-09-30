plugins { `java-library` }

val endive = providers.gradleProperty("endive_version").get()

dependencies {
    api(project(":core"))
    implementation("run.endive.cm:runtime:$endive")
    implementation("run.endive.cm:parser:$endive")
    implementation("run.endive.cm:wasm-tools:$endive")
    implementation("run.endive:compiler:$endive")
    compileOnly("org.slf4j:slf4j-api:2.0.17")
    testImplementation("org.slf4j:slf4j-api:2.0.17")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
    implementation("org.tomlj:tomlj:1.1.1")
    annotationProcessor("run.endive.cm:bindgen-processor:$endive")
    // The bindgen processor looks WIT up through the Filer's class path.
    compileOnly(files(rootProject.file("wit/src/main/resources")))

    testImplementation(project(":engine-java"))
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<JavaCompile>("compileJava") {
    options.compilerArgs.add("-Xlint:-processing")
}

tasks.named<Test>("test") {
    systemProperty("guests.dir", rootProject.file("guests/out").absolutePath)
    maxHeapSize = "2g"
}
