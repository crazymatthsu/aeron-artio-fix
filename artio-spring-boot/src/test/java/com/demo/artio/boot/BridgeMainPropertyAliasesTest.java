package com.demo.artio.boot;

import com.demo.artio.bridge.BridgeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped {@code artio-amps-bridge/bridge.properties} configures this application unchanged.
 *
 * <p>The README and {@code docs/04} both claim "one configuration, two entry points". This test is
 * what makes that a fact: it binds the real file - not a copy, not a fixture - through Spring, and
 * compares the resulting {@link BridgeConfig} with what
 * {@link BridgeConfig#fromProperties(Properties)} makes of the same bytes. If someone adds a key to
 * {@code bridge.properties} that Spring cannot see, or renames one on either side, the two configs
 * stop being equal and this fails with the difference printed.
 *
 * <p>The file's path arrives as {@code -Dbridge.properties.file} from Gradle, which also declares it
 * as a task input; guessing it from a working directory would make the test's behaviour depend on
 * how it was launched.
 */
class BridgeMainPropertyAliasesTest
{
    @Test
    void onlyTheFiveGroupedKeysAreTranslatedAndTheirValuesAreCarriedOver()
    {
        final Map<String, Object> source = new LinkedHashMap<>();
        source.put("bridge.amps.uri", "tcp://elsewhere:9007/amps/fix");
        source.put("bridge.amps.clientName", "renamed");
        source.put("bridge.amps.guaranteedPublishing", "true");
        source.put("bridge.reconnect.initialBackoffMs", "125");
        source.put("bridge.reconnect.maxBackoffMs", "9000");
        // Already canonical, or not ours: neither must produce an alias.
        source.put("bridge.flushTimeoutMs", "1000");
        source.put("bridge.route[0].msgType", "D");
        source.put("artio.port", "9880");

        assertEquals(
            Map.of(
                "bridge.uri", "tcp://elsewhere:9007/amps/fix",
                "bridge.client-name", "renamed",
                "bridge.guaranteed-publishing", "true",
                "bridge.reconnect-initial-backoff-ms", "125",
                "bridge.reconnect-max-backoff-ms", "9000"),
            BridgeMainPropertyAliases.translate(new MapPropertySource("test", source)));
    }

    @Test
    void aSourceWithNoBridgeMainSpellingsProducesNoAliasSourceAtAll()
    {
        assertEquals(
            Map.of(),
            BridgeMainPropertyAliases.translate(
                new MapPropertySource("test", Map.of("bridge.uri", "tcp://x:9007/amps/fix"))));
    }

    @Test
    void theShippedBridgePropertiesFileBindsToExactlyWhatBridgeMainWouldBuildFromIt() throws Exception
    {
        final Path file = bridgePropertiesFile();
        final Properties raw = new Properties();
        try (InputStream in = Files.newInputStream(file))
        {
            raw.load(in);
        }
        final BridgeConfig expected = BridgeConfig.fromProperties(raw);

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(PropertiesOnly.class)
            .web(WebApplicationType.NONE)
            .bannerMode(org.springframework.boot.Banner.Mode.OFF)
            // `location` rather than `additional-location`: it replaces the default search list, so
            // "the file wins over the packaged application.yml" is stated here rather than inferred
            // from Boot's search order. `optional:classpath:/` keeps application.yml as the floor,
            // and the file, being listed later, overrides it.
            .run("--spring.config.location=optional:classpath:/,file:" + file))
        {
            final BridgeProperties bound = context.getBean(BridgeProperties.class);

            // Every value in the shipped file happens to agree with the record defaults, so an
            // equal BridgeConfig alone would also be what a file that was never read produces.
            // Assert the machinery ran: the alias source exists, and it was derived from THIS file.
            final long aliasSources = StreamSupport
                .stream(context.getEnvironment().getPropertySources().spliterator(), false)
                .map(PropertySource::getName)
                .filter(name -> name.endsWith(BridgeMainPropertyAliases.SOURCE_SUFFIX))
                .filter(name -> name.contains(file.getFileName().toString()))
                .count();
            assertEquals(1, aliasSources,
                () -> "expected one alias source derived from " + file + " but the environment had " +
                    StreamSupport.stream(
                        context.getEnvironment().getPropertySources().spliterator(), false)
                        .map(PropertySource::getName).toList());

            assertEquals(expected, bound.toBridgeConfig(),
                "bridge.properties must mean the same thing to BridgeMain and to Spring");

            // Named individually as well, because a single equals() failure on a fifteen-component
            // record is a wall of text that does not say which key broke.
            final BridgeConfig actual = bound.toBridgeConfig();
            assertAll(
                () -> assertEquals("tcp://localhost:9007/amps/fix", actual.uri(),
                    "bridge.amps.uri -> bridge.uri"),
                () -> assertEquals("artio-bridge", actual.clientName(),
                    "bridge.amps.clientName -> bridge.client-name"),
                () -> assertEquals(false, actual.guaranteedPublishing(),
                    "bridge.amps.guaranteedPublishing -> bridge.guaranteed-publishing"),
                () -> assertEquals(250, actual.reconnectInitialBackoffMs(),
                    "bridge.reconnect.initialBackoffMs -> bridge.reconnect-initial-backoff-ms"),
                () -> assertEquals(30_000, actual.reconnectMaxBackoffMs(),
                    "bridge.reconnect.maxBackoffMs -> bridge.reconnect-max-backoff-ms"),
                () -> assertEquals(5, actual.routes().size(),
                    "bridge.route[0..4] binds with no translation at all"),
                () -> assertEquals(BridgeConfig.DEFAULT_ROUTES, actual.routes()));
        }
    }

    @Test
    void theArtioHalfOfTheShippedFileBindsWithNoTranslationWhatsoever() throws Exception
    {
        final Path file = bridgePropertiesFile();
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(PropertiesOnly.class)
            .web(WebApplicationType.NONE)
            .bannerMode(org.springframework.boot.Banner.Mode.OFF)
            .run("--spring.config.location=optional:classpath:/,file:" + file))
        {
            // artio.senderCompId in the file, senderCompId in the record: relaxed binding, not an
            // alias. These are the eight keys BridgeMain.engineConfig reads.
            final var engine = context.getBean(ArtioProperties.class).toFixEngineConfig();
            assertAll(
                () -> assertEquals("bridge", engine.name()),
                () -> assertEquals(com.demo.artio.engine.EngineMode.ACCEPTOR, engine.mode()),
                () -> assertEquals("0.0.0.0", engine.host()),
                () -> assertEquals(9880, engine.port()),
                () -> assertEquals("ARTIO", engine.senderCompId()),
                () -> assertEquals("QFJ", engine.targetCompId()),
                () -> assertEquals("FIX.4.2", engine.fixVersion().beginString()),
                () -> assertEquals(30, engine.heartbeatIntervalSec()));
        }
    }

    @Test
    void anAliasedKeyFromAnExternalFileBeatsTheDefaultInApplicationYml(@TempDir final Path dir)
        throws Exception
    {
        // The shipped bridge.properties happens to agree with application.yml on every value, so
        // proving the alias is *seen* proves nothing about precedence. This file disagrees.
        final Path file = dir.resolve("override.properties");
        Files.writeString(file, """
            bridge.amps.uri=tcp://elsewhere:19007/amps/fix
            bridge.amps.clientName=elsewhere
            bridge.reconnect.maxBackoffMs=11000
            """);

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(PropertiesOnly.class)
            .web(WebApplicationType.NONE)
            .bannerMode(org.springframework.boot.Banner.Mode.OFF)
            .run("--spring.config.location=optional:classpath:/,file:" + file))
        {
            final BridgeConfig actual = context.getBean(BridgeProperties.class).toBridgeConfig();
            assertAll(
                () -> assertEquals("tcp://elsewhere:19007/amps/fix", actual.uri()),
                () -> assertEquals("elsewhere", actual.clientName()),
                () -> assertEquals(11_000, actual.reconnectMaxBackoffMs()),
                // Untouched keys still come from application.yml, which is what "inherits the
                // source's standing" means: the alias source replaces nothing else.
                () -> assertEquals(4 * 1024 * 1024, actual.ringBufferCapacityBytes()));
        }
    }

    @Test
    void aCommandLineOverrideStillBeatsAnAliasedKeyFromAFile(@TempDir final Path dir) throws Exception
    {
        final Path file = dir.resolve("override.properties");
        Files.writeString(file, "bridge.amps.uri=tcp://from-file:19007/amps/fix\n");

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(PropertiesOnly.class)
            .web(WebApplicationType.NONE)
            .bannerMode(org.springframework.boot.Banner.Mode.OFF)
            .run(
                "--spring.config.location=optional:classpath:/,file:" + file,
                "--bridge.uri=tcp://from-args:29007/amps/fix"))
        {
            // The alias source is inserted immediately before the source it came from, so it sits
            // exactly where the canonical spelling would have: above application.yml, below the
            // command line.
            assertEquals("tcp://from-args:29007/amps/fix",
                context.getBean(BridgeProperties.class).toBridgeConfig().uri());
        }
    }

    private static Path bridgePropertiesFile() throws IOException
    {
        final String configured = System.getProperty("bridge.properties.file", "");
        assertTrue(!configured.isBlank(),
            "-Dbridge.properties.file was not set; see artio-spring-boot/build.gradle.kts");
        final Path file = Path.of(configured);
        assertTrue(Files.isReadable(file), () -> file + " is not readable");
        return file.toRealPath();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({ArtioProperties.class, BridgeProperties.class})
    static class PropertiesOnly
    {
    }
}
