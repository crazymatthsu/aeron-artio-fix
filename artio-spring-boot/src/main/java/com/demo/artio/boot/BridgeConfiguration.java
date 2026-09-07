package com.demo.artio.boot;

import com.demo.artio.bridge.AmpsFixPublisher;
import com.demo.artio.engine.ArtioRuntime;
import com.demo.artio.engine.CompositeSink;
import com.demo.artio.engine.FixMessageSink;
import com.demo.artio.engine.LoggingSink;
import com.demo.artio.engine.SessionListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.ArrayList;
import java.util.List;

/**
 * The whole wiring: two objects and two {@link org.springframework.context.SmartLifecycle}
 * adapters. This is what {@code BridgeMain} does in twenty lines of {@code main}, expressed so that
 * the container - rather than a hand-written shutdown hook - owns the ordering.
 *
 * <pre>
 *   BridgeProperties -> BridgeConfig -> AmpsFixPublisher --+
 *                                                          |-> CompositeSink -> ArtioRuntime
 *   ArtioProperties  -> FixEngineConfig ---- LoggingSink --+
 * </pre>
 *
 * <p>There is no {@code spring-boot-autoconfigure} jar and no
 * {@code META-INF/spring/...AutoConfiguration.imports} here on purpose: auto-configuration is for
 * libraries other applications depend on, and this module <em>is</em> the application. A plain
 * {@code @Configuration} inside the scanned package does the same work and can be read top to
 * bottom.
 *
 * <h2>Why no {@code destroyMethod}</h2>
 * {@code @Bean} infers {@code close()} on an {@link AutoCloseable}, and both objects are one. That
 * inference is switched off here ({@code destroyMethod = ""}) because destruction runs
 * <em>after</em> the lifecycle stop phases and in reverse dependency order, not in phase order -
 * so leaving it on would give each object two close paths, one ordered and one not. Both
 * {@code close()} methods are idempotent, so nothing would break; it would simply become impossible
 * to tell from the code which path did the flush. One owner each: the lifecycle adapter.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ArtioProperties.class, BridgeProperties.class})
@EnableScheduling
public class BridgeConfiguration
{
    /**
     * The AMPS publisher, not yet started - {@link BridgePublisherLifecycle} does that, first.
     *
     * @param properties the bound {@code bridge.*} configuration.
     * @return the publisher.
     */
    @Bean(destroyMethod = "")
    @ConditionalOnProperty(prefix = "bridge", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AmpsFixPublisher ampsFixPublisher(final BridgeProperties properties)
    {
        return new AmpsFixPublisher(properties.toBridgeConfig());
    }

    /**
     * Where Artio delivers every message it receives.
     *
     * <p>Order matters inside the composite: {@link LoggingSink} builds a printable String and the
     * publisher does not, so the log line is written before the bytes are handed off. Both see the
     * same flyweight.
     *
     * <p><strong>{@code @Primary} is load-bearing.</strong> {@link AmpsFixPublisher} <em>is</em> a
     * {@link FixMessageSink} - that is the whole design - so with the bridge enabled the context
     * holds two beans of this type and {@code artioRuntime}'s injection point is ambiguous. The
     * application then fails to start with "expected single matching bean but found 2", which is a
     * confusing way to be told about a design that is working as intended. Naming this one primary
     * says which sink the engine gets: the composite, which contains the other.
     *
     * @param publisher  the bridge, when {@code bridge.enabled} is true.
     * @param properties the bound {@code artio.*} configuration.
     * @return the sink, which may be {@link FixMessageSink#NO_OP} if both halves are switched off.
     */
    @Bean
    @Primary
    public FixMessageSink fixMessageSink(
        final ObjectProvider<AmpsFixPublisher> publisher, final ArtioProperties properties)
    {
        final List<FixMessageSink> sinks = new ArrayList<>(2);
        if (properties.logMessages())
        {
            sinks.add(new LoggingSink("recv", true));
        }
        publisher.ifAvailable(sinks::add);
        return CompositeSink.of(sinks.toArray(new FixMessageSink[0]));
    }

    /**
     * The Artio engine and library, not yet started - {@link ArtioRuntimeLifecycle} does that,
     * last.
     *
     * @param properties the bound {@code artio.*} configuration.
     * @param sink       where received messages go.
     * @param listener   an optional application-supplied session listener; declare one as a bean to
     *                   react to logon, logout and disconnect.
     * @return the runtime.
     */
    @Bean(destroyMethod = "")
    @ConditionalOnProperty(prefix = "artio", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ArtioRuntime artioRuntime(
        final ArtioProperties properties,
        final FixMessageSink sink,
        final ObjectProvider<SessionListener> listener)
    {
        return new ArtioRuntime(properties.toFixEngineConfig(), sink, listener.getIfAvailable());
    }

    /**
     * @param publisher the publisher to start first and stop last.
     * @return the low-phase lifecycle adapter.
     */
    @Bean
    @ConditionalOnProperty(prefix = "bridge", name = "enabled", havingValue = "true", matchIfMissing = true)
    public BridgePublisherLifecycle bridgePublisherLifecycle(final AmpsFixPublisher publisher)
    {
        return new BridgePublisherLifecycle(publisher);
    }

    /**
     * @param runtime the runtime to start last and stop first.
     * @return the high-phase lifecycle adapter.
     */
    @Bean
    @ConditionalOnProperty(prefix = "artio", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ArtioRuntimeLifecycle artioRuntimeLifecycle(final ArtioRuntime runtime)
    {
        return new ArtioRuntimeLifecycle(runtime);
    }

    /**
     * @param publisher  whose counters are logged.
     * @param runtime    whose sessions are listed; absent when {@code artio.enabled} is false.
     * @param properties supplies the interval; 0 disables logging.
     * @return the stats logger.
     */
    @Bean
    @ConditionalOnProperty(prefix = "bridge", name = "enabled", havingValue = "true", matchIfMissing = true)
    public StatsLogger statsLogger(
        final AmpsFixPublisher publisher,
        final ObjectProvider<ArtioRuntime> runtime,
        final BridgeProperties properties)
    {
        return new StatsLogger(publisher, runtime, properties.statsLogIntervalMs());
    }
}
