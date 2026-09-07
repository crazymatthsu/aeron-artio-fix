package com.demo.artio.boot;

import com.demo.artio.bridge.BridgeConfig;
import com.demo.artio.bridge.OverflowPolicy;
import com.demo.artio.bridge.TopicRoute;
import com.demo.artio.engine.IdleStrategyType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * The AMPS half of the configuration, bound from {@code bridge.*}.
 *
 * <p>Every key {@link com.demo.artio.bridge.BridgeConfig#fromProperties(java.util.Properties)}
 * understands is understood here too, with the same spelling. The list component is called
 * {@code route}, not {@code routes}, for exactly that reason: {@code bridge.route[0].msgType} is
 * what {@code bridge.properties} writes and what {@code BridgeConfig.fromProperties} reads, so
 * naming the component after the key means there is one spelling for routes across both entry
 * points and nothing to translate. Five keys <em>are</em> spelled differently, because
 * {@code BridgeMain} groups them and this record is flat - {@code bridge.amps.uri},
 * {@code bridge.amps.clientName}, {@code bridge.amps.guaranteedPublishing},
 * {@code bridge.reconnect.initialBackoffMs}, {@code bridge.reconnect.maxBackoffMs} -
 * and {@link BridgeMainPropertyAliases} translates those at environment-preparation time so the
 * shipped {@code bridge.properties} binds unchanged.
 *
 * <p>{@code route} is <strong>all-or-nothing</strong>, exactly as in {@code BridgeMain}: leave it
 * out and you get {@link BridgeConfig#DEFAULT_ROUTES}; define one and it replaces the whole set.
 * Merging a partial override into the defaults would be the more forgiving behaviour and the more
 * dangerous one, because it makes "I removed the fix.order.state rule" impossible to express.
 * Spring binds a collection from the single highest-precedence property source that has any element
 * of it, so an override file that defines two rules yields two rules, not two plus the five it did
 * not mention.
 *
 * @param enabled                   false leaves the {@link com.demo.artio.bridge.AmpsFixPublisher}
 *                                  bean out of the context; the engine then logs and publishes
 *                                  nothing.
 * @param uri                       the AMPS URI. The {@code /amps/fix} path is not decoration - it
 *                                  selects the server-side FIX parser, which is what makes
 *                                  {@code /11}-style SOW keys work.
 * @param clientName                the AMPS client name; a unique suffix is appended per
 *                                  connection.
 * @param defaultTopic              every application message goes here regardless of type; blank
 *                                  for none.
 * @param adminTopic                where session-level messages go when the next key is true.
 * @param publishAdminMessages      whether to publish session-level messages at all.
 * @param ringBufferCapacityBytes   the off-heap hand-off buffer; a power of two.
 * @param overflowPolicy            what the Artio poll thread does when that buffer is full.
 * @param overflowBlockTimeoutMs    how long {@code BLOCK} holds the poll thread before dropping.
 * @param flushTimeoutMs            the drain-and-flush budget at shutdown, and the AMPS logon
 *                                  timeout.
 * @param reconnectInitialBackoffMs the first delay after an AMPS disconnect.
 * @param reconnectMaxBackoffMs     the ceiling it doubles up to.
 * @param guaranteedPublishing      install a client-side publish store so unacknowledged messages
 *                                  are replayed on reconnect.
 * @param idleStrategy              how the publisher agent idles when the ring buffer is empty.
 * @param route                     the ordered routing rules, bound from {@code bridge.route[N]};
 *                                  empty means {@link BridgeConfig#DEFAULT_ROUTES}.
 * @param statsLogIntervalMs        how often {@link StatsLogger} prints the counters; 0 disables
 *                                  it, which is what the integration tests use so their output is
 *                                  only the flow.
 */
@Validated
@ConfigurationProperties(prefix = "bridge")
public record BridgeProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue(BridgeConfig.DEFAULT_URI) @NotBlank String uri,
    @DefaultValue("artio-bridge") @NotBlank String clientName,
    @DefaultValue(BridgeConfig.TOPIC_RAW) String defaultTopic,
    @DefaultValue(BridgeConfig.TOPIC_ADMIN) String adminTopic,
    @DefaultValue("false") boolean publishAdminMessages,
    @DefaultValue("4194304") @Min(BridgeConfig.MIN_RING_BUFFER_CAPACITY) int ringBufferCapacityBytes,
    @DefaultValue("DROP_AND_COUNT") @NotNull OverflowPolicy overflowPolicy,
    @DefaultValue("1000") @PositiveOrZero long overflowBlockTimeoutMs,
    @DefaultValue("10000") @Positive long flushTimeoutMs,
    @DefaultValue("250") @Positive long reconnectInitialBackoffMs,
    @DefaultValue("30000") @Positive long reconnectMaxBackoffMs,
    @DefaultValue("false") boolean guaranteedPublishing,
    @DefaultValue("BACKOFF") @NotNull IdleStrategyType idleStrategy,
    @DefaultValue @Valid List<Route> route,
    @DefaultValue("5000") @PositiveOrZero long statsLogIntervalMs)
{
    /** Normalises {@code route} so callers never see null. */
    public BridgeProperties
    {
        route = route == null ? List.of() : List.copyOf(route);
    }

    /**
     * One routing rule, bound from {@code bridge.route[N]} - the same key {@code bridge.properties}
     * uses.
     *
     * @param msgType     the {@code MsgType(35)} this rule matches, e.g. {@code D} or {@code 8}.
     * @param topic       the AMPS topic to publish to.
     * @param requiredTag the FIX tag that must be present before the rule fires - normally the
     *                    destination topic's SOW key. 0 means no condition. This is a correctness
     *                    requirement: AMPS accepts a SOW publish that lacks the topic's key and
     *                    silently collapses every such message into one record.
     */
    public record Route(
        @NotBlank String msgType,
        @NotBlank String topic,
        @DefaultValue("0") @PositiveOrZero int requiredTag)
    {
        /** @return the bridge's own record for this rule. */
        public TopicRoute toTopicRoute()
        {
            return new TopicRoute(msgType.trim(), topic.trim(), requiredTag);
        }
    }

    /**
     * Translates the bound properties into the bridge's own configuration record, which validates
     * again - the ring buffer must be a power of two, the back-off ceiling must not be below the
     * floor, and a bridge with no default topic and no routes is refused.
     *
     * @return the bridge configuration.
     * @throws IllegalArgumentException if the combination is invalid.
     */
    public BridgeConfig toBridgeConfig()
    {
        final BridgeConfig.Builder builder = BridgeConfig.builder()
            .uri(uri.trim())
            .clientName(clientName.trim())
            .defaultTopic(defaultTopic)
            .adminTopic(adminTopic)
            .publishAdminMessages(publishAdminMessages)
            .ringBufferCapacityBytes(ringBufferCapacityBytes)
            .overflowPolicy(overflowPolicy)
            .overflowBlockTimeoutMs(overflowBlockTimeoutMs)
            .flushTimeoutMs(flushTimeoutMs)
            .reconnectInitialBackoffMs(reconnectInitialBackoffMs)
            .reconnectMaxBackoffMs(reconnectMaxBackoffMs)
            .guaranteedPublishing(guaranteedPublishing)
            .idleStrategy(idleStrategy);
        if (!route.isEmpty())
        {
            builder.routes(route.stream().map(Route::toTopicRoute).toList());
        }
        return builder.build();
    }
}
