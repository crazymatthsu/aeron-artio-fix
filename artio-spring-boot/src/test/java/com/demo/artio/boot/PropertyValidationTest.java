package com.demo.artio.boot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A bad value fails the context refresh, with the offending key named, before anything is started.
 *
 * <p>That is the whole point of putting jakarta constraints on the two records: without them a port
 * of {@code 70000} or a blank {@code SenderCompID} would be discovered by
 * {@code FixEngineConfig}'s constructor - which is still before a socket is opened, but inside a
 * bean factory method, where the failure reads as "Error creating bean with name 'artioRuntime'"
 * rather than as "artio.port must be less than or equal to 65535".
 *
 * <p>{@link ApplicationContextRunner} rather than {@code @SpringBootTest}: each case wants a
 * different, deliberately broken environment, and starting one context per case in-process costs
 * milliseconds. Only the binding machinery is registered, so nothing that could be started exists.
 */
class PropertyValidationTest
{
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
        .withUserConfiguration(PropertiesOnly.class);

    @Test
    void aPortOutsideOneToSixtyFiveThousandFiveHundredAndThirtyFiveIsRejectedByName()
    {
        runner.withPropertyValues("artio.port=70000")
            .run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .hasStackTraceContaining("artio.port")
                .hasStackTraceContaining("65535"));
    }

    @Test
    void aBlankSenderCompIdIsRejectedByName()
    {
        runner.withPropertyValues("artio.sender-comp-id=  ")
            .run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .hasStackTraceContaining("artio.senderCompId"));
    }

    @Test
    void aFixVersionThatIsNeitherFourTwoNorFourFourIsRejectedWithTheAllowedValues()
    {
        runner.withPropertyValues("artio.fix-version=FIX.5.0")
            .run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .hasStackTraceContaining("must be FIX.4.2 or FIX.4.4"));
    }

    @Test
    void aRingBufferSmallerThanTheMinimumIsRejectedByName()
    {
        runner.withPropertyValues("bridge.ring-buffer-capacity-bytes=64")
            .run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .hasStackTraceContaining("bridge.ringBufferCapacityBytes"));
    }

    @Test
    void aRouteWithoutATopicIsRejected()
    {
        runner.withPropertyValues("bridge.route[0].msg-type=D")
            .run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .hasStackTraceContaining("route[0].topic"));
    }

    @Test
    void aValueJakartaCannotExpressStillFailsInTheMapperWithAUsefulMessage()
    {
        // "the two CompIDs must differ" is not a field constraint, so it lives in FixEngineConfig's
        // constructor. Binding succeeds; the mapper is where it is caught. Both layers matter, and
        // this test is what stops someone deleting the second one as redundant.
        runner.withPropertyValues("artio.sender-comp-id=SAME", "artio.target-comp-id=SAME")
            .run(context ->
            {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(ArtioProperties.class))
                    .satisfies(properties -> assertThat(
                        org.junit.jupiter.api.Assertions.assertThrows(
                            IllegalArgumentException.class, properties::toFixEngineConfig))
                        .hasMessageContaining("must differ"));
            });
    }

    @Test
    void aRingBufferThatIsLargeEnoughButNotAPowerOfTwoFailsInTheMapper()
    {
        // Agrona masks rather than divides, so this is the bridge's rule and not something a
        // @Min can express. Same shape as the case above: bound, then refused.
        runner.withPropertyValues("bridge.ring-buffer-capacity-bytes=3000")
            .run(context ->
            {
                assertThat(context).hasNotFailed();
                assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    context.getBean(BridgeProperties.class)::toBridgeConfig))
                    .hasMessageContaining("power of two");
            });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({ArtioProperties.class, BridgeProperties.class})
    static class PropertiesOnly
    {
    }
}
