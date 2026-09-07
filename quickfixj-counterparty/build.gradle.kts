/**
 * "The other FIX engine": a QuickFIX/J initiator and acceptor built from programmatic settings, used
 * as the counterparty by every integration test that needs one, and runnable on its own for the demo.
 *
 * This module deliberately does NOT depend on :artio-engine. If it did, a test that "proves the
 * counterparty works" could be leaning on Artio, and the FIX-4.2-on-Artio result would be circular.
 * Where both sides need the same concept - a FIX version - each defines its own enum and the test
 * maps between them.
 *
 * Two test suites:
 *
 *   test              settings construction, scenario contents, the ExecutionReport builder and
 *                     command line parsing. No sockets.
 *   integrationTest   QuickFIX/J initiator <-> QuickFIX/J acceptor over loopback, running the full
 *                     order scenario. Proves the counterparty end to end without Artio in the
 *                     picture; the Artio pairings live in :artio-engine:integrationTest.
 */
plugins {
    `java-library`
    application
}

dependencies {
    // quickfixj-core brings quickfixj-base and MINA; the two messages jars carry FIX42.xml /
    // FIX44.xml (QuickFIX/J's DataDictionary) and the per-version MessageFactory that
    // DefaultMessageFactory discovers at runtime.
    api(libs.quickfixj.core)
    runtimeOnly(libs.quickfixj.messages.fix42)
    runtimeOnly(libs.quickfixj.messages.fix44)
    implementation(libs.slf4j.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.awaitility)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
}

/** The integration suite compiles against main and the unit-test helpers but runs on its own task. */
val integrationTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += output + compileClasspath
}

configurations["integrationTestImplementation"]
    .extendsFrom(configurations.testImplementation.get())
configurations["integrationTestRuntimeOnly"]
    .extendsFrom(configurations.testRuntimeOnly.get())

/**
 * Nothing in this module touches Artio or Agrona, but every JVM in this build gets the same flags:
 * the integration tests in :artio-engine run this module's classes in a JVM that does, and a
 * developer who copies a command line from one module's README to another should not have to know
 * which is which.
 */
val agronaJvmArgs = listOf(
    "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED"
)

tasks.withType<Test>().configureEach {
    jvmArgs(agronaJvmArgs)
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs(agronaJvmArgs)
}

application {
    mainClass.set("com.demo.artio.qfj.QfjMain")
    applicationDefaultJvmArgs = agronaJvmArgs + listOf(
        // The main prints every message itself; QuickFIX/J's own SLF4J logging would double it up.
        "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn",
        "-Dorg.slf4j.simpleLogger.log.com.demo.artio=info",
        "-Dorg.slf4j.simpleLogger.showThreadName=false"
    )
}

dependencies {
    // slf4j-simple is a runtime choice, and this module has a main. Consumers get slf4j-api only.
    runtimeOnly(libs.slf4j.simple)
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    // Ctrl-C on the acceptor has to reach the JVM, and the scenario reads nothing from stdin.
    standardInput = System.`in`
}

val integrationTestTask = tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Runs a QuickFIX/J initiator against a QuickFIX/J acceptor on loopback."
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter(tasks.test)
    workingDir = rootProject.projectDir
    // Real sockets and threads: a cached "up to date" would be a green build that ran nothing.
    outputs.upToDateWhen { false }
    testLogging {
        showStandardStreams = false
    }
}

tasks.named("check") {
    dependsOn(integrationTestTask)
}
