/**
 * The Artio -> AMPS bridge: a `FixMessageSink` that hands every message off to a publisher thread
 * through an off-heap ring buffer, routes it onto the `artio-fix` flow's topics, and publishes the
 * raw FIX bytes with the AMPS byte-array publish. Also the non-Spring demo entry point
 * (`./gradlew :artio-amps-bridge:run`) and the "did it arrive" utility
 * (`./gradlew :artio-amps-bridge:sowDump`).
 *
 * Three suites, split by what they need:
 *
 *   test              routing rules, the tag-presence scan, the ring-buffer hand-off against an
 *                     in-memory port, overflow accounting, config binding, reconnect back-off.
 *                     No AMPS, no Artio runtime, no sockets. Runs inside `build`.
 *   integrationTest   a real AMPS from :amps-test-harness, a real Artio acceptor and a real
 *                     QuickFIX/J initiator: five orders in, SOW records out. Skips with a reason
 *                     when podman or the image is missing.
 *   publishBenchmark  a non-asserting throughput measurement, excluded from `integrationTest` and
 *                     from `check`. Numbers land in docs/03.
 *
 * See docs/03-artio-amps-bridge-design.md.
 */
plugins {
    `java-library`
    application
}

dependencies {
    // The engine, the sink SPI and the flyweight. `api` because AmpsFixPublisher IS a
    // FixMessageSink and BridgeConfig reuses the engine's IdleStrategyType, so a consumer
    // (the Spring Boot module) compiles against both.
    api(project(":artio-engine"))
    // AmpsPublishPort declares AMPS's checked AMPSException, and AmpsClientPort's reconnect logic
    // is written against DisconnectedException. Both are on this module's public surface.
    api(libs.amps.client)

    // The ring buffer, the AgentRunner and UnsafeBuffer. Already on the compile classpath through
    // :artio-engine's `api`, declared here because this module uses it directly.
    implementation(libs.agrona)
    implementation(libs.slf4j.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.awaitility)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)

    // This module has a main; consumers pick their own binding.
    runtimeOnly(libs.slf4j.simple)
}

/**
 * The integration suite compiles against main and the unit-test helpers but runs on its own task,
 * so a plain `build` never needs a container.
 */
val integrationTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += output + compileClasspath
}

configurations["integrationTestImplementation"]
    .extendsFrom(configurations.testImplementation.get())
configurations["integrationTestRuntimeOnly"]
    .extendsFrom(configurations.testRuntimeOnly.get())

// Declared here rather than in the block above: the configurations these name are created by the
// sourceSets block, which runs after it.
dependencies {
    // The throwaway AMPS.
    "integrationTestImplementation"(project(":amps-test-harness"))
    // "The other FIX engine" that sends the orders.
    "integrationTestImplementation"(project(":quickfixj-counterparty"))
}

/**
 * The three flags any JVM that runs Artio needs; see docs/01 section 12 and the long comment in
 * artio-engine/build.gradle.kts. This module launches a FixEngine in `run` and in its integration
 * suite, so all three go on every Test, JavaExec and on the `application` start script.
 */
val artioJvmArgs = listOf(
    "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED"
)

tasks.withType<Test>().configureEach {
    jvmArgs(artioJvmArgs)
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs(artioJvmArgs)
}

application {
    mainClass.set("com.demo.artio.bridge.BridgeMain")
    applicationDefaultJvmArgs = artioJvmArgs + listOf(
        // Artio, Aeron and the AMPS client all log at INFO and between them bury the bridge's own
        // output; the floor is WARN and this project's loggers are lifted back to INFO.
        "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn",
        "-Dorg.slf4j.simpleLogger.log.com.demo.artio=info",
        "-Dorg.slf4j.simpleLogger.showDateTime=true",
        "-Dorg.slf4j.simpleLogger.dateTimeFormat=HH:mm:ss.SSS",
        "-Dorg.slf4j.simpleLogger.showThreadName=false"
    )
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    // Ctrl-C has to reach the JVM so the shutdown hook can flush AMPS.
    standardInput = System.`in`
    // Point the bridge somewhere else without editing bridge.properties, exactly as amps-demo's
    // bootRun does:
    //   ./gradlew :artio-amps-bridge:run -Dbridge.amps.uri=tcp://host:9007/amps/fix -Dartio.port=9881
    systemProperties(
        System.getProperties()
            .stringPropertyNames()
            .filter { it.startsWith("bridge.") || it.startsWith("artio.") }
            .associateWith { System.getProperty(it) }
    )
}

