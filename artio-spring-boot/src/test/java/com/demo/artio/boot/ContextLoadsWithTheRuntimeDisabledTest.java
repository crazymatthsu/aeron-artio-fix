package com.demo.artio.boot;

import com.demo.artio.bridge.AmpsFixPublisher;
import com.demo.artio.engine.ArtioRuntime;
import com.demo.artio.engine.FixMessageSink;
import com.demo.artio.engine.LoggingSink;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The context refreshes, the two properties records bind, and neither half of the runtime is
 * instantiated.
 *
 * <p>This is the cheap test that catches the expensive mistakes: a typo in a
 * {@code @ConfigurationProperties} key, a validation constraint that rejects the shipped defaults,
 * a bean that quietly needs another bean that is conditional. It runs in under a second and touches
 * no socket, no media driver and no container, because both {@code enabled} flags are false.
 *
 * <p>It also pins the "no servlet stack" claim rather than repeating it: if a dependency ever drags
 * {@code spring-web} and a servlet API onto the classpath, {@code deduceFromClasspath} would return
 * {@code SERVLET} and this application would start a web server next to the FIX engine. The
 * assertion below is the alarm, and it is written against the classpath rather than against the
 * context type because that is the thing that actually decides.
 */
@SpringBootTest(properties = {"artio.enabled=false", "bridge.enabled=false"})
class ContextLoadsWithTheRuntimeDisabledTest
{
    @Autowired
    private ApplicationContext context;

    @Autowired
    private ArtioProperties artioProperties;

    @Autowired
    private BridgeProperties bridgeProperties;

    @Test
    void thereIsNoServletStackOnTheClasspathSoNoPortButArtioSIsEverOpened()
    {
        assertAll(
            () -> assertThrows(ClassNotFoundException.class,
                () -> Class.forName("jakarta.servlet.Servlet"),
                "a servlet API on the classpath makes SpringApplication start a web server"),
            () -> assertThrows(ClassNotFoundException.class,
                () -> Class.forName("org.springframework.web.context.ConfigurableWebApplicationContext"),
                "spring-web on the classpath makes SpringApplication start a web server"),
            () -> assertFalse(context.getClass().getName().contains("Web"),
                () -> "expected a plain application context but got " + context.getClass().getName()));
    }

    @Test
    void neitherTheEngineNorThePublisherIsInstantiatedWhenBothAreDisabled()
    {
        assertAll(
            () -> assertEquals(0, context.getBeanNamesForType(ArtioRuntime.class).length,
                "artio.enabled=false must leave the runtime out of the context, not merely stopped"),
            () -> assertEquals(0, context.getBeanNamesForType(AmpsFixPublisher.class).length,
                "bridge.enabled=false must leave the publisher out of the context"),
            () -> assertEquals(0, context.getBeanNamesForType(ArtioRuntimeLifecycle.class).length),
            () -> assertEquals(0, context.getBeanNamesForType(BridgePublisherLifecycle.class).length),
            () -> assertEquals(0, context.getBeanNamesForType(StatsLogger.class).length));
    }

    @Test
    void theSinkIsStillBuiltSoTheEngineCouldBeSwitchedBackOnWithoutAnotherBean()
    {
        // artio.log-messages defaults to true and the publisher is absent, so the composite
        // collapses to the single remaining sink.
        assertInstanceOf(LoggingSink.class, context.getBean(FixMessageSink.class));
    }

    @Test
    void theShippedDefaultsBindAndSurviveTheEngineAndBridgeValidatorsAsWell()
    {
        // Binding proves the jakarta constraints pass; the two mappers prove the records
        // FixEngineConfig and BridgeConfig accept the same values, which is a second, stricter set
        // of rules (CompIDs must differ, the ring buffer must be a power of two, ...).
        assertAll(
            () -> assertEquals(9880, artioProperties.toFixEngineConfig().port()),
            () -> assertEquals("ARTIO", artioProperties.toFixEngineConfig().senderCompId()),
            () -> assertEquals("QFJ", artioProperties.toFixEngineConfig().targetCompId()),
            () -> assertEquals("FIX.4.2", artioProperties.toFixEngineConfig().fixVersion().beginString()),
            () -> assertEquals("tcp://localhost:9007/amps/fix", bridgeProperties.toBridgeConfig().uri()),
            () -> assertEquals(5, bridgeProperties.toBridgeConfig().routes().size(),
                "an unset bridge.route must mean BridgeConfig.DEFAULT_ROUTES, not no routes"),
            () -> assertEquals(5_000, bridgeProperties.statsLogIntervalMs()),
            () -> assertTrue(artioProperties.logMessages()));
    }
}
