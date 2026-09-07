package com.demo.artio.bridge;

/**
 * One routing rule: "a message whose {@code MsgType(35)} is {@code msgType} goes to {@code topic},
 * provided it carries {@code requiredTag}".
 *
 * <p>{@code requiredTag} is the whole point of this record, and it is a correctness requirement
 * rather than a defensive nicety. AMPS 5.3.5.135 does <strong>not</strong> reject a SOW publish
 * that lacks the topic's key field: it accepts it, logs nothing, and stores it under one degenerate
 * key shared by every such message, so publishing three keyless messages leaves one record. The
 * failure is silent, unbounded data loss that presents as "the SOW looks short". See
 * {@code docs/05-integration-testing-and-demo.md} section 8.1, which pins the behaviour down with a
 * test, and the header comment in {@code amps-server/config/flows/artio-fix/amps-config.xml}.
 *
 * <p>The check therefore lives here, in the publisher, because there is nowhere else it can live.
 *
 * @param msgType     the {@code MsgType(35)} value this rule matches, e.g. {@code D} or {@code 8}.
 *                    Matched exactly; there is no wildcard, because a rule that matched everything
 *                    is what {@link BridgeConfig#defaultTopic()} is for.
 * @param topic       the AMPS topic to publish to.
 * @param requiredTag the FIX tag that must be present in the raw message before this rule fires,
 *                    or {@link #NO_REQUIRED_TAG} for "no condition". Normally the SOW key of
 *                    {@code topic}: 11 for {@code fix.orders}, 17 for {@code fix.execs}, 37 for
 *                    {@code fix.order.state}.
 */
public record TopicRoute(String msgType, String topic, int requiredTag)
{
    /** {@code requiredTag} value meaning "publish every message of this type". */
    public static final int NO_REQUIRED_TAG = 0;

    /**
     * @param msgType the {@code MsgType(35)} value.
     * @param topic   the AMPS topic.
     * @param requiredTag the tag that must be present, or {@link #NO_REQUIRED_TAG}.
     */
    public TopicRoute
    {
        msgType = requireText(msgType, "msgType");
        topic = requireText(topic, "topic");
        if (requiredTag < 0)
        {
            throw new IllegalArgumentException("requiredTag must not be negative: " + requiredTag);
        }
    }

    /**
     * A rule with no tag condition.
     *
     * @param msgType the {@code MsgType(35)} value.
     * @param topic   the AMPS topic.
     * @return the rule.
     */
    public static TopicRoute of(final String msgType, final String topic)
    {
        return new TopicRoute(msgType, topic, NO_REQUIRED_TAG);
    }

    /**
     * A rule guarded by the destination topic's SOW key.
     *
     * @param msgType     the {@code MsgType(35)} value.
     * @param topic       the AMPS topic.
     * @param requiredTag the tag that must be present.
     * @return the rule.
     */
    public static TopicRoute requiring(final String msgType, final String topic, final int requiredTag)
    {
        return new TopicRoute(msgType, topic, requiredTag);
    }

    /** @return true when this rule fires only for messages carrying {@link #requiredTag()}. */
    public boolean hasRequiredTag()
    {
        return requiredTag != NO_REQUIRED_TAG;
    }

    private static String requireText(final String value, final String field)
    {
        if (value == null || value.isBlank())
        {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.indexOf('\001') >= 0 || value.indexOf('=') >= 0)
        {
            throw new IllegalArgumentException(field + " must not contain SOH or '=': " + value);
        }
        return value;
    }

    @Override
    public String toString()
    {
        return "35=" + msgType + " -> " + topic + (hasRequiredTag() ? " requires " + requiredTag : "");
    }
}
