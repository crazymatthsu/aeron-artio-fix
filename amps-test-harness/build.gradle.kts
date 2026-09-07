/**
 * The throwaway-AMPS harness every other module's `integrationTest` source set
 * depends on. A library, not an application: it starts a container, hands back
 * a URI, and gets out of the way.
 *
 * Two suites here, split because one needs a container and the other must not:
 *
 *   test              printable rendering, the skip rules, repository-root
 *                     discovery. No podman, runs inside `build`.
 *   integrationTest   starts a real AMPS on the artio-fix flow, publishes raw
 *                     FIX, queries the SOW back, replays the journal, restarts
 *                     the container and queries again. This is the suite that
 *                     proves the harness works, so nothing downstream has to
 *                     debug it.
 */
plugins {
    `java-library`
}

dependencies {
    // All `api`, and each for a reason a consumer can see:
    //   slf4j        - a consumer configures the binding, so it must see the API
    //   amps-client  - SowReader returns and throws AMPS types
    //   junit        - AmpsAssumptions.assumeAvailable() IS a JUnit assumption;
    //                  it is part of this module's surface, not of its own tests
    api(libs.slf4j.api)
    api(libs.amps.client)
    api(platform(libs.junit.bom))
    api(libs.junit.jupiter)

    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
}

/**
 * The integration suite compiles against main and the unit-test helpers, but
 * runs on its own task, so a plain `build` never needs a container.
 */
val integrationTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += output + compileClasspath
}

configurations["integrationTestImplementation"]
    .extendsFrom(configurations.testImplementation.get())
configurations["integrationTestRuntimeOnly"]
    .extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    "integrationTestImplementation"(libs.awaitility)
}

val integrationTestTask = tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Start a real AMPS with podman compose, publish FIX, and read it back."
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter(tasks.test)

    // The harness resolves the compose file and the flow config relative to the
    // repository root, which it finds by walking up from the working directory.
    // Setting it here means the walk is a no-op in a normal build.
    workingDir = rootProject.projectDir

    // Each variable is a declared INPUT, not merely forwarded.
    //
    // This repository sets org.gradle.caching=true. An environment variable
    // that is only forwarded is invisible to the cache key, so a run WITHOUT
    // an AMPS image caches an all-skipped result, and the next run WITH one
    // restores that entry instead of executing:
    //
    //     > Task :amps-test-harness:integrationTest FROM-CACHE
    //     BUILD SUCCESSFUL
    //     ...3 tests, 3 skipped
    //
    // A green build that ran nothing - the worst available outcome for a suite
    // that is designed to skip itself. AMPS_IT matters most: it does not merely
    // enable the suite, it is the switch that turns it off, so a cached result
    // from AMPS_IT=false would satisfy a run that asked for the opposite.
    // Reading through providers.environmentVariable is the
    // configuration-cache-correct way to make a value an input.
    listOf("AMPS_IMAGE", "AMPS_PLATFORM", "CONTAINER_ENGINE", "AMPS_IT").forEach { name ->
        val value = providers.environmentVariable(name)
        inputs.property(name, value.orElse(""))
        if (value.isPresent) {
            environment(name, value.get())
        }
    }

    // ...and then this task is excluded from the cache and from up-to-date
    // checks entirely, which is not a contradiction of the above.
    //
    // Correct inputs make the cache key as good as it can be. They cannot make
    // it COMPLETE: whether this suite does anything depends on podman being
    // installed and running, on the machine holding the image, and on three
    // host ports being free - none of which Gradle can see. So a key that is
    // right in every respect Gradle can observe still restores "PASSED" for a
    // run on a box where the container engine has since broken, and reports
    // success for a suite that started nothing:
    //
    //     > Task :amps-test-harness:integrationTest FROM-CACHE
    //
    // For a unit suite that is the whole point of the cache. For the one suite
    // whose job is to prove a container really starts, it is the failure mode
    // it exists to catch. Ask for it and it runs.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    // ...and, belt and braces, told not to track state at all. `Test` is a
    // cacheable task type and this repository has org.gradle.caching=true, so
    // the two predicates above are what stand between an unchanged rerun and
    // `FROM-CACHE`. This is the setting that says so in one place.
    doNotTrackState("starts containers")

    testLogging {
        showStandardStreams = true
    }
}

// `check` runs it. The suite opts out with a reason when podman or the image is
// absent, so this stays green on a machine that has never seen AMPS and does
// real work on one that has.
tasks.named("check") {
    dependsOn(integrationTestTask)
}
