package com.demo.artio.boot;

import com.demo.artio.bridge.AmpsFixPublisher;
import com.demo.artio.engine.CompositeSink;
import com.demo.artio.engine.FixMessageSink;
import com.demo.artio.engine.LoggingSink;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * With the bridge enabled the context holds <em>two</em> {@code FixMessageSink} beans, and the
 * engine must still get exactly one.
 *
 * <p>{@link AmpsFixPublisher} implements {@link FixMessageSink} - that is the design, not an
 * accident - so {@code artioRuntime}'s {@code FixMessageSink} parameter is ambiguous the moment the
 * publisher exists. Without {@code @Primary} on {@code fixMessageSink} the application does not
 * start at all:
 *
 * <pre>
 *   Parameter 1 of method artioRuntime required a single bean, but 2 were found:
 *       - ampsFixPublisher
 *       - fixMessageSink
 * </pre>
 *
 * <p>The other tests in this suite disable the bridge, so none of them can see that. This one
 * enables it and never enables the engine, which is the cheapest configuration in which the
 * ambiguity exists.
 *
 * <p><strong>What this costs.</strong> One TCP connect to a port this test has just confirmed free,
 * which is refused immediately; {@code AmpsFixPublisher.start()} logs it and backs off, because an
 * unreachable AMPS is deliberately not a start-up failure. No container, no external service, and
 * the back-off is set past the life of the context so the retry never happens.
 */
@SpringBootTest(properties = {
    "artio.enabled=false",
    "bridge.enabled=true",
    "bridge.stats-log-interval-ms=0",
    "bridge.flush-timeout-ms=500"})
class SinkWiringWithThePublisherEnabledTest
{
    @DynamicPropertySource
    static void pointTheBridgeAtAPortNothingIsListeningOn(final DynamicPropertyRegistry registry)
    {
        final int deadPort = freePort();
        registry.add("bridge.uri", () -> "tcp://127.0.0.1:" + deadPort + "/amps/fix");
        // Longer than this context will live, so the publisher agent connects once and then waits.
        registry.add("bridge.reconnect-initial-backoff-ms", () -> 600_000);
        registry.add("bridge.reconnect-max-backoff-ms", () -> 600_000);
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private AmpsFixPublisher publisher;

    @Test
    void theEngineSGetsTheCompositeAndNotTheAmbiguousPairOfCandidates()
    {
        assertEquals(2, context.getBeanNamesForType(FixMessageSink.class).length,
            "the publisher IS a sink; if this ever becomes 1 the @Primary below is dead weight");

        final FixMessageSink sink = assertDoesNotThrow(
            () -> context.getBean(FixMessageSink.class),
            "an ambiguous FixMessageSink is what stops the whole application from starting");

        final CompositeSink composite = assertInstanceOf(CompositeSink.class, sink);
        final List<FixMessageSink> delegates = composite.delegates();
        assertAll(
            () -> assertEquals(2, delegates.size()),
            // LoggingSink first: it builds a printable String and the publisher does not, so the
            // console line is written before the bytes are handed off.
            () -> assertInstanceOf(LoggingSink.class, delegates.get(0)),
            () -> assertSame(publisher, delegates.get(1)));
    }

    @Test
    void thePublisherIsStartedByItsLifecycleEvenThoughAmpsIsUnreachable()
    {
        // The FIX gateway's first duty is to stay logged on to its counterparty; refusing to come
        // up because the broker was briefly down would be the more surprising behaviour.
        assertEquals(true, publisher.isRunning());
        assertEquals(false, publisher.stats().connected());
    }

    private static int freePort()
    {
        try (ServerSocket socket = new ServerSocket(0))
        {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
        catch (final IOException e)
        {
            throw new UncheckedIOException("Could not find a free TCP port", e);
        }
    }
}
