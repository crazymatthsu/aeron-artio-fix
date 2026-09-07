package com.demo.artio.bridge;

import com.demo.artio.engine.IdleStrategyType;
import org.agrona.BitUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Everything the bridge needs to know, validated at construction.
 *
 * <p>The defaults describe the {@code artio-fix} flow in
 * {@code amps-server/config/flows/artio-fix/amps-config.xml} exactly, so
 * {@code BridgeConfig.defaults()} against a locally running AMPS is a working bridge:
 *
 * <pre>
 *   every application message                 -&gt; fix.raw          (no SOW, journalled)
 *   35=D, 35=G, 35=F   carrying tag 11        -&gt; fix.orders       (SOW key /11)
 *   35=8               carrying tag 17        -&gt; fix.execs        (SOW key /17)
 *   35=8               carrying tag 37        -&gt; fix.order.state  (SOW key /37)
 *   session messages                          -&gt; dropped, or fix.admin
 * </pre>
 *
 * <h2>Properties</h2>
 * {@link #fromProperties(Properties)} binds the whole record from a flat key set; the keys are
 * listed on that method and in the module README. {@link BridgeMain} loads them from
 * {@code bridge.properties} and lets {@code -Dbridge.*} override.
 *
 * @param uri                       AMPS connection URI. Must select the {@code fix} message type -
 *                                  {@code tcp://host:9007/amps/fix} - because that is what makes
 *                                  the server parse FIX tags and honour {@code /11}-style SOW keys.
 * @param clientName                the AMPS client name. A unique suffix is appended per
 *                                  connection, unless {@code guaranteedPublishing} is on, when
 *                                  the name is used exactly as given so AMPS recognises the
 *                                  returning publisher; see
 *                                  {@link AmpsClientConnection#clientName(BridgeConfig)}.
 * @param routes                    the ordered routing rules, applied after {@code defaultTopic}.
 * @param defaultTopic              the topic every application message goes to regardless of type,
 *                                  or null for none. {@code fix.raw}: unkeyed, so nothing can be
 *                                  silently collapsed, and journalled, so it is the recovery path.
 * @param adminTopic                where session-level messages go when
 *                                  {@code publishAdminMessages} is true.
 * @param publishAdminMessages      whether to publish session-level messages at all. Off by
 *                                  default: a heartbeat per session per interval would be the
 *                                  largest and least useful stream in the instance.
 * @param ringBufferCapacityBytes   the hand-off buffer's capacity. A power of two, at least 1 KiB.
 * @param overflowPolicy            what the Artio poll thread does when that buffer is full.
 * @param overflowBlockTimeoutMs    how long {@link OverflowPolicy#BLOCK} waits before giving up and
 *                                  dropping. Ignored under {@link OverflowPolicy#DROP_AND_COUNT}.
 * @param flushTimeoutMs            how long {@code close()} waits for AMPS to acknowledge, and the
 *                                  logon timeout when connecting.
 * @param reconnectInitialBackoffMs the first delay after a disconnect.
 * @param reconnectMaxBackoffMs     the ceiling the delay doubles up to.
 * @param guaranteedPublishing      install a client-side publish store so unacknowledged messages
 *                                  are replayed on reconnect; see {@link AmpsClientConnection}.
 * @param idleStrategy              how the publisher agent idles when the ring buffer is empty.
 */
public record BridgeConfig(
    String uri,
    String clientName,
    List<TopicRoute> routes,
    String defaultTopic,
    String adminTopic,
    boolean publishAdminMessages,
    int ringBufferCapacityBytes,
    OverflowPolicy overflowPolicy,
    long overflowBlockTimeoutMs,
    long flushTimeoutMs,
    long reconnectInitialBackoffMs,
    long reconnectMaxBackoffMs,
    boolean guaranteedPublishing,
    IdleStrategyType idleStrategy)
{
    /** The AMPS instance {@code amps-server/scripts/amps.sh start} gives you. */
    public static final String DEFAULT_URI = "tcp://localhost:9007/amps/fix";

    /** Every application message, unkeyed and journalled. */
    public static final String TOPIC_RAW = "fix.raw";

    /** Order requests, SOW key {@code /11} (ClOrdID). */
    public static final String TOPIC_ORDERS = "fix.orders";

    /** Execution reports, SOW key {@code /17} (ExecID). */
    public static final String TOPIC_EXECS = "fix.execs";

    /** The live blotter, SOW key {@code /37} (OrderID). */
    public static final String TOPIC_ORDER_STATE = "fix.order.state";

    /** Session-level traffic, when it is published at all. */
    public static final String TOPIC_ADMIN = "fix.admin";

    /**
     * The routing rules for the {@code artio-fix} flow. Each required tag is the destination
     * topic's SOW key; see {@link TopicRoute} for why that is a correctness requirement and not a
     * precaution.
     */
    public static final List<TopicRoute> DEFAULT_ROUTES = List.of(
        TopicRoute.requiring("D", TOPIC_ORDERS, 11),
        TopicRoute.requiring("G", TOPIC_ORDERS, 11),
        TopicRoute.requiring("F", TOPIC_ORDERS, 11),
        TopicRoute.requiring("8", TOPIC_EXECS, 17),
        TopicRoute.requiring("8", TOPIC_ORDER_STATE, 37));

    /** 4 MiB: roughly 20 000 FIX messages in flight before anything is dropped. */
    public static final int DEFAULT_RING_BUFFER_CAPACITY = 4 * 1024 * 1024;

    /** Smallest ring buffer that is not obviously a mistake. */
    public static final int MIN_RING_BUFFER_CAPACITY = 1024;

    /**
     * @param uri                       the AMPS URI.
     * @param clientName                the AMPS client name.
     * @param routes                    the routing rules.
     * @param defaultTopic              the catch-all topic for application messages, or null.
     * @param adminTopic                the topic for session-level messages.
     * @param publishAdminMessages      whether to publish them.
     * @param ringBufferCapacityBytes   the hand-off buffer capacity; a power of two.
     * @param overflowPolicy            the full-buffer policy.
     * @param overflowBlockTimeoutMs    the BLOCK policy's bound.
     * @param flushTimeoutMs            the flush and logon timeout.
     * @param reconnectInitialBackoffMs the first reconnect delay.
     * @param reconnectMaxBackoffMs     the reconnect delay ceiling.
     * @param guaranteedPublishing      whether to install a publish store.
     * @param idleStrategy              the publisher agent's idle strategy.
     */
    public BridgeConfig
    {
        uri = requireText(uri, "uri");
        clientName = requireText(clientName, "clientName");
        routes = List.copyOf(routes);
        if (defaultTopic != null && defaultTopic.isBlank())
        {
            defaultTopic = null;
        }
        if (adminTopic != null && adminTopic.isBlank())
        {
            adminTopic = null;
        }
        if (publishAdminMessages && adminTopic == null)
        {
            throw new IllegalArgumentException(
                "publishAdminMessages is set but adminTopic is blank: there is nowhere to publish them");
        }
        if (defaultTopic == null && routes.isEmpty())
        {
            throw new IllegalArgumentException(
                "no defaultTopic and no routes: this bridge would publish nothing");
        }
        if (ringBufferCapacityBytes < MIN_RING_BUFFER_CAPACITY)
        {
            throw new IllegalArgumentException(
                "ringBufferCapacityBytes must be at least " + MIN_RING_BUFFER_CAPACITY +
                    ": " + ringBufferCapacityBytes);
        }
        if (!BitUtil.isPowerOfTwo(ringBufferCapacityBytes))
        {
            // Agrona's ring buffer masks rather than divides, so this is its requirement, not ours;
            // catching it here names the field instead of failing inside the buffer's constructor.
            throw new IllegalArgumentException(
                "ringBufferCapacityBytes must be a power of two: " + ringBufferCapacityBytes);
        }
        if (overflowPolicy == null)
        {
            throw new IllegalArgumentException("overflowPolicy must not be null");
        }
        if (idleStrategy == null)
        {
            throw new IllegalArgumentException("idleStrategy must not be null");
        }
        if (overflowBlockTimeoutMs < 0)
        {
            throw new IllegalArgumentException(
                "overflowBlockTimeoutMs must not be negative: " + overflowBlockTimeoutMs);
        }
        if (flushTimeoutMs <= 0)
        {
            throw new IllegalArgumentException("flushTimeoutMs must be positive: " + flushTimeoutMs);
        }
        if (reconnectInitialBackoffMs <= 0)
        {
            throw new IllegalArgumentException(
                "reconnectInitialBackoffMs must be positive: " + reconnectInitialBackoffMs);
        }
        if (reconnectMaxBackoffMs < reconnectInitialBackoffMs)
        {
            throw new IllegalArgumentException(
                "reconnectMaxBackoffMs (" + reconnectMaxBackoffMs + ") must be at least " +
                    "reconnectInitialBackoffMs (" + reconnectInitialBackoffMs + ')');
        }
    }

    /**
     * @return the {@code artio-fix} flow's configuration against a local AMPS.
     */
    public static BridgeConfig defaults()
    {
        return builder().build();
    }

    /**
     * @return a builder pre-loaded with the defaults.
     */
    public static Builder builder()
    {
        return new Builder();
    }

    /**
     * The admin topic as the router should see it: null when session-level messages are not being
     * published, which is what tells {@link TopicRouter} to drop them.
     *
     * @return the effective admin topic, or null.
     */
    public String effectiveAdminTopic()
    {
        return publishAdminMessages ? adminTopic : null;
    }

    /**
     * @return this configuration as a builder, for making one small change.
     */
    public Builder toBuilder()
    {
        return new Builder()
            .uri(uri)
            .clientName(clientName)
            .routes(routes)
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
    }

    // ------------------------------------------------------------------------------- properties

    /** Prefix every key in {@link #fromProperties(Properties)} carries. */
    public static final String PREFIX = "bridge.";

    /**
     * Binds a configuration from a flat property set. Unknown {@code bridge.*} keys are ignored;
     * absent keys keep their default.
     *
     * <table>
     *   <caption>Keys</caption>
     *   <tr><th>Key</th><th>Default</th></tr>
     *   <tr><td>{@code bridge.amps.uri}</td><td>{@value #DEFAULT_URI}</td></tr>
     *   <tr><td>{@code bridge.amps.clientName}</td><td>{@code artio-bridge}</td></tr>
     *   <tr><td>{@code bridge.amps.guaranteedPublishing}</td><td>false</td></tr>
     *   <tr><td>{@code bridge.defaultTopic}</td><td>{@code fix.raw}; blank means none</td></tr>
     *   <tr><td>{@code bridge.adminTopic}</td><td>{@code fix.admin}</td></tr>
     *   <tr><td>{@code bridge.publishAdminMessages}</td><td>false</td></tr>
     *   <tr><td>{@code bridge.ringBufferCapacityBytes}</td><td>4194304</td></tr>
     *   <tr><td>{@code bridge.overflowPolicy}</td><td>{@code DROP_AND_COUNT} or {@code BLOCK}</td></tr>
     *   <tr><td>{@code bridge.overflowBlockTimeoutMs}</td><td>1000</td></tr>
     *   <tr><td>{@code bridge.flushTimeoutMs}</td><td>10000</td></tr>
     *   <tr><td>{@code bridge.reconnect.initialBackoffMs}</td><td>250</td></tr>
     *   <tr><td>{@code bridge.reconnect.maxBackoffMs}</td><td>30000</td></tr>
     *   <tr><td>{@code bridge.idleStrategy}</td><td>{@code BACKOFF}, {@code BUSY_SPIN}, {@code SLEEPING}</td></tr>
     *   <tr><td>{@code bridge.route[N].msgType}</td><td>-</td></tr>
     *   <tr><td>{@code bridge.route[N].topic}</td><td>-</td></tr>
     *   <tr><td>{@code bridge.route[N].requiredTag}</td><td>0 (no condition)</td></tr>
     * </table>
     *
     * <p>The routes are all-or-nothing: define any {@code bridge.route[N].*} key and the whole
     * default set is replaced by what you define. Indices must run from 0 with no gap, and every
     * index needs both {@code .msgType} and {@code .topic}: a gap, a lone {@code .requiredTag}, or
     * an index that is not a number is an error naming the key, never a rule that is silently
     * dropped. Leave them out entirely and you get {@link #DEFAULT_ROUTES}. Merging a partial
     * override into the defaults would be the more forgiving behaviour and the more dangerous one
     * - it makes "I removed the fix.order.state rule" impossible to express.
     *
     * @param properties the source.
     * @return the configuration.
     * @throws IllegalArgumentException if a value is malformed or the result fails validation.
     */
    public static BridgeConfig fromProperties(final Properties properties)
    {
        final Builder builder = builder();
        text(properties, PREFIX + "amps.uri").ifPresent(builder::uri);
        text(properties, PREFIX + "amps.clientName").ifPresent(builder::clientName);
        text(properties, PREFIX + "amps.guaranteedPublishing")
            .ifPresent(value -> builder.guaranteedPublishing(bool(value, PREFIX + "amps.guaranteedPublishing")));
        // Present-but-blank is meaningful for the topics: it means "do not publish there".
        if (properties.getProperty(PREFIX + "defaultTopic") != null)
        {
            builder.defaultTopic(properties.getProperty(PREFIX + "defaultTopic").trim());
        }
        if (properties.getProperty(PREFIX + "adminTopic") != null)
        {
            builder.adminTopic(properties.getProperty(PREFIX + "adminTopic").trim());
        }
        text(properties, PREFIX + "publishAdminMessages")
            .ifPresent(value -> builder.publishAdminMessages(bool(value, PREFIX + "publishAdminMessages")));
        text(properties, PREFIX + "ringBufferCapacityBytes")
            .ifPresent(value -> builder.ringBufferCapacityBytes(
                intNumber(value, PREFIX + "ringBufferCapacityBytes")));
        text(properties, PREFIX + "overflowPolicy")
            .ifPresent(value -> builder.overflowPolicy(
                enumValue(OverflowPolicy.class, value, PREFIX + "overflowPolicy")));
        text(properties, PREFIX + "overflowBlockTimeoutMs")
            .ifPresent(value -> builder.overflowBlockTimeoutMs(
                number(value, PREFIX + "overflowBlockTimeoutMs")));
        text(properties, PREFIX + "flushTimeoutMs")
            .ifPresent(value -> builder.flushTimeoutMs(number(value, PREFIX + "flushTimeoutMs")));
        text(properties, PREFIX + "reconnect.initialBackoffMs")
            .ifPresent(value -> builder.reconnectInitialBackoffMs(
                number(value, PREFIX + "reconnect.initialBackoffMs")));
        text(properties, PREFIX + "reconnect.maxBackoffMs")
            .ifPresent(value -> builder.reconnectMaxBackoffMs(
                number(value, PREFIX + "reconnect.maxBackoffMs")));
        text(properties, PREFIX + "idleStrategy")
            .ifPresent(value -> builder.idleStrategy(
                enumValue(IdleStrategyType.class, value, PREFIX + "idleStrategy")));

        final List<TopicRoute> routes = routesFromProperties(properties);
        if (routes != null)
        {
            builder.routes(routes);
        }
        return builder.build();
    }

    /** The prefix of every route key: {@code bridge.route[N].field}. */
    private static final String ROUTE_PREFIX = PREFIX + "route[";

    /**
     * Reads every {@code bridge.route[N].*} key, whichever indices they name.
     *
     * <p>Scanning the key set rather than probing {@code route[0]}, {@code route[1]}, ... until one
     * is missing is what turns a typo into an error: with the probe, {@code route[2]} after a
     * missing {@code route[1]} is silently ignored, and a {@code route[0].requiredTag} with no
     * {@code msgType} or {@code topic} beside it means "keep the defaults" - both of which leave
     * the operator believing a rule exists that does not.
     *
     * @param properties the source.
     * @return the configured routes, or null if no {@code bridge.route[} key is present at all.
     * @throws IllegalArgumentException for an index gap, an index that is not a number, an index
     *                                  with only some of its fields, or a tag out of range.
     */
    private static List<TopicRoute> routesFromProperties(final Properties properties)
    {
        int highest = -1;
        for (final String key : properties.stringPropertyNames())
        {
            if (key.startsWith(ROUTE_PREFIX))
            {
                highest = Math.max(highest, routeIndex(key));
            }
        }
        if (highest < 0)
        {
            return null;
        }
        final List<TopicRoute> routes = new ArrayList<>(highest + 1);
        for (int i = 0; i <= highest; i++)
        {
            final String key = ROUTE_PREFIX + i + "]";
            final String msgType = properties.getProperty(key + ".msgType");
            final String topic = properties.getProperty(key + ".topic");
            if (msgType == null && topic == null)
            {
                throw new IllegalArgumentException(properties.getProperty(key + ".requiredTag") == null ?
                    key + " is missing: route indices must run from 0 with no gap (highest is " +
                        highest + ')' :
                    key + " has only .requiredTag; a route needs .msgType and .topic");
            }
            if (msgType == null || topic == null)
            {
                throw new IllegalArgumentException(key + " needs both .msgType and .topic");
            }
            final String tag = properties.getProperty(key + ".requiredTag");
            final int requiredTag = tag == null || tag.isBlank() ?
                TopicRoute.NO_REQUIRED_TAG : intNumber(tag, key + ".requiredTag");
            routes.add(new TopicRoute(msgType.trim(), topic.trim(), requiredTag));
        }
        return routes;
    }

    /**
     * @param key a property key beginning with {@link #ROUTE_PREFIX}.
     * @return the {@code N} in {@code bridge.route[N].field}.
     * @throws IllegalArgumentException if the key is not of that shape or {@code N} is not a
     *                                  non-negative integer.
     */
    private static int routeIndex(final String key)
    {
        final int close = key.indexOf(']', ROUTE_PREFIX.length());
        if (close < 0 || close + 1 >= key.length() || key.charAt(close + 1) != '.')
        {
            throw new IllegalArgumentException(
                "malformed route key " + key + ": expected " + ROUTE_PREFIX + "N].field");
        }
        final String digits = key.substring(ROUTE_PREFIX.length(), close);
        try
        {
            final int index = Integer.parseInt(digits);
            if (index < 0)
            {
                throw new NumberFormatException();
            }
            return index;
        }
        catch (final NumberFormatException e)
        {
            throw new IllegalArgumentException(
                "malformed route key " + key + ": the index must be a non-negative integer, not '" +
                    digits + '\'');
        }
    }

    private static java.util.Optional<String> text(final Properties properties, final String key)
    {
        final String value = properties.getProperty(key);
        return value == null || value.isBlank() ?
            java.util.Optional.empty() : java.util.Optional.of(value.trim());
    }

    private static boolean bool(final String value, final String key)
    {
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value))
        {
            return Boolean.parseBoolean(value);
        }
        throw new IllegalArgumentException(key + " must be true or false: " + value);
    }

    private static long number(final String value, final String key)
    {
        try
        {
            return Long.parseLong(value.trim());
        }
        catch (final NumberFormatException e)
        {
            throw new IllegalArgumentException(key + " must be a number: " + value, e);
        }
    }

    /**
     * A number that has to fit an {@code int}. Parsed as a long and range-checked, rather than
     * cast: {@code (int)4294967296L} is 0 and {@code (int)2147483648L} is negative, and either
     * would reach the validation below as a different, wrong number that names no key.
     */
    private static int intNumber(final String value, final String key)
    {
        final long parsed = number(value, key);
        if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE)
        {
            throw new IllegalArgumentException(
                key + " must be between " + Integer.MIN_VALUE + " and " + Integer.MAX_VALUE + ": " + value);
        }
        return (int)parsed;
    }

    private static <E extends Enum<E>> E enumValue(
        final Class<E> type, final String value, final String key)
    {
        try
        {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        }
        catch (final IllegalArgumentException e)
        {
            throw new IllegalArgumentException(
                key + " must be one of " + List.of(type.getEnumConstants()) + ": " + value, e);
        }
    }

    private static String requireText(final String value, final String field)
    {
        if (value == null || value.isBlank())
        {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    /** Builder for {@link BridgeConfig}; every field starts at the {@code artio-fix} default. */
    public static final class Builder
    {
        private String uri = DEFAULT_URI;
        private String clientName = "artio-bridge";
        private List<TopicRoute> routes = DEFAULT_ROUTES;
        private String defaultTopic = TOPIC_RAW;
        private String adminTopic = TOPIC_ADMIN;
        private boolean publishAdminMessages;
        private int ringBufferCapacityBytes = DEFAULT_RING_BUFFER_CAPACITY;
        private OverflowPolicy overflowPolicy = OverflowPolicy.DROP_AND_COUNT;
        private long overflowBlockTimeoutMs = 1_000;
        private long flushTimeoutMs = 10_000;
        private long reconnectInitialBackoffMs = 250;
        private long reconnectMaxBackoffMs = 30_000;
        private boolean guaranteedPublishing;
        private IdleStrategyType idleStrategy = IdleStrategyType.BACKOFF;

        private Builder()
        {
        }

        /** @param uri the AMPS URI; must select the {@code fix} message type. @return this. */
        public Builder uri(final String uri)
        {
            this.uri = uri;
            return this;
        }

        /** @param clientName the AMPS client name. @return this. */
        public Builder clientName(final String clientName)
        {
            this.clientName = clientName;
            return this;
        }

        /** @param routes the ordered routing rules. @return this. */
        public Builder routes(final List<TopicRoute> routes)
        {
            this.routes = List.copyOf(routes);
            return this;
        }

        /** @param routes the ordered routing rules. @return this. */
        public Builder routes(final TopicRoute... routes)
        {
            return routes(List.of(routes));
        }

        /**
         * Appends one rule to whatever is configured.
         *
         * @param route the rule.
         * @return this.
         */
        public Builder addRoute(final TopicRoute route)
        {
            final List<TopicRoute> combined = new ArrayList<>(this.routes);
            combined.add(route);
            this.routes = List.copyOf(combined);
            return this;
        }

        /** @param defaultTopic the catch-all topic, or null/blank for none. @return this. */
        public Builder defaultTopic(final String defaultTopic)
        {
            this.defaultTopic = defaultTopic;
            return this;
        }

        /** @param adminTopic where session-level messages go. @return this. */
        public Builder adminTopic(final String adminTopic)
        {
            this.adminTopic = adminTopic;
            return this;
        }

        /** @param publishAdminMessages whether to publish session-level messages. @return this. */
        public Builder publishAdminMessages(final boolean publishAdminMessages)
        {
            this.publishAdminMessages = publishAdminMessages;
            return this;
        }

        /** @param ringBufferCapacityBytes the hand-off capacity; a power of two. @return this. */
        public Builder ringBufferCapacityBytes(final int ringBufferCapacityBytes)
        {
            this.ringBufferCapacityBytes = ringBufferCapacityBytes;
            return this;
        }

        /** @param overflowPolicy the full-buffer policy. @return this. */
        public Builder overflowPolicy(final OverflowPolicy overflowPolicy)
        {
            this.overflowPolicy = overflowPolicy;
            return this;
        }

        /** @param overflowBlockTimeoutMs the BLOCK policy's bound. @return this. */
        public Builder overflowBlockTimeoutMs(final long overflowBlockTimeoutMs)
        {
            this.overflowBlockTimeoutMs = overflowBlockTimeoutMs;
            return this;
        }

        /** @param flushTimeoutMs the flush and logon timeout. @return this. */
        public Builder flushTimeoutMs(final long flushTimeoutMs)
        {
            this.flushTimeoutMs = flushTimeoutMs;
            return this;
        }

        /** @param reconnectInitialBackoffMs the first reconnect delay. @return this. */
        public Builder reconnectInitialBackoffMs(final long reconnectInitialBackoffMs)
        {
            this.reconnectInitialBackoffMs = reconnectInitialBackoffMs;
            return this;
        }

        /** @param reconnectMaxBackoffMs the reconnect delay ceiling. @return this. */
        public Builder reconnectMaxBackoffMs(final long reconnectMaxBackoffMs)
        {
            this.reconnectMaxBackoffMs = reconnectMaxBackoffMs;
            return this;
        }

        /** @param guaranteedPublishing whether to install a publish store. @return this. */
        public Builder guaranteedPublishing(final boolean guaranteedPublishing)
        {
            this.guaranteedPublishing = guaranteedPublishing;
            return this;
        }

        /** @param idleStrategy the publisher agent's idle strategy. @return this. */
        public Builder idleStrategy(final IdleStrategyType idleStrategy)
        {
            this.idleStrategy = idleStrategy;
            return this;
        }

        /** @return the validated configuration. */
        public BridgeConfig build()
        {
            return new BridgeConfig(
                uri, clientName, routes, defaultTopic, adminTopic, publishAdminMessages,
                ringBufferCapacityBytes, overflowPolicy, overflowBlockTimeoutMs, flushTimeoutMs,
                reconnectInitialBackoffMs, reconnectMaxBackoffMs, guaranteedPublishing, idleStrategy);
        }
    }
}
