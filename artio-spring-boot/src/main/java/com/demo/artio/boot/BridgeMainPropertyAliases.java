package com.demo.artio.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lets {@code artio-amps-bridge/bridge.properties} configure this application unchanged.
 *
 * <p>Almost every key already matches. Spring's relaxed binding treats {@code artio.senderCompId}
 * and {@code artio.sender-comp-id} as the same component, so the whole {@code artio.*} block binds
 * with no help, and {@link BridgeProperties} names its list component {@code route} precisely so
 * that {@code bridge.route[0].msgType} binds with no help either. What is left is five keys that
 * {@code BridgeMain} groups and {@link BridgeProperties} keeps flat:
 *
 * <table>
 *   <caption>Translations</caption>
 *   <tr><th>{@code bridge.properties}</th><th>{@link BridgeProperties}</th></tr>
 *   <tr><td>{@code bridge.amps.uri}</td><td>{@code bridge.uri}</td></tr>
 *   <tr><td>{@code bridge.amps.clientName}</td><td>{@code bridge.client-name}</td></tr>
 *   <tr><td>{@code bridge.amps.guaranteedPublishing}</td><td>{@code bridge.guaranteed-publishing}</td></tr>
 *   <tr><td>{@code bridge.reconnect.initialBackoffMs}</td><td>{@code bridge.reconnect-initial-backoff-ms}</td></tr>
 *   <tr><td>{@code bridge.reconnect.maxBackoffMs}</td><td>{@code bridge.reconnect-max-backoff-ms}</td></tr>
 * </table>
 *
 * <p>Without this, those five would not fail - they would <em>silently</em> fall back to their
 * defaults, so a bridge pointed at a remote AMPS by an existing {@code bridge.properties} would
 * come up cheerfully connected to {@code localhost}. That is the whole reason this class exists;
 * five hard-coded renames are a small price for not having that failure mode.
 *
 * <pre>
 *   java ... -jar artio-spring-boot.jar \
 *       --spring.config.location=optional:classpath:/,file:artio-amps-bridge/bridge.properties
 * </pre>
 *
 * <h2>Precedence</h2>
 * A translated key is inserted in a property source placed <em>immediately before</em> the source
 * it was derived from, so it inherits that source's standing in the environment exactly. A
 * {@code bridge.amps.uri} in an external properties file therefore still loses to a
 * {@code --bridge.uri} on the command line and still beats the default in {@code application.yml},
 * which is what both would do if the file had used the canonical spelling. The one corner: if a
 * single source carries <em>both</em> spellings, the {@code BridgeMain} one wins - stating that is
 * cheaper than making the reader guess.
 *
 * <p>Runs at {@link Ordered#LOWEST_PRECEDENCE} so that {@code ConfigDataEnvironmentPostProcessor}
 * has already loaded {@code application.yml} and any {@code spring.config.*} location; there is
 * nothing to translate before that. Registered in
 * {@code META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports}.
 */
public class BridgeMainPropertyAliases implements EnvironmentPostProcessor, Ordered
{
    /** Suffix on the derived property sources, so they are recognisable in an environment dump. */
    static final String SOURCE_SUFFIX = " (bridge.properties aliases)";

    /** The renames. None is a prefix of another, so a plain map lookup is enough. */
    static final Map<String, String> RENAMES = Map.of(
        "bridge.amps.uri", "bridge.uri",
        "bridge.amps.clientName", "bridge.client-name",
        "bridge.amps.guaranteedPublishing", "bridge.guaranteed-publishing",
        "bridge.reconnect.initialBackoffMs", "bridge.reconnect-initial-backoff-ms",
        "bridge.reconnect.maxBackoffMs", "bridge.reconnect-max-backoff-ms");

    @Override
    public int getOrder()
    {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(
        final ConfigurableEnvironment environment, final SpringApplication application)
    {
        final MutablePropertySources sources = environment.getPropertySources();
        // Snapshot first: adding sources while iterating the live list is undefined.
        final List<PropertySource<?>> existing = new ArrayList<>();
        sources.forEach(existing::add);

        for (final PropertySource<?> source : existing)
        {
            if (!(source instanceof EnumerablePropertySource<?> enumerable) ||
                source.getName().endsWith(SOURCE_SUFFIX))
            {
                continue;
            }
            final Map<String, Object> aliases = translate(enumerable);
            if (!aliases.isEmpty())
            {
                sources.addBefore(
                    source.getName(),
                    new MapPropertySource(source.getName() + SOURCE_SUFFIX, aliases));
            }
        }
    }

    /**
     * @param source a property source to read.
     * @return the canonical keys derived from the {@code BridgeMain} spellings it carries; empty if
     * it carries none.
     */
    static Map<String, Object> translate(final EnumerablePropertySource<?> source)
    {
        final Map<String, Object> aliases = new LinkedHashMap<>();
        for (final String name : source.getPropertyNames())
        {
            final String renamed = RENAMES.get(name);
            if (renamed != null)
            {
                aliases.put(renamed, source.getProperty(name));
            }
        }
        return aliases;
    }
}
