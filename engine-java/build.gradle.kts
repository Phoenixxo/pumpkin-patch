// The control engine for benchmarks: the sample components ported to plain Java, run through the
// same host interface, dispatch, and timers as the Wasm engines. Only the guest code differs.
plugins { `java-library` }

dependencies {
    api(project(":core"))

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}
