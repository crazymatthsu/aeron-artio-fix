package com.demo.artio.bridge;

import java.util.List;

/**
 * An immutable snapshot of what the bridge has done, taken with
 * {@link AmpsFixPublisher#stats()}.
 *
 * <p>The three numbers that matter operationally:
 * <ul>
 *   <li>{@link #dropped()} - messages Artio delivered that never reached the ring buffer, because
 *       AMPS was too slow for too long. Should be zero.</li>
 *   <li>{@link #unroutable()} - messages that named a route's message type but not the tag that
 *       route's SOW topic is keyed on. Not published there, on purpose: AMPS would have accepted
 *       them and silently collapsed them onto one record. Should be zero, and if it is not, a
 *       counterparty is sending something the topic map does not describe.</li>
 *   <li>{@link #lostWhileDisconnected()} - messages that reached the publisher while AMPS was
 *       unreachable.</li>
 * </ul>
 *
 * <p>{@link #accepted()} minus {@link #drained()} is the message backlog; {@link #pendingBytes()}
 * is the same backlog measured the way the ring buffer measures it.
 *
 * @param accepted             messages written into the ring buffer by the Artio poll thread.
 * @param drained              messages read off it by the publisher agent.
 * @param dropped              messages the poll thread could not write because the ring was full.
 * @param published            successful publishes, summed over every route. One message on three
 *                             topics counts three.
 * @param unroutable           messages declined by a route for want of its required tag, summed.
 * @param adminSkipped         session-level messages dropped because no admin topic is configured.
 * @param publishErrors        publishes that failed for a reason other than a disconnect.
 * @param bytesPublished       total payload bytes handed to AMPS.
 * @param lostWhileDisconnected messages the port could not publish because AMPS was unreachable.
 * @param reconnects           how many times the port re-established its connection.
 * @param pendingBytes         bytes currently occupying the ring buffer, including its framing.
 * @param ringCapacityBytes    the ring buffer's usable capacity.
 * @param connected            whether the port had a live AMPS connection when this was taken.
 * @param routes               per-topic counters, default route first.
 */
public record BridgeStats(
    long accepted,
    long drained,
    long dropped,
    long published,
    long unroutable,
    long adminSkipped,
    long publishErrors,
    long bytesPublished,
    long lostWhileDisconnected,
    long reconnects,
    int pendingBytes,
    int ringCapacityBytes,
    boolean connected,
    List<RouteStats> routes)
{
    /**
     * One topic's counters.
     *
     * @param topic          the AMPS topic.
     * @param msgType        the {@code MsgType(35)} this route matches, or null for the default and
     *                       admin routes.
     * @param requiredTag    the tag the route insists on, or 0.
     * @param published      messages published to this topic.
     * @param unroutable     messages that matched the type but lacked {@code requiredTag}.
     * @param bytesPublished payload bytes published to this topic.
     */
    public record RouteStats(
        String topic,
        String msgType,
        int requiredTag,
        long published,
        long unroutable,
        long bytesPublished)
    {
        @Override
        public String toString()
        {
            return topic + "={published=" + published +
                (unroutable == 0 ? "" : ", unroutable=" + unroutable) + '}';
        }
    }

    /** @param routes defensively copied. */
    public BridgeStats
    {
        routes = List.copyOf(routes);
    }

    /** @return messages written into the ring buffer but not yet published. */
    public long pendingMessages()
    {
        return accepted - drained;
    }

    /** @return how full the ring buffer is, 0.0 to 1.0. */
    public double ringUtilisation()
    {
        return ringCapacityBytes == 0 ? 0.0 : (double)pendingBytes / ringCapacityBytes;
    }

    /**
     * The <strong>first</strong> route declared for a topic.
     *
     * <p>Several rules routinely share one topic - {@code 35=D}, {@code 35=G} and {@code 35=F} all
     * feed {@code fix.orders} - so this is the right call only for a topic you know is fed by one
     * rule. Use {@link #publishedTo(String)} and {@link #unroutableFor(String)} when you want the
     * topic's total.
     *
     * @param topic the AMPS topic.
     * @return the first route to it, or null if this bridge has none.
     */
    public RouteStats route(final String topic)
    {
        for (final RouteStats route : routes)
        {
            if (route.topic().equals(topic))
            {
                return route;
            }
        }
        return null;
    }

    /**
     * @param topic the AMPS topic.
     * @return every route that feeds it, in configuration order.
     */
    public List<RouteStats> routes(final String topic)
    {
        return routes.stream().filter(route -> route.topic().equals(topic)).toList();
    }

    /**
     * @param topic the AMPS topic.
     * @return how many messages were published to it, summed over every rule that feeds it; 0 if
     * there is no such route.
     */
    public long publishedTo(final String topic)
    {
        long total = 0;
        for (final RouteStats route : routes)
        {
            if (route.topic().equals(topic))
            {
                total += route.published();
            }
        }
        return total;
    }

    /**
     * @param topic the AMPS topic.
     * @return how many messages were declined for want of a required tag, summed over every rule
     * that feeds it. Non-zero means a counterparty is sending messages the topic map does not
     * describe, and they are on {@link BridgeConfig#defaultTopic()} instead.
     */
    public long unroutableFor(final String topic)
    {
        long total = 0;
        for (final RouteStats route : routes)
        {
            if (route.topic().equals(topic))
            {
                total += route.unroutable();
            }
        }
        return total;
    }

    /**
     * One line for a periodic log.
     *
     * @return the summary.
     */
    public String summary()
    {
        return "accepted=" + accepted + " published=" + published + " pending=" + pendingMessages() +
            " dropped=" + dropped + " unroutable=" + unroutable +
            " errors=" + publishErrors + " lost=" + lostWhileDisconnected +
            " bytes=" + bytesPublished + " ring=" + pendingBytes + '/' + ringCapacityBytes +
            (connected ? " connected" : " DISCONNECTED") + ' ' + routes;
    }
}
