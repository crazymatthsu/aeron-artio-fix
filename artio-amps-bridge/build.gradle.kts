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
}

/**
 * The SLF4J binding for this module's two mains, and for nothing else.
 *
 * Not `runtimeOnly`: on a `java-library` that is part of the published runtime classpath, so every
 * consumer inherits it - :artio-spring-boot would get slf4j-simple next to Spring Boot's logback,
 * SLF4J would pick one of the two arbitrarily, and half of logback-spring.xml would silently do
 * nothing. A consumer picks its own binding. Only `run` and `sowDump` are given this one, below.
 */
val mainLogging: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    "mainLogging"(libs.slf4j.simple)
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
    classpath(mainLogging)
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
 * `-Dbridge.amps.uri=...` on the Gradle command line reaches it, as it does `run`.
 */
tasks.register<JavaExec>("sowDump") {
    group = "application"
    description = "Print an AMPS SOW topic (or replay a journalled one) as printable FIX."
    mainClass.set("com.demo.artio.bridge.SowDump")
    classpath = sourceSets.main.get().runtimeClasspath + mainLogging
    workingDir = rootProject.projectDir
    systemProperties(
        mapOf(
            "org.slf4j.simpleLogger.defaultLogLevel" to "warn",
            "org.slf4j.simpleLogger.showThreadName" to "false"
        )
    )
    // SowDump reads bridge.amps.uri for its default URI; forwarded from the Gradle JVM exactly as
    // `run` forwards it, so `-Dbridge.amps.uri=tcp://host:9007/amps/fix` works on both.
    systemProperties(
        System.getProperties()
            .stringPropertyNames()
            .filter { it.startsWith("bridge.") }
            .associateWith { System.getProperty(it) }
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

    // The harness reads these; forwarded from the Gradle JVM's environment.
    listOf("AMPS_IMAGE", "AMPS_PLATFORM", "CONTAINER_ENGINE", "AMPS_IT").forEach { name ->
        val value = providers.environmentVariable(name)
        if (value.isPresent) {
            environment(name, value.get())
        }
    }

    // Never up to date, never cached, never restored. `Test` is a cacheable task type and this
    // repository sets org.gradle.caching=true, so `upToDateWhen { false }` on its own is not
    // enough: it forces the task to execute, and execution then finds a cache entry for the same
    // inputs and restores it as FROM-CACHE without running a test - a green build that ran nothing.
    // Whether this suite does anything depends on podman being installed and running, on the image
    // being present and on free host ports, none of which Gradle can see. Ask for it and it runs.
    doNotTrackState("starts real engines and containers")

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
    // A measurement is only worth taking if it is taken; see integrationTest.
    doNotTrackState("starts real engines and containers")
    testLogging { showStandardStreams = true }

    listOf("AMPS_IMAGE", "AMPS_PLATFORM", "CONTAINER_ENGINE", "AMPS_IT").forEach { name ->
        val value = providers.environmentVariable(name)
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
