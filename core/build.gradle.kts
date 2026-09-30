plugins { `java-library` }

dependencies {
    implementation("org.tomlj:tomlj:1.1.1")
    // Minecraft provides slf4j at runtime.
    compileOnly("org.slf4j:slf4j-api:2.0.17")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.slf4j:slf4j-api:2.0.17")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
