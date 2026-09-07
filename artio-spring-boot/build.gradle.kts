/**
 * The Artio engine and the AMPS bridge as a Spring Boot application, and the answer to the TODO's
 * fourth question. Nothing on the message path differs from `:artio-amps-bridge`: the same
 * `AmpsFixPublisher`, the same off-heap ring buffer, the same `ArtioRuntime`. What Spring adds is
 * `@ConfigurationProperties` binding with validation, profiles, and a `SmartLifecycle` pair that
 * gets the start-up and shutdown ORDER right without a hand-written shutdown hook.
 *
 * The analysis, with the measured numbers, is in docs/04-spring-boot-feasibility.md.
 *
 * Two suites:
 *
 *   test              context loads with the runtime disabled; property binding, including a
 *                     custom route list; validation rejects bad values; the lifecycle phases
 *                     really do order the two components. No AMPS, no Artio, no sockets.
 *   integrationTest   the whole flow twice over - once in-process via SpringApplication, once as
 *                     the fat jar in a subprocess killed with SIGTERM. Needs podman and the AMPS
 *                     image; skips with a reason when either is missing.
 *
 * The fat jar is built (unlike amps-demo's fix42-publisher, which disables `bootJar`) because
 * "does the fat jar run?" is part of the feasibility question this module exists to answer.
 */
plugins {
    java
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter)
    // Hibernate Validator, so the jakarta constraints on the two @ConfigurationProperties records
    // are actually enforced at bind time rather than being decoration.
    implementation(libs.spring.boot.starter.validation)
    // The bridge exposes :artio-engine and :fix-codecs as `api`, so this one line brings the
    // publisher, the runtime, the sink SPI and the generated codecs.
    implementation(project(":artio-amps-bridge"))
    implementation(libs.slf4j.api)

    annotationProcessor(platform(libs.spring.boot.bom))
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.awaitility)
    // spring-boot-starter-test brings junit-jupiter but not the platform launcher, and Gradle's
    // test worker needs it on the runtime classpath. The version comes from spring-boot's own
    // import of junit-bom.
    testRuntimeOnly(libs.junit.platform.launcher)
}

/**
 * `:artio-amps-bridge` and `:quickfixj-counterparty` declare `runtimeOnly(slf4j-simple)` because
 * they have mains of their own. A `runtimeOnly` dependency of a project dependency IS on the
 * consumer's runtime classpath, so without this both slf4j-simple and Spring Boot's logback would
 * be present, SLF4J would pick one arbitrarily and print a "multiple bindings" warning, and half
 * the configuration in logback-spring.xml would silently do nothing.
 *
 * Verified with `./gradlew :artio-spring-boot:dependencies --configuration runtimeClasspath`:
 * without the exclude, `org.slf4j:slf4j-simple` appears via project :artio-amps-bridge.
 */
configurations.configureEach {
    exclude(group = "org.slf4j", module = "slf4j-simple")
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
    // The throwaway AMPS in podman.
    "integrationTestImplementation"(project(":amps-test-harness"))
    // "The other FIX engine" that sends the five-message order scenario.
    "integrationTestImplementation"(project(":quickfixj-counterparty"))
}

/**
 * The three flags any JVM that runs Artio needs; see docs/01 section 12. The first two are
 * agrona's (`UnsafeApi` reaches `jdk.internal.misc.Unsafe`), the third is Artio's
 * (`ReceiverEndPoints` reflects on `sun.nio.ch.SelectorImpl.selectedKeys` in a static
 * initialiser). Spring Boot does not change this: the fat jar needs them exactly as the plain
 * classpath does, and `java -jar` has nowhere to get them from except the command line or
 * JAVA_TOOL_OPTIONS.
 */
val artioJvmArgs = listOf(
    "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED"
)

tasks.withType<Test>().configureEach {
    jvmArgs(artioJvmArgs)

    // BridgeMainPropertyAliasesTest binds the SHIPPED bridge.properties through Spring and compares
    // the result with what BridgeConfig.fromProperties makes of the same file, which is the only way
    // "one file configures either entry point" is a fact rather than a claim. The file belongs to
    // :artio-amps-bridge, so its path is passed in rather than guessed from a working directory,
    // and declared as an input so editing it re-runs the test.
    val bridgeProperties = rootProject.file("artio-amps-bridge/bridge.properties")
    inputs.file(bridgeProperties).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("bridge.properties.file", bridgeProperties.absolutePath)
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs(artioJvmArgs)
}

/**
 * `bootJar` produces `build/libs/artio-spring-boot-<version>.jar`, and `jar` is disabled so that
 * directory holds exactly one jar. The integration suite runs it as a subprocess and needs to name
 * it without guessing between `-plain.jar` and the executable one.
 */
tasks.named<Jar>("jar") {
    enabled = false
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir
    // Ctrl-C has to reach the JVM so Spring's shutdown hook can close the context, which is what
    // runs the two SmartLifecycles in order and flushes AMPS.
    standardInput = System.`in`
    // Point the bridge somewhere else without editing application.yml, exactly as amps-demo's
    // bootRun does:
    //   ./gradlew :artio-spring-boot:bootRun -Dartio.port=9881 -Dbridge.uri=tcp://host:9007/amps/fix
    //   ./gradlew :artio-spring-boot:bootRun -Dspring.profiles.active=initiator
    systemProperties(
        System.getProperties()
            .stringPropertyNames()
            .filter { it.startsWith("artio.") || it.startsWith("bridge.") || it.startsWith("spring.") }
            .associateWith { System.getProperty(it) }
    )
}

val integrationTestTask = tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "The Spring application end to end: in-process, and as the fat jar under SIGTERM."
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter(tasks.test)

    // The harness resolves the compose file relative to the repository root.
    workingDir = rootProject.projectDir

    // BootJarSubprocessIT runs this exact artifact with `java -jar`.
    val bootJar = tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar")
    dependsOn(bootJar)
    systemProperty("boot.jar", bootJar.get().archiveFile.get().asFile.absolutePath)

    // Aeron and Artio directories go under java.io.tmpdir with a unique suffix and are deleted on
    // close, as in :artio-engine. Override with -Dartio.it.dir to keep them.
    systemProperty("artio.it.dir", System.getProperty("artio.it.dir", ""))

    // Each variable is a declared INPUT, not merely forwarded: this repository sets
    // org.gradle.caching=true, and an environment variable that is only forwarded is invisible to
    // the cache key - so a run WITHOUT an AMPS image would cache an all-skipped result and the
    // next run WITH one would restore it instead of executing. Spelled out at length in
    // amps-test-harness/build.gradle.kts.
    listOf("AMPS_IMAGE", "AMPS_PLATFORM", "CONTAINER_ENGINE", "AMPS_IT").forEach { name ->
        val value = providers.environmentVariable(name)
        inputs.property(name, value.orElse(""))
        if (value.isPresent) {
            environment(name, value.get())
        }
    }

    // ...and excluded from the cache and from up-to-date checks anyway: whether this suite does
    // anything depends on podman being installed and running and on free host ports, none of which
    // Gradle can see. Ask for it and it runs.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }

    testLogging {
        showStandardStreams = true
    }
}

// `check` runs the integration suite. It opts out with a reason when podman or the image is absent,
// so this stays green on a machine that has never seen AMPS and does real work on one that has.
tasks.named("check") {
    dependsOn(integrationTestTask)
}
