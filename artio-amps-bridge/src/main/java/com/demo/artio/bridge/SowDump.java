package com.demo.artio.bridge;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;
import com.crankuptheamps.client.exception.DisconnectedException;
import com.crankuptheamps.client.exception.TimedOutException;
import com.demo.artio.engine.FixMessages;
import java.io.PrintStream;

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
    /** Exit code for a usage error: an unknown option, a missing value, or an unparsable number. */
    public static final int EXIT_USAGE = 2;

    /** Exit code when AMPS could not be reached or the query failed. */
    public static final int EXIT_FAILURE = 1;

    /**
     * The parsed command line. {@code filter} applies to both a SOW query and a journal replay:
     * AMPS accepts a content filter on a bookmark subscription exactly as on a {@code sow} command.
     */
    record Options(String uri, String topic, String replayTopic, String filter, int idleMs, boolean help)
    {
    }

    public static void main(final String[] args)
    {
        final int code = run(args, System.out, System.err);
        if (code != 0)
        {
            System.exit(code);
        }
    }

    /**
     * Runs the dump and returns the exit code instead of exiting, so a test can drive it.
     *
     * @return 0 on success, {@link #EXIT_USAGE} for a command-line error (usage is printed to
     * {@code err}), {@link #EXIT_FAILURE} when the query itself failed.
     */
    static int run(final String[] args, final PrintStream out, final PrintStream err)
    {
        final Options options;
        try
        {
            options = parse(args);
        }
        catch (final IllegalArgumentException e)
        {
            err.println("error: " + e.getMessage());
            usage(err);
            return EXIT_USAGE;
        }
        if (options.help())
        {
            usage(out);
            return 0;
        }

        try (Client client = new Client("artio-sow-dump-" + System.nanoTime()))
        {
            client.connect(options.uri());
            client.logon(CONNECT_TIMEOUT_MS);
            if (options.topic() != null)
            {
                dumpSow(client, options.uri(), options.topic(), options.filter(), options.idleMs(), out);
            }
            if (options.replayTopic() != null)
            {
                replay(client, options.uri(), options.replayTopic(), options.filter(), options.idleMs(), out);
            }
            return 0;
        }
        catch (final Exception e)
        {
            err.println("error: " + e.getMessage());
            return EXIT_FAILURE;
        }
    }

    /**
     * Parses the command line.
     *
     * @throws IllegalArgumentException for an unknown option, an option without its value, a
     *                                  {@code --timeout-ms} that is not a non-negative integer, or
     *                                  neither {@code --topic} nor {@code --replay} given.
     */
    static Options parse(final String[] args)
    {
        String uri = System.getProperty("bridge.amps.uri", BridgeConfig.DEFAULT_URI);
        String topic = null;
        String replayTopic = null;
        String filter = null;
        int idleMs = QUERY_IDLE_MS;
        boolean help = false;

        for (int i = 0; i < args.length; i++)
        {
            switch (args[i])
            {
                case "--topic" -> topic = value(args, ++i, "--topic");
                case "--replay" -> replayTopic = value(args, ++i, "--replay");
                case "--filter" -> filter = value(args, ++i, "--filter");
                case "--uri" -> uri = value(args, ++i, "--uri");
                case "--timeout-ms" -> idleMs = nonNegativeInt(value(args, ++i, "--timeout-ms"), "--timeout-ms");
                case "--help", "-h" -> help = true;
                default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }

        if (!help && topic == null && replayTopic == null)
        {
            throw new IllegalArgumentException("one of --topic or --replay is required");
        }
        return new Options(uri, topic, replayTopic, filter, idleMs, help);
    }

    private static int nonNegativeInt(final String text, final String option)
    {
        try
        {
            final int parsed = Integer.parseInt(text.trim());
            if (parsed < 0)
            {
                throw new IllegalArgumentException(option + " must be >= 0, got " + text);
            }
            return parsed;
        }
        catch (final NumberFormatException e)
        {
            throw new IllegalArgumentException(
                option + " must be an integer number of milliseconds, got '" + text + "'");
        }
    }

    private static void dumpSow(
        final Client client, final String uri, final String topic, final String filter, final int idleMs,
        final PrintStream out)
        throws Exception
    {
        out.printf("SOW %s on %s%s%n", topic, uri, filter == null ? "" : " where " + filter);
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
                    out.printf("  [%d] %s%n", ++count, FixMessages.printable(message.getData()));
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
        out.printf("%d record(s) in %s%n", count, topic);
    }

    private static void replay(
        final Client client, final String uri, final String topic, final String filter, final int idleMs,
        final PrintStream out) throws Exception
    {
        out.printf("journal replay of %s on %s from the epoch%s%n", topic, uri,
            filter == null ? "" : " where " + filter);
        int count = 0;
        final Command command = new Command("subscribe")
            .setTopic(topic)
            .setBookmark(Client.Bookmarks.EPOCH)
            .setTimeout(CONNECT_TIMEOUT_MS);
        if (filter != null && !filter.isBlank())
        {
            command.setFilter(filter);
        }
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
                    out.printf("  [%d] %s%n", ++count, FixMessages.printable(message.getData()));
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
        out.printf("%d message(s) in the %s journal%n", count, topic);
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

    private static void usage(final PrintStream out)
    {
        out.println("""
            usage: SowDump --topic <topic> [--filter "<amps filter>"] [--uri <amps uri>]
                   SowDump --replay <journalled topic> [--filter "<amps filter>"] [--uri <amps uri>]

              --topic       query the SOW: one record per key, as it stands now
              --replay      replay the transaction log from the epoch, and count it
              --filter      an AMPS content filter over FIX tags, e.g. "/11 = 'ORD-1'"; applies
                            to --topic and to --replay alike
              --uri         default tcp://localhost:9007/amps/fix
              --timeout-ms  idle time that ends a query (default 3000)

            exit codes: 0 done, 1 AMPS unreachable or the query failed, 2 usage error

            SOW topics in the artio-fix flow: fix.orders (/11), fix.execs (/17),
            fix.order.state (/37). fix.raw and fix.admin have no SOW; use --replay
            for fix.raw.""");
    }
}
