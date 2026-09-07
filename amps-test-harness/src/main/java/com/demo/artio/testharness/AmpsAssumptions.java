package com.demo.artio.testharness;

import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one line every AMPS-backed test starts with.
 *
 * <pre>{@code
 * @BeforeAll
 * static void ampsOrSkip() {
 *     AmpsAssumptions.assumeAvailable();
 * }
 * }</pre>
 *
 * <p>This is why the module depends on JUnit through {@code api} rather than
 * {@code testImplementation}: the assumption is part of the harness's public
 * surface, not of its own test suite.
 *
 * <p>A skip here is an ordinary state of a developer machine. There is no
 * public AMPS server image - it has to be built from a licensed release
 * tarball - so {@code ./gradlew build} on a laptop that has never seen AMPS
 * must stay green, and on one that has it must do real work. The corollary is
 * that a green build is not by itself proof these ran: look for
 * {@code 0 skipped}, and see the note about task inputs in this module's
 * {@code build.gradle.kts} for how a cached all-skipped result was prevented
 * from masquerading as one.
 */
public final class AmpsAssumptions {

    private static final Logger log = LoggerFactory.getLogger(AmpsAssumptions.class);

    private AmpsAssumptions() {
    }

    /**
     * Skips the calling test, with the reason, when AMPS cannot be started
     * here.
     *
     * @see AmpsComposeServer#unavailableReason()
     */
    public static void assumeAvailable() {
        Optional<String> reason = AmpsComposeServer.unavailableReason();
        // Logged as well as reported: a JUnit skip reason is easy to miss in
        // Gradle output, and "why did the integration suite do nothing" is the
        // question this answers.
        reason.ifPresent(why -> log.warn("skipping AMPS integration test: {}", why));
        Assumptions.assumeTrue(reason.isEmpty(),
                () -> "AMPS is not available here: " + reason.orElse(""));
    }
}
