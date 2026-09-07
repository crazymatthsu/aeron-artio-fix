package com.demo.artio.boot;

import com.demo.artio.bridge.AmpsFixPublisher;
import com.demo.artio.bridge.BridgeConfig;
import com.demo.artio.bridge.BridgeStats;
import com.demo.artio.engine.ArtioRuntime;
import com.demo.artio.qfj.OrderScenario;
import com.demo.artio.qfj.QfjInitiator;
import com.demo.artio.testharness.AmpsAssumptions;
import com.demo.artio.testharness.AmpsComposeServer;
import com.demo.artio.testharness.SowReader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Spring application, in this JVM, end to end: a real container, a real media driver, a real
 * Aeron archive, two FIX engines on loopback, an off-heap ring buffer and a SOW.
 *
 * <p>Two questions, and they are different:
 *
 * <ol>
 *   <li><strong>Does Spring's wiring produce the same result as {@code BridgeMain}'s?</strong> The
 *       SOW assertions are the ones {@code BridgeOrderFlowIT} makes, factored into
 *       {@link SpringItSupport#assertOrderScenarioLanded}, so "the same" is checked rather than
 *       asserted.</li>
 *   <li><strong>Does the container really stop the engine before the publisher?</strong> That is
 *       the one ordering the bridge does not tolerate (docs/03 section 8), and the only reason the
 *       two {@code SmartLifecycle} phases exist. {@link ShutdownOrderProbe} answers it directly: a
 *       third lifecycle at phase 0, between the publisher's {@code MIN+1000} and the runtime's
 *       {@code MAX-1000}, which records what was still running when Spring reached it. Reading the
 *       log would be an indirect answer to the same question and a worse one.</li>
 * </ol>
 *
 * <p>The overrides arrive as command-line arguments, not
 * {@code SpringApplicationBuilder.properties(...)}: the latter registers <em>default</em>
 * properties, which are the lowest-precedence source of all and therefore lose to
 * {@code application.yml}. A test that set {@code artio.port} that way would have silently bound
 * 9880 and passed or failed depending on what else was running on the machine.
 */
class SpringApplicationOrderFlowIT
{
    private static AmpsComposeServer amps;

    @BeforeAll
    static void startAmps() throws Exception
    {
        AmpsAssumptions.assumeAvailable();
        amps = AmpsComposeServer.start("artio-fix");
    }

    @AfterAll
    static void stopAmps()
    {
        if (amps != null)
        {
            amps.close();
        }
    }

    @Test
    void theApplicationPublishesTheOrderScenarioAndClosesTheEngineBeforeThePublisher() throws Exception
    {
        final OrderScenario scenario = OrderScenario.DEFAULT;
        final int port = SpringItSupport.freePort();
        final long rawBefore = SowReader.countFromEpoch(
            amps.uri(), BridgeConfig.TOPIC_RAW, Duration.ofSeconds(2));

        final List<String> clOrdIds;
        final AmpsFixPublisher publisher;
        final ArtioRuntime runtime;
        final ShutdownOrderProbe probe;
        final BridgeStats statsBeforeClose;

        final ConfigurableApplicationContext context =
            new SpringApplicationBuilder(ArtioBridgeApplication.class, ProbeConfiguration.class)
                .web(WebApplicationType.NONE)
                .bannerMode(org.springframework.boot.Banner.Mode.OFF)
                // No shutdown hook: this test closes the context itself, and a hook left registered
                // would try again after the JVM's own teardown had started.
                .registerShutdownHook(false)
                .run(
                    "--artio.port=" + port,
                    "--artio.base-directory=" + SpringItSupport.baseDirectory(),
                    "--bridge.uri=" + amps.uri(),
                    "--bridge.client-name=artio-spring-it",
                    // Short, because a failing test should not sit for ten seconds at close().
                    "--bridge.flush-timeout-ms=5000",
                    // Off, so the only thing on the console is the flow.
                    "--bridge.stats-log-interval-ms=0");
        try
        {
            publisher = context.getBean(AmpsFixPublisher.class);
            runtime = context.getBean(ArtioRuntime.class);
            probe = context.getBean(ShutdownOrderProbe.class);

            assertAll(
                () -> assertTrue(runtime.isRunning(), "the runtime lifecycle must have started it"),
                () -> assertTrue(publisher.isRunning(), "the publisher lifecycle must have started it"),
                () -> assertEquals(port, runtime.config().port()),
                () -> assertEquals(amps.uri(), publisher.config().uri()),
                // The command line changed the URI and nothing else: an unset bridge.route still
                // means BridgeConfig.DEFAULT_ROUTES. (TopicRouter.routes() reports one more than
                // this - the catch-all `* -> fix.raw` - which is why the config is what is compared.)
                () -> assertEquals(BridgeConfig.DEFAULT_ROUTES, publisher.config().routes()));

            try (QfjInitiator qfj = new QfjInitiator(SpringItSupport.initiatorConfig(port)))
            {
                qfj.start();
                assertTrue(qfj.awaitLogon(SpringItSupport.STARTUP_TIMEOUT),
                    "QuickFIX/J did not log on to the Spring application's acceptor");
                await().atMost(SpringItSupport.STARTUP_TIMEOUT).until(() -> !runtime.sessions().isEmpty());

                clOrdIds = qfj.run(scenario);

                // Five application messages, each on fix.raw and on fix.orders.
                await().atMost(SpringItSupport.MESSAGE_TIMEOUT)
                    .until(() -> publisher.stats().published() >= 2L * OrderScenario.MESSAGE_COUNT);
            }
            statsBeforeClose = publisher.stats();
        }
        finally
        {
            // This is the shutdown under test: Spring runs the two phases, in order, and the
            // publisher's drain-and-flush is what makes the SOW queries below deterministic.
            context.close();
        }

        assertAll(
            // --- the ordering ---------------------------------------------------------------
            () -> assertTrue(probe.wasStopped(),
                "the probe never ran, so it proves nothing about the order"),
            () -> assertFalse(probe.runtimeRunningAtProbe(),
                "the engine must already be closed when Spring reaches phase 0, so that nothing " +
                    "can still be written into the ring buffer while the publisher drains it"),
            () -> assertTrue(probe.publisherRunningAtProbe(),
                "the publisher must still be alive at phase 0; closing it before the engine would " +
                    "count the tail of the session as dropped"),
            () -> assertFalse(publisher.isRunning(), "and it is closed by the time the context is"),
            () -> assertFalse(runtime.isRunning()));

        SpringItSupport.assertOrderScenarioLanded(amps.uri(), scenario, clOrdIds, rawBefore);

        final BridgeStats stats = publisher.stats();
        assertAll(
            () -> assertEquals(OrderScenario.MESSAGE_COUNT, stats.publishedTo(BridgeConfig.TOPIC_RAW)),
            () -> assertEquals(OrderScenario.MESSAGE_COUNT, stats.publishedTo(BridgeConfig.TOPIC_ORDERS)),
            () -> assertEquals(0, stats.publishedTo(BridgeConfig.TOPIC_EXECS)),
            () -> assertEquals(0, stats.publishedTo(BridgeConfig.TOPIC_ORDER_STATE)),
            () -> assertEquals(0, stats.dropped(), "the ring buffer never filled"),
            () -> assertEquals(0, stats.unroutable(), "every message carried its topic's SOW key"),
            () -> assertEquals(0, stats.publishErrors()),
            () -> assertEquals(0, stats.lostWhileDisconnected()),
            // Artio delivers session-level messages to the sink too; publishAdminMessages is off.
            () -> assertTrue(stats.adminSkipped() >= 1,
                "the counterparty's Logon reached the sink and was dropped, not published"),
            () -> assertEquals(stats.accepted(), stats.drained(),
                "close() drained the ring buffer before flushing"),
            () -> assertEquals(0, stats.pendingBytes()),
            () -> assertTrue(stats.published() >= statsBeforeClose.published(),
                "the close-time drain can only add publishes"));
    }

    /** Adds {@link ShutdownOrderProbe} to the application's own context. */
    @Configuration(proxyBeanMethods = false)
    static class ProbeConfiguration
    {
        @Bean
        ShutdownOrderProbe shutdownOrderProbe(
            final ObjectProvider<ArtioRuntime> runtime, final ObjectProvider<AmpsFixPublisher> publisher)
        {
            return new ShutdownOrderProbe(runtime, publisher);
        }
    }

    /**
     * A {@link SmartLifecycle} at phase 0, which is strictly between
     * {@link BridgePublisherLifecycle#PHASE} and {@link ArtioRuntimeLifecycle#PHASE}. Spring stops
     * phases in descending order, so when this one's {@code stop()} runs the engine must already be
     * down and the publisher must not be. Recording both here turns the ordering from something you
     * read in a log into something a test can fail on.
     */
    static final class ShutdownOrderProbe implements SmartLifecycle
    {
        private final ObjectProvider<ArtioRuntime> runtime;
        private final ObjectProvider<AmpsFixPublisher> publisher;
        private volatile boolean running;
        private volatile boolean stopped;
        private volatile boolean runtimeRunningAtProbe = true;
        private volatile boolean publisherRunningAtProbe;

        ShutdownOrderProbe(
            final ObjectProvider<ArtioRuntime> runtime, final ObjectProvider<AmpsFixPublisher> publisher)
        {
            this.runtime = runtime;
            this.publisher = publisher;
        }

        @Override
        public int getPhase()
        {
            return 0;
        }

        @Override
        public void start()
        {
            running = true;
        }

        @Override
        public void stop()
        {
            final ArtioRuntime engine = runtime.getIfAvailable();
            final AmpsFixPublisher bridge = publisher.getIfAvailable();
            runtimeRunningAtProbe = engine != null && engine.isRunning();
            publisherRunningAtProbe = bridge != null && bridge.isRunning();
            running = false;
            stopped = true;
        }

        @Override
        public boolean isRunning()
        {
            return running;
        }

        boolean wasStopped()
        {
            return stopped;
        }

        boolean runtimeRunningAtProbe()
        {
            return runtimeRunningAtProbe;
        }

        boolean publisherRunningAtProbe()
        {
            return publisherRunningAtProbe;
        }
    }
}
