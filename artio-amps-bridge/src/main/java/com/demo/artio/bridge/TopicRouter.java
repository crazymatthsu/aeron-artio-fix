package com.demo.artio.bridge;

import com.demo.artio.engine.FixMessageView;
import uk.co.real_logic.artio.util.MessageTypeEncoding;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decides which AMPS topics a message goes to, without allocating and without building a String.
 *
 * <h2>The rules</h2>
 * <ol>
 *   <li>An <strong>admin</strong> message ({@code 0 1 2 3 4 5 A}) goes to the admin topic, or
 *       nowhere. It never reaches the default topic: {@code fix.raw} is the tape of application
 *       traffic, and a heartbeat every few seconds per session would be the largest and least
 *       useful stream in the instance.</li>
 *   <li>An <strong>application</strong> message always goes to the default topic
 *       ({@code fix.raw}), unconditionally. That topic has no SOW key, so nothing can be lost by
 *       publishing to it, and it is journalled - it is the recovery path for anything the rules
 *       below decline.</li>
 *   <li>Then every configured {@link TopicRoute} whose {@code msgType} matches, in configuration
 *       order. A route with a {@code requiredTag} fires only if that tag is actually present in the
 *       raw bytes; otherwise the message is counted as {@link CompiledRoute#unroutable()} for that
 *       route and is <strong>not</strong> published there.</li>
 * </ol>
 *
 * <h2>Why the tag check exists</h2>
 * AMPS accepts a SOW publish that lacks the topic's key and silently collapses every such record
 * into one. There is no server-side guard to mirror; this is the only one. See {@link TopicRoute}
 * and {@code docs/05-integration-testing-and-demo.md} section 8.1.
 *
 * <h2>Allocation</h2>
 * Everything is resolved once, at construction: message types are packed into Artio's {@code long}
 * encoding so matching is a primitive comparison, and topic names are encoded to {@code byte[]} so
 * the publish call has nothing to convert. {@link #route} allocates nothing per message, creates no
 * {@code String}, and touches the payload only through {@link FixTags#containsTag}.
 *
 * <h2>Threading</h2>
 * One router per {@link AmpsFixPublisher}. {@link #route} is called only from the publisher agent
 * thread; the counters are {@link AtomicLong}s so {@link AmpsFixPublisher#stats()} can be read from
 * anywhere.
 */
public final class TopicRouter
{
    /**
     * One rule, compiled: the packed message type to compare against and the topic name already in
     * bytes, plus this route's two counters.
     */
    public static final class CompiledRoute
    {
        private final String msgType;
        private final long packedMsgType;
        private final String topic;
        private final byte[] topicBytes;
        private final int requiredTag;
        private final boolean admin;
        private final boolean matchesEveryApplicationType;
        private final AtomicLong published = new AtomicLong();
        private final AtomicLong unroutable = new AtomicLong();
        private final AtomicLong bytesPublished = new AtomicLong();

        private CompiledRoute(
            final String msgType,
            final String topic,
            final int requiredTag,
            final boolean admin,
            final boolean matchesEveryApplicationType)
        {
            this.msgType = msgType;
            this.packedMsgType = msgType == null ? 0 : MessageTypeEncoding.packMessageType(msgType);
            this.topic = topic;
            this.topicBytes = topic.getBytes(StandardCharsets.US_ASCII);
            this.requiredTag = requiredTag;
            this.admin = admin;
            this.matchesEveryApplicationType = matchesEveryApplicationType;
        }

        /** @return the {@code MsgType(35)} this route matches, or null for the default/admin route. */
        public String msgType()
        {
            return msgType;
        }

        /** @return the AMPS topic name. */
        public String topic()
        {
            return topic;
        }

        /**
         * The topic name, pre-encoded, ready for
         * {@code Client.publish(byte[], int, int, byte[], int, int)}. The array is this route's
         * own; callers must not modify it.
         *
         * @return the US-ASCII bytes of {@link #topic()}.
         */
        public byte[] topicBytes()
        {
            return topicBytes;
        }

        /** @return the tag that must be present, or {@link TopicRoute#NO_REQUIRED_TAG}. */
        public int requiredTag()
        {
            return requiredTag;
        }

        /** @return true if this is the admin route. */
        public boolean isAdminRoute()
        {
            return admin;
        }

        /** @return true if this is the catch-all route for application messages. */
        public boolean isDefaultRoute()
        {
            return matchesEveryApplicationType;
        }

        /** @return how many messages have been published to this topic. */
        public long published()
        {
            return published.get();
        }

        /**
         * How many messages matched this route's message type but did not carry its
         * {@link #requiredTag()}, and were therefore not published here. Always zero for a route
         * with no required tag. A non-zero value means a counterparty is sending messages that
         * would have been silently collapsed into one SOW record.
         *
         * @return the count.
         */
        public long unroutable()
        {
            return unroutable.get();
        }

        /** @return the total payload bytes published to this topic. */
        public long bytesPublished()
        {
            return bytesPublished.get();
        }

        /**
         * Records a successful publish. Called by {@link AmpsFixPublisher} after the port has
         * accepted the message.
         *
         * @param payloadLength the number of payload bytes published.
         */
        void recordPublished(final int payloadLength)
        {
            published.incrementAndGet();
            bytesPublished.addAndGet(payloadLength);
        }

        /** @return an immutable snapshot of this route's counters. */
        public BridgeStats.RouteStats snapshot()
        {
            return new BridgeStats.RouteStats(
                topic, msgType, requiredTag, published.get(), unroutable.get(), bytesPublished.get());
        }

        @Override
        public String toString()
        {
            final String type = msgType == null ? (admin ? "admin" : "*") : "35=" + msgType;
            return type + " -> " + topic + (requiredTag == 0 ? "" : " requires " + requiredTag);
        }
    }

    /** What {@link TopicRouter#route} calls for each topic a message should be published to. */
    @FunctionalInterface
    public interface RouteHandler
    {
        /**
         * @param route the topic to publish this message to.
         */
        void onRoute(CompiledRoute route);
    }

    private final CompiledRoute defaultRoute;
    private final CompiledRoute adminRoute;
    private final CompiledRoute[] rules;
    private final List<CompiledRoute> all;
    private final AtomicLong adminSkipped = new AtomicLong();

    /**
     * Builds a router from a configuration.
     *
     * @param config the bridge configuration; {@link BridgeConfig#routes()},
     *               {@link BridgeConfig#defaultTopic()}, {@link BridgeConfig#adminTopic()} and
     *               {@link BridgeConfig#publishAdminMessages()} are read.
     */
    public TopicRouter(final BridgeConfig config)
    {
        this(config.routes(), config.defaultTopic(), config.effectiveAdminTopic());
    }

    /**
     * Builds a router from its parts.
     *
     * @param routes       the ordered rules; may be empty.
     * @param defaultTopic the topic every application message goes to, or null for none.
     * @param adminTopic   the topic session-level messages go to, or null to drop them.
     */
    public TopicRouter(final List<TopicRoute> routes, final String defaultTopic, final String adminTopic)
    {
        this.defaultRoute = defaultTopic == null || defaultTopic.isBlank() ?
            null : new CompiledRoute(null, defaultTopic, TopicRoute.NO_REQUIRED_TAG, false, true);
        this.adminRoute = adminTopic == null || adminTopic.isBlank() ?
            null : new CompiledRoute(null, adminTopic, TopicRoute.NO_REQUIRED_TAG, true, false);
        this.rules = new CompiledRoute[routes.size()];
        for (int i = 0; i < routes.size(); i++)
        {
            final TopicRoute route = routes.get(i);
            rules[i] = new CompiledRoute(route.msgType(), route.topic(), route.requiredTag(), false, false);
        }

        final List<CompiledRoute> ordered = new ArrayList<>(rules.length + 2);
        if (defaultRoute != null)
        {
            ordered.add(defaultRoute);
        }
        ordered.addAll(List.of(rules));
        if (adminRoute != null)
        {
            ordered.add(adminRoute);
        }
        this.all = List.copyOf(ordered);
    }

    /**
     * Resolves the topics for one message and hands each to {@code handler}, in publish order.
     *
     * <p>Allocates nothing. The default route (if any) comes first, then matching rules in
     * configuration order. A rule whose required tag is missing is counted, not published, and not
     * passed to the handler.
     *
     * @param packedMsgType Artio's packed {@code MsgType(35)}; see
     *                      {@link FixMessageView#msgType()}.
     * @param admin         whether this is a session-level message; see
     *                      {@link FixMessageView#isAdmin()}.
     * @param data          the raw message bytes.
     * @param offset        the index of the first byte of the message.
     * @param length        the message length.
     * @param handler       called once per destination topic.
     * @return the number of topics the message was routed to.
     */
    public int route(
        final long packedMsgType,
        final boolean admin,
        final byte[] data,
        final int offset,
        final int length,
        final RouteHandler handler)
    {
        if (admin)
        {
            if (adminRoute == null)
            {
                adminSkipped.incrementAndGet();
                return 0;
            }
            handler.onRoute(adminRoute);
            return 1;
        }

        int routed = 0;
        if (defaultRoute != null)
        {
            handler.onRoute(defaultRoute);
            routed++;
        }
        for (final CompiledRoute rule : rules)
        {
            if (rule.packedMsgType != packedMsgType)
            {
                continue;
            }
            if (rule.requiredTag != TopicRoute.NO_REQUIRED_TAG &&
                !FixTags.containsTag(data, offset, length, rule.requiredTag))
            {
                // The message names this route's type but not its SOW key. Publishing it would put
                // it on the topic's one keyless record, overwriting whatever was there. Counted so
                // it is visible; still on fix.raw, so it is still recoverable.
                rule.unroutable.incrementAndGet();
                continue;
            }
            handler.onRoute(rule);
            routed++;
        }
        return routed;
    }

    /**
     * The same decision as {@link #route}, materialised as a list. Allocates; for tests and
     * diagnostics, not for the hot path.
     *
     * @param msgType the {@code MsgType(35)} value, e.g. {@code D}.
     * @param admin   whether this is a session-level message.
     * @param rawFix  the raw message bytes.
     * @return the routes this message would be published to, in order.
     */
    public List<CompiledRoute> resolve(final String msgType, final boolean admin, final byte[] rawFix)
    {
        final List<CompiledRoute> matched = new ArrayList<>(3);
        route(MessageTypeEncoding.packMessageType(msgType), admin, rawFix, 0, rawFix.length, matched::add);
        return matched;
    }

    /** @return the default route for application messages, or null if there is none. */
    public CompiledRoute defaultRoute()
    {
        return defaultRoute;
    }

    /** @return the admin route, or null when admin messages are dropped. */
    public CompiledRoute adminRoute()
    {
        return adminRoute;
    }

    /** @return every route this router owns: default first, then the rules, then admin. */
    public List<CompiledRoute> routes()
    {
        return all;
    }

    /**
     * How many session-level messages were dropped because no admin topic is configured. Not an
     * error - it is the default - but it is counted rather than invisible.
     *
     * @return the count.
     */
    public long adminSkipped()
    {
        return adminSkipped.get();
    }

    @Override
    public String toString()
    {
        return "TopicRouter" + all;
    }
}