/**
 * `./gradlew :artio-amps-bridge:sowDump --args="--topic fix.orders"`
 *
 * Prints what a SOW topic holds, as printable FIX. `--replay fix.raw` counts and prints the
 * journal from the epoch instead. Needs a running AMPS; `amps-server/scripts/amps.sh start`.
 */
tasks.register<JavaExec>("sowDump") {
    group = "application"
    description = "Print an AMPS SOW topic (or replay a journalled one) as printable FIX."
    mainClass.set("com.demo.artio.bridge.SowDump")
    classpath = sourceSets.main.get().runtimeClasspath
    workingDir = rootProject.projectDir
    systemProperties(
        mapOf(
            "org.slf4j.simpleLogger.defaultLogLevel" to "warn",
            "org.slf4j.simpleLogger.showThreadName" to "false"
        )
    )
}

val integrationTestTask = tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Artio acceptor + bridge + QuickFIX/J initiator against a real AMPS."
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter(tasks.test)

    // The harness resolves the compose file relative to the repository root.
    workingDir = rootProject.projectDir

    // Aeron and Artio directories go under java.io.tmpdir with a unique suffix and are deleted on
    // close, as in :artio-engine. Override with -Dartio.it.dir to keep them.
    systemProperty("artio.it.dir", System.getProperty("artio.it.dir", ""))

    // The measurement, not the assertions: a benchmark that runs on every `build` is a slow build,
    // and one that asserts nothing cannot fail usefully.
    filter { excludeTestsMatching("*Benchmark") }

    // Each variable is a declared INPUT, not merely forwarded: this repository sets
    // org.gradle.caching=true, and an environment variable that is only forwarded is invisible to
    // the cache key - so a run WITHOUT an AMPS image would cache an all-skipped result and the
    // next run WITH one would restore it instead of executing. The reasoning is spelled out at
    // length in amps-test-harness/build.gradle.kts.
    listOf("AMPS_IMAGE", "AMPS_PLATFORM", "CONTAINER_ENGINE", "AMPS_IT").forEach { name ->
        val value = providers.environmentVariable(name)
        inputs.property(name, value.orElse(""))
        if (value.isPresent) {
            environment(name, value.get())
        }
    }

    // ...and excluded from the cache and from up-to-date checks anyway. Correct inputs make the key
    // as good as Gradle can see; they cannot make it complete, because whether this suite does
    // anything depends on podman being installed and running and on free host ports. Ask for it and
    // it runs.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }

    testLogging {
        showStandardStreams = true
    }
}

/**
 * `./gradlew :artio-amps-bridge:publishBenchmark`
 *
 * Pushes 50k hand-built FIX messages through the sink into a real AMPS and reports msgs/s and peak
 * ring occupancy. Asserts nothing; it exists so docs/03 can quote a measured number rather than a
 * guess. Not wired into `check`.
 */
tasks.register<Test>("publishBenchmark") {
    group = "verification"
    description = "Measure bridge publish throughput against a real AMPS instance."
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    workingDir = rootProject.projectDir
    filter { includeTestsMatching("*Benchmark") }
    systemProperty("bridge.benchmark", "true")
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    testLogging { showStandardStreams = true }

    listOf("AMPS_IMAGE", "AMPS_PLATFORM", "CONTAINER_ENGINE", "AMPS_IT").forEach { name ->
        val value = providers.environmentVariable(name)
        inputs.property(name, value.orElse(""))
        if (value.isPresent) {
            environment(name, value.get())
        }
    }
}

// `check` runs the integration suite. It opts out with a reason when podman or the image is absent,
// so this stays green on a machine that has never seen AMPS and does real work on one that has.
tasks.named("check") {
    dependsOn(integrationTestTask)
}
