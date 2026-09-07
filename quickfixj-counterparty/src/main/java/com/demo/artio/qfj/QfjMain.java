package com.demo.artio.qfj;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Command line entry point: "the other FIX engine", runnable on its own.
 *
 * <pre>
 * ./gradlew :quickfixj-counterparty:run --args="initiator --host localhost --port 9880 \
 *     --version FIX.4.2 --sender QFJ --target ARTIO --scenario orders"
 *
 * ./gradlew :quickfixj-counterparty:run --args="acceptor --port 9881 --version FIX.4.4 \
 *     --sender QFJ --target ARTIO"
 * </pre>
 *
 * Every message sent or received is printed with SOH shown as {@code |}. The initiator exits when
 * its scenario has run and the replies have stopped arriving; the acceptor runs until Ctrl-C.
 */
public final class QfjMain
{
    /** How long to wait for the session to log on before giving up. */
    private static final Duration LOGON_TIMEOUT = Duration.ofSeconds(30);

    /** How long to wait for execution reports after the scenario has been sent. */
    private static final Duration REPLY_TIMEOUT = Duration.ofSeconds(10);

    /** Exit code when the command line could not be parsed. */
    public static final int EXIT_BAD_ARGUMENTS = 2;

    /** Exit code when the session never logged on. */
    public static final int EXIT_NO_LOGON = 1;

    private QfjMain()
    {
    }

    /**
     * @param args see {@link CliArgs#USAGE}.
     * @throws InterruptedException if the process is interrupted while waiting.
     */
    public static void main(final String[] args) throws InterruptedException
    {
        if (CliArgs.isHelp(args))
        {
            System.out.println(CliArgs.USAGE);
            return;
        }

        final CliArgs parsed;
        try
        {
            parsed = CliArgs.parse(args);
        }
        catch (final IllegalArgumentException e)
        {
            System.err.println(e.getMessage());
            System.err.println();
            System.err.println(CliArgs.USAGE);
            System.exit(EXIT_BAD_ARGUMENTS);
            return;
        }

        if (parsed.role() == QfjRole.INITIATOR)
        {
            runInitiator(parsed);
        }
        else
        {
            runAcceptor(parsed);
        }
    }

    private static void runInitiator(final CliArgs args) throws InterruptedException
    {
        try (QfjInitiator initiator = new QfjInitiator(args.toConfig()))
        {
            initiator.printMessages(true);
            initiator.start();

            if (!initiator.awaitLogon(LOGON_TIMEOUT))
            {
                System.err.println("Did not log on to " + args.host() + ':' + args.port() +
                    " within " + LOGON_TIMEOUT.toSeconds() + "s");
                System.exit(EXIT_NO_LOGON);
                return;
            }

            if (args.runScenario())
            {
                final List<String> clOrdIds = initiator.run(OrderScenario.DEFAULT);
                System.out.println("== sent scenario: " + clOrdIds);
                // A counterparty that answers (the QuickFIX/J acceptor) sends two reports per new
                // order plus one each for the replace and the cancel. One that does not (a bare
                // Artio acceptor with a sink that only records) sends none, which is not an error.
                final int expectedReports = 8;
                initiator.awaitReceived(Tags.MSG_TYPE_EXECUTION_REPORT, expectedReports, REPLY_TIMEOUT);
                System.out.println("== received " +
                    initiator.receivedMessages(Tags.MSG_TYPE_EXECUTION_REPORT).size() +
                    " execution report(s) of an expected " + expectedReports);
            }
            else
            {
                System.out.println("== logged on; no scenario requested, waiting for Ctrl-C");
                awaitShutdownSignal(initiator);
            }
        }
    }

    private static void runAcceptor(final CliArgs args) throws InterruptedException
    {
        try (QfjAcceptor acceptor = new QfjAcceptor(args.toConfig()))
        {
            acceptor.printMessages(true);
            acceptor.start();
            System.out.println("== listening on " + args.host() + ':' + args.port() +
                " as " + args.senderCompId() + "; Ctrl-C to stop");
            awaitShutdownSignal(acceptor);
        }
    }

    /**
     * Parks the main thread until Ctrl-C, and closes the engine from the shutdown hook itself.
     *
     * <p>Closing it in the hook rather than after the latch matters: the JVM waits for shutdown
     * hooks to finish but not for {@code main}, so a hook that merely released the main thread
     * would race the process exit and the counterparty would see a dropped connection instead of a
     * logout. {@code close()} is idempotent, so the try-with-resources block that also calls it is
     * harmless.
     */
    private static void awaitShutdownSignal(final AutoCloseable engine) throws InterruptedException
    {
        final CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
        {
            try
            {
                engine.close();
            }
            catch (final Exception e)
            {
                System.err.println("Shutdown failed: " + e);
            }
            finally
            {
                stopped.countDown();
            }
        }, "qfj-shutdown"));
        stopped.await();
    }
}
