package com.demo.artio.boot;

import com.demo.artio.bridge.AmpsFixPublisher;
import com.demo.artio.engine.ArtioRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContextException;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real application, when {@code SpringApplication.run} fails: nothing that was started may
 * outlive the failure, whichever way it failed.
 *
 * <p>{@code LifecyclePhaseOrderingTest} covers the two failure paths against bare lifecycle beans.
 * This class runs them through {@code ArtioBridgeApplication} and {@code BridgeConfiguration} as
 * shipped, so the wiring - the conditional beans, {@code destroyMethod = ""}, the dependency edge
 * between the two adapters - is what is tested, not a model of it. Both cases are cheap: the engine
 * fails before it launches a media driver, and the publisher's one TCP connect is to a port this
 * test has just confirmed free.
 */
class ContextRefreshFailureTest
{
    @Test
    void anEngineThatCannotStartFailsTheRunWithEverythingClosedAndTheAdaptersOrdered(
        @TempDir final Path dir) throws IOException
    {
        // A base directory under a regular file cannot be created, so ArtioRuntime.start() throws
        // from createDirectories(), before the media driver - the cheapest way to make the real
        // runtime fail at the real moment.
        final Path file = Files.writeString(dir.resolve("not-a-directory"), "");
        final Captured captured = new Captured();

        final ApplicationContextException failure = assertThrows(ApplicationContextException.class,
            () -> run(captured, List.of(),
                "--artio.enabled=true",
                "--artio.port=" + freePort(),
                "--artio.base-directory=" + file.resolve("cannot-exist")));

        assertAll(
            () -> assertTrue(failure.getMessage().contains("artioRuntimeLifecycle"),
                () -> "the failure must name the bean that could not start: " + failure.getMessage()),
            () -> assertNotNull(captured.publisher, "the publisher bean was created"),
            () -> assertNotNull(captured.runtime, "the runtime bean was created"),
            () -> assertFalse(captured.publisher.isRunning(),
                "Spring rolls a failed start back by stopping what it had started; the publisher " +
                    "must not be left running"),
            () -> assertFalse(captured.runtime.isRunning(), "a failed start closes the runtime"),
            // The edge that orders bean destruction on the path Spring does not roll back: the
            // runtime's adapter is a dependent of the publisher's, so it is destroyed first.
            () -> assertTrue(captured.runtimeLifecycleDependencies.contains("bridgePublisherLifecycle"),
                () -> "artioRuntimeLifecycle must depend on bridgePublisherLifecycle but depends on " +
                    captured.runtimeLifecycleDependencies));
    }

    @Test
    void aListenerThatThrowsOnContextRefreshedLeavesNoRunningPublisherBehind()
    {
        // The path with no roll-back: every phase has started when ContextRefreshedEvent is
        // published, and a listener that throws there ends the refresh with the singletons
        // destroyed and no stop phase run. Only DisposableBean.destroy() on the adapter closes the
        // publisher, whose agent thread is not a daemon.
        final Captured captured = new Captured();
        final ApplicationListener<ContextRefreshedEvent> throwing = event ->
        {
            // Sampled here, not at bean creation: the phases start in finishRefresh(), which is
            // what publishes this event, so this is the earliest moment the publisher can be seen
            // running and the latest moment before the refresh is torn down.
            captured.publisherWasRunningWhenListenerThrew =
                captured.publisher != null && captured.publisher.isRunning();
            throw new IllegalStateException("a listener that throws on ContextRefreshedEvent");
        };

        final IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> run(captured, List.of(throwing), "--artio.enabled=false"));

        assertAll(
            () -> assertTrue(failure.getMessage().contains("ContextRefreshedEvent")),
            () -> assertNotNull(captured.publisher, "the publisher bean was created"),
            () -> assertTrue(captured.publisherWasRunningWhenListenerThrew,
                "the publisher was started by its phase before the listener threw"),
            () -> assertFalse(captured.publisher.isRunning(),
                "no stop phase runs on this path; the adapter's destroy() must close the publisher"));
    }

    private static void run(
        final Captured captured, final List<ApplicationListener<?>> listeners, final String... args)
    {
        final List<String> arguments = new ArrayList<>(Arrays.asList(args));
        arguments.addAll(List.of(
            "--bridge.enabled=true",
            "--bridge.uri=tcp://127.0.0.1:" + freePort() + "/amps/fix",
            // Longer than this context will live, so the publisher connects once and then waits.
            "--bridge.reconnect-initial-backoff-ms=600000",
            "--bridge.reconnect-max-backoff-ms=600000",
            "--bridge.flush-timeout-ms=500",
            "--bridge.stats-log-interval-ms=0"));

        final SpringApplicationBuilder builder = new SpringApplicationBuilder(ArtioBridgeApplication.class)
            .web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF)
            .registerShutdownHook(false)
            .initializers(context ->
            {
                captured.beanFactory(context.getBeanFactory());
                context.getBeanFactory().addBeanPostProcessor(captured);
            });
        for (final ApplicationListener<?> listener : listeners)
        {
            builder.listeners(listener);
        }
        try (ConfigurableApplicationContext context = builder.run(arguments.toArray(new String[0])))
        {
            throw new AssertionError("the run was expected to fail but produced " + context);
        }
    }

    /** Grabs the two runtime objects as they are created, so they can be inspected after the failure. */
    static final class Captured implements BeanPostProcessor
    {
        volatile AmpsFixPublisher publisher;
        volatile ArtioRuntime runtime;
        volatile boolean publisherWasRunningWhenListenerThrew;
        volatile List<String> runtimeLifecycleDependencies = List.of();
        private volatile ConfigurableListableBeanFactory beanFactory;

        void beanFactory(final ConfigurableListableBeanFactory factory)
        {
            this.beanFactory = factory;
        }

        @Override
        public Object postProcessAfterInitialization(final Object bean, final String beanName)
        {
            if (bean instanceof AmpsFixPublisher p)
            {
                publisher = p;
            }
            if (bean instanceof ArtioRuntime r)
            {
                runtime = r;
            }
            if (bean instanceof ArtioRuntimeLifecycle && beanFactory != null)
            {
                runtimeLifecycleDependencies = List.of(beanFactory.getDependenciesForBean(beanName));
            }
            return bean;
        }
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
