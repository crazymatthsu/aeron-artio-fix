package com.demo.artio.bridge;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;
import com.crankuptheamps.client.exception.DisconnectedException;
import com.crankuptheamps.client.exception.TimedOutException;
import com.demo.artio.engine.FixMessages;

/**
 * "Did it arrive?" - prints what an AMPS topic holds, as printable FIX.
 *
 * <pre>
 *   ./gradlew :artio-amps-bridge:sowDump --args="--topic fix.orders"
 *   ./gradlew :artio-amps-bridge:sowDump --args="--topic fix.orders --filter \"/11 = 'ORD-1'\""
 *   ./gradlew :artio-amps-bridge:sowDump --args="--replay fix.raw"
 *   ./gradlew :artio-amps-bridge:sowDump --args="--topic fix.execs --uri tcp://host:9007/amps/fix"
 * </pre>
 *
 * <p>{@code --topic} queries the <strong>SOW</strong>: what the topic holds <em>now</em>, one record
 * per key. {@code --replay} takes a bookmark subscription from the epoch and prints the
 * <strong>journal</strong> instead: everything ever published, in order, with a count. A topic with
 * no {@code <SOW>} declaration - {@code fix.raw}, {@code fix.admin} - has nothing to query and
 * answers {@code --topic} with silence; use {@code --replay} for those.
 *
 * <p>Output is printable FIX, SOH rendered as {@code |}. That is a rendering for eyes, not a
 * comparison form; the bytes on the wire really do contain SOH, and this module's integration tests
 * assert against the raw payload.
 */
public final class SowDump
{
    /** How long a query may sit idle before it is considered finished. */
    private static final int QUERY_IDLE_MS = 3_000;

    private static final long CONNECT_TIMEOUT_MS = 10_000;

    private SowDump()
    {
    }

    /**
     * @param args {@code --topic}, {@code --replay}, {@code --filter}, {@code --uri},
     *             {@code --timeout-ms}.
     * @throws Exception if AMPS could not be reached.
     */
    public static void main(final String[] args) throws Exception
    {
        String uri = System.getProperty("bridge.amps.uri", BridgeConfig.DEFAULT_URI);
        String topic = null;
        String replayTopic = null;
        String filter = null;
        int idleMs = QUERY_IDLE_MS;

        for (int i = 0; i < args.length; i++)
        {
            switch (args[i])
            {
                case "--topic" -> topic = value(args, ++i, "--topic");
                case "--replay" -> replayTopic = value(args, ++i, "--replay");
                case "--filter" -> filter = value(args, ++i, "--filter");
                case "--uri" -> uri = value(args, ++i, "--uri");
                case "--timeout-ms" -> idleMs = Integer.parseInt(value(args, ++i, "--timeout-ms"));
                case "--help", "-h" ->
                {
                    usage();
                    return;
                }
                default -> throw new IllegalArgumentException(
                    "unknown argument: " + args[i] + " (try --help)");
            }
        }

        if (topic == null && replayTopic == null)
        {
            usage();
            throw new IllegalArgumentException("one of --topic or --replay is required");
        }

        try (Client client = new Client("artio-sow-dump-" + System.nanoTime()))
        {
            client.connect(uri);
            client.logon(CONNECT_TIMEOUT_MS);
            if (topic != null)
            {
                dumpSow(client, uri, topic, filter, idleMs);
            }
            if (replayTopic != null)
            {
                replay(client, uri, replayTopic, idleMs);
            }
        }
    }

    private static void dumpSow(
        final Client client, final String uri, final String topic, final String filter, final int idleMs)
        throws Exception
    {
        System.out.printf("SOW %s on %s%s%n", topic, uri, filter == null ? "" : " where " + filter);
        int count = 0;
        try (MessageStream stream = filter == null || filter.isBlank() ?
            client.sow(topic) : client.sow(topic, filter))
        {
            stream.timeout(idleMs);
            for (final Message message : stream)
            {
                // An expired stream timeout can surface as a null rather than an exception.
                if (message == null)
                {
                    break;
                }
                // A SOW query also delivers group markers and acks, which carry no data.
                if (message.getCommand() == Message.Command.SOW && !message.isDataNull())
                {
                    System.out.printf("  [%d] %s%n", ++count, FixMessages.printable(message.getData()));
                }
            }
        }
        catch (final RuntimeException e)
        {
            if (!isExpectedEnd(e))
            {
                throw e;
            }
        }
        System.out.printf("%d record(s) in %s%n", count, topic);
    }

    private static void replay(
        final Client client, final String uri, final String topic, final int idleMs) throws Exception
    {
        System.out.printf("journal replay of %s on %s from the epoch%n", topic, uri);
        int count = 0;
        final Command command = new Command("subscribe")
            .setTopic(topic)
            .setBookmark(Client.Bookmarks.EPOCH)
            .setTimeout(CONNECT_TIMEOUT_MS);
        try (MessageStream stream = client.execute(command))
        {
            // A replay has no end marker - it replays history and then goes live - so "finished" is
            // "nothing has arrived for a while". This call therefore always costs at least idleMs.
            stream.timeout(idleMs);
            for (final Message message : stream)
            {
                if (message == null)
                {
                    break;
                }
                if (!message.isDataNull())
                {
                    System.out.printf("  [%d] %s%n", ++count, FixMessages.printable(message.getData()));
                }
            }
        }
        catch (final RuntimeException e)
        {
            if (!isExpectedEnd(e))
            {
                throw e;
            }
        }
        System.out.printf("%d message(s) in the %s journal%n", count, topic);
    }

    private static boolean isExpectedEnd(final Throwable error)
    {
        for (Throwable current = error; current != null; current = current.getCause())
        {
            if (current instanceof TimedOutException || current instanceof DisconnectedException)
            {
                return true;
            }
            if (current == current.getCause())
            {
                break;
            }
        }
        return false;
    }

    private static String value(final String[] args, final int index, final String option)
    {
        if (index >= args.length)
        {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[index];
    }

    private static void usage()
    {
        System.out.println("""
            usage: SowDump --topic <topic> [--filter "<amps filter>"] [--uri <amps uri>]
                   SowDump --replay <journalled topic> [--uri <amps uri>]

              --topic       query the SOW: one record per key, as it stands now
              --replay      replay the transaction log from the epoch, and count it
              --filter      an AMPS content filter over FIX tags, e.g. "/11 = 'ORD-1'"
              --uri         default tcp://localhost:9007/amps/fix
              --timeout-ms  idle time that ends a query (default 3000)

            SOW topics in the artio-fix flow: fix.orders (/11), fix.execs (/17),
            fix.order.state (/37). fix.raw and fix.admin have no SOW; use --replay
            for fix.raw.""");
    }
}
