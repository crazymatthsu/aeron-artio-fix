/**
 * The Artio runtime: an embedded media driver + Aeron archive + FixEngine + FixLibrary, plus the
 * `FixMessageSink` seam every downstream module (the AMPS bridge, the Spring Boot app) plugs into.
 *
 * Two test suites, split because one needs sockets and threads and the other does not:
 *
 *   test              config validation, the FixMessageView flyweight over a hand-built buffer,
 *                     and the sinks. No ports, no Aeron, milliseconds.
 *   integrationTest   real engines on loopback with free ports: Artio acceptor <- QuickFIX/J
 *                     initiator, Artio initiator -> QuickFIX/J acceptor, Artio <-> Artio, and
 *                     shutdown. Nothing here is conditional: every test must run.
 *
 * See docs/01-artio-engine-design.md.
 */
plugins {
    `java-library`
}

dependencies {
    // The generated FIX 4.2 / 4.4 dictionaries and codecs. `api` because FixVersion hands out
    // `Class<? extends FixDictionary>` and callers encode with the generated encoders.
    api(project(":fix-codecs"))
    // FixEngine, FixLibrary, Session, Reply, AuthenticationStrategy: all appear in this module's
    // public API (ArtioRuntime.sessions(), FixEngineConfig.authenticationStrategy()).
    api(libs.artio.core)
    // DirectBuffer is on FixMessageView; IdleStrategy is on the config surface.
    api(libs.agrona)

    // The embedded transport. Private: no Aeron type escapes ArtioRuntime.
    implementation(libs.aeron.client)
    implementation(libs.aeron.driver)
    implementation(libs.aeron.archive)
    implementation(libs.slf4j.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.awaitility)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
}

/**
 * The integration suite compiles against the main source set and the unit-test helpers, but runs on
 * its own task so a plain `build` stays fast.
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
    // "The other FIX engine". quickfixj-counterparty does not depend on this module, so this is not
    // a cycle: the dependency runs one way, from this test suite to the counterparty.
    "integrationTestImplementation"(project(":quickfixj-counterparty"))
}

/**
 * The three flags any JVM that RUNS Artio needs. The first two are enough to build a codec; the
 * third is only needed once a FixEngine is launched, and it is easy to miss because nothing fails
 * until then.
 *
 *   --add-opens/--add-exports java.base/jdk.internal.misc
 *       agrona 2.x reaches jdk.internal.misc.Unsafe from org.agrona.UnsafeApi. Without these the
 *       first `new UnsafeBuffer(...)` - which every generated codec does in its constructor - dies
 *       with "class org.agrona.UnsafeApi cannot access class jdk.internal.misc.Unsafe".
 *
 *   --add-opens java.base/sun.nio.ch
 *       Artio's uk.co.real_logic.artio.engine.framer.ReceiverEndPoints reflects on
 *       sun.nio.ch.SelectorImpl.selectedKeys in a static initialiser, to replace the selected-key
 *       set with an array-backed one. Without it FixEngine.launch fails with
 *       "NoClassDefFoundError: Could not initialize class ...ReceiverEndPoints", caused by
 *       "InaccessibleObjectException: Unable to make field private final java.util.Set
 *       sun.nio.ch.SelectorImpl.selectedKeys accessible". Nothing in :fix-codecs needs it, which is
 *       why it only turns up here.
 *
 * Every downstream module that launches a FixEngine (the AMPS bridge, the Spring Boot app) needs
 * all three.
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

val integrationTestTask = tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Runs real Artio engines against QuickFIX/J and against each other on loopback."
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter(tasks.test)

    // Aeron and Artio directories are created under java.io.tmpdir with a unique suffix and deleted
    // on close, so nothing is left in the repository. Point them somewhere else with -Dartio.it.dir.
    systemProperty("artio.it.dir", System.getProperty("artio.it.dir", ""))

    // Paths in test output are relative to the repository root, as everywhere else in this build.
    workingDir = rootProject.projectDir

    // These tests open sockets and start real engines; a "green" result that ran nothing is worth
    // less than no result. `Test` is a cacheable task type and this build has
    // org.gradle.caching=true, so `upToDateWhen { false }` alone is not enough: it forces the task
    // to execute, but execution then finds a cache entry and restores it as FROM-CACHE without
    // running a single test. doNotTrackState opts the task out of both up-to-date checks and the
    // build cache, so every invocation runs the suite.
    doNotTrackState("starts real engines and binds ports")

    testLogging {
        showStandardStreams = false
    }
}

tasks.named("check") {
    dependsOn(integrationTestTask)
}
