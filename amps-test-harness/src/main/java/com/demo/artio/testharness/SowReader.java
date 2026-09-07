package com.demo.artio.testharness;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;
import com.crankuptheamps.client.exception.AMPSException;
import com.crankuptheamps.client.exception.DisconnectedException;
import com.crankuptheamps.client.exception.TimedOutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reading a FIX-typed AMPS instance back, for assertions.
 *
 * <p>Two questions, and they are answered by two different subsystems, which
 * is the thing to keep straight when writing an assertion:
 *
 * <ul>
 *   <li>{@link #query} asks the <b>SOW</b>: current state, one record per key,
 *       available immediately and however long ago it was published.</li>
 *   <li>{@link #countFromEpoch} asks the <b>transaction log</b>: history, every
 *       message ever published to the topic, in order. A topic absent from
 *       {@code <TransactionLog>} answers zero here no matter how much was
 *       published to it - that is a configuration fact, not a bug.</li>
 * </ul>
 *
 * <p>Payloads come back exactly as published: SOH-separated {@code tag=value}
 * with the SOH bytes intact, so an assertion can compare against the bytes the
 * publisher sent. Use {@link #printable} for messages and log lines only.
 *
 * <p>Every method opens its own short-lived connection. This is test-support
 * code reading a handful of records; a shared client would buy nothing and
 * would couple the assertions to each other's connection state.
 */
public final class SowReader {

    private static final Logger log = LoggerFactory.getLogger(SowReader.class);

    /** The FIX field separator, and the one character that makes raw FIX unreadable. */
    public static final char SOH = '\u0001';

    private static final long CONNECT_TIMEOUT_MS = 10_000L;

    /** How long a completed query may sit idle before it is considered finished. */
    private static final int QUERY_IDLE_MS = 5_000;

    private static final AtomicLong CLIENT_SEQUENCE = new AtomicLong();

    private SowReader() {
    }

    /**
     * The records a SOW topic currently holds, as raw FIX payloads.
     *
     * @param uri    an AMPS URI selecting the {@code fix} message type, e.g.
     *               {@code AmpsComposeServer.uri()}
     * @param topic  the SOW topic; a topic with no {@code <SOW>} declaration
     *               returns nothing, because there is no state to query
     * @param filter an AMPS content filter over FIX tags, e.g.
     *               {@code /11 = 'ORDER-1'}, or {@code null} for every record
     * @return the payloads, SOH intact, in the order the server returned them
     */
    public static List<String> query(String uri, String topic, String filter)
            throws AMPSException {
        List<String> records = new ArrayList<>();
        try (Client client = connect(uri, "sow-query")) {
            try (MessageStream stream = filter == null || filter.isBlank()
                    ? client.sow(topic)
                    : client.sow(topic, filter)) {
                forEach(stream, QUERY_IDLE_MS, message -> {
                    // A SOW query also delivers GroupBegin/GroupEnd markers and
                    // acks, which carry no data.
                    if (message.getCommand() == Message.Command.SOW && !message.isDataNull()) {
                        records.add(message.getData());
                    }
                    return true;
                });
            }
        }
        log.debug("sow query {} filter {} returned {} record(s)", topic, filter, records.size());
        return records;
    }

    /** Every record on {@code topic}. */
    public static List<String> query(String uri, String topic) throws AMPSException {
        return query(uri, topic, null);
    }

    /**
     * How many messages the transaction log holds for {@code topic}: a bookmark
     * subscription replayed from the beginning of the journal.
     *
     * <p>{@code Client.Bookmarks.EPOCH} means "everything you have". The
     * subscription has no end marker - it replays history and then goes live -
     * so "the replay has finished" is "nothing has arrived for {@code timeout}",
     * which is why this method takes a duration and always costs at least that
     * long. Keep it small; the replay itself is fast.
     *
     * <p>This is the check that distinguishes a journalled topic from a merely
     * published one. {@code fix.raw} answers with the number of messages ever
     * published; {@code fix.order.state}, which is deliberately not in the
     * transaction log, answers zero.
     *
     * @param uri     an AMPS URI selecting the {@code fix} message type
     * @param topic   the topic to replay
     * @param timeout how long the replay may be idle before it is considered
     *                complete
     */
    public static long countFromEpoch(String uri, String topic, Duration timeout)
            throws AMPSException {
        int idleMs = Math.max(250, Math.toIntExact(timeout.toMillis()));
        long count;
        try (Client client = connect(uri, "epoch-replay")) {
            Command command = new Command("subscribe")
                    .setTopic(topic)
                    .setBookmark(Client.Bookmarks.EPOCH)
                    .setTimeout(CONNECT_TIMEOUT_MS);
            try (MessageStream stream = client.execute(command)) {
                count = forEach(stream, idleMs, message -> !message.isDataNull());
            }
        }
        log.debug("epoch replay of {} saw {} message(s)", topic, count);
        return count;
    }

    /**
     * Raw FIX with the SOH separators replaced by {@code |}, for a log line or
     * an assertion message.
     *
     * <p>Never for comparison against what was published: this is lossy in
     * principle - a {@code |} already in a field value is indistinguishable
     * from a separator afterwards - and comparing printable forms would hide a
     * publisher that mangled the separators, which is exactly the bug this
     * harness exists to catch.
     */
    public static String printable(String fix) {
        return fix == null ? null : fix.replace(SOH, '|');
    }

    // ---------------------------------------------------------------- private

    private static Client connect(String uri, String purpose) throws AMPSException {
        // Unique per connection: AMPS uses the client name as an identity, and
        // two live connections sharing one is a source of confusing behaviour.
        Client client = new Client("artio-harness-" + purpose + "-"
                + CLIENT_SEQUENCE.incrementAndGet() + "-" + System.nanoTime());
        try {
            client.connect(uri);
            client.logon(CONNECT_TIMEOUT_MS);
        } catch (AMPSException | RuntimeException e) {
            client.close();
            throw e;
        }
        return client;
    }

    /**
     * Consumes a stream until the handler says stop, the stream ends, or
     * nothing has arrived for {@code idleMillis}.
     *
     * <p>All three of the ways an AMPS client build can signal an expired
     * stream timeout are treated as the end: {@code hasNext()} false,
     * {@code next()} null, and a {@link TimedOutException} wrapped in a runtime
     * exception (the {@link java.util.Iterator} contract has nowhere to put a
     * checked one).
     *
     * @return the number of messages the handler accepted
     */
    private static long forEach(MessageStream stream, int idleMillis,
                                java.util.function.Predicate<Message> handler) {
        stream.timeout(idleMillis);
        long handled = 0;
        try {
            while (stream.hasNext()) {
                Message message = stream.next();
                if (message == null) {
                    break;
                }
                if (handler.test(message)) {
                    handled++;
                }
            }
        } catch (RuntimeException e) {
            if (!isExpectedEnd(e)) {
                throw e;
            }
        }
        return handled;
    }

    private static boolean isExpectedEnd(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof TimedOutException || current instanceof DisconnectedException) {
                return true;
            }
            if (current == current.getCause()) {
                break;
            }
        }
        return false;
    }
}
