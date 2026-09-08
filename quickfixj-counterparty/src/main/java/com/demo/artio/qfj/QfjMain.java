package com.demo.artio.qfj;

import java.io.PrintStream;
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
 * Every message sent or received is printed with SOH shown as {@code |}. The initiator sends a drop
 * copy stream - order events and the execution reports they produced - and exits once it has been
 * sent and it has reported what went out; the acceptor runs until Ctrl-C.
 */
public final class QfjMain
{
    /** How long to wait for the session to log on before giving up. */
    private static final Duration LOGON_TIMEOUT = Duration.ofSeconds(30);

    /**
     * How long to wait after the scenario has been sent, for the one peer that answers - this
     * module's own acceptor mode. Short: a drop copy consumer answers nothing, and the run is over
     * either way.
     */
    private static final Duration REPLY_TIMEOUT = Duration.ofSeconds(3);

    /** Exit code when the command line could not be parsed or describes an invalid configuration. */
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
        final int exitCode = run(args, System.out, System.err);
        if (exitCode != 0)
        {
            System.exit(exitCode);
        }
    }

    /**
     * Runs the command line and returns the exit code rather than calling {@code System.exit}, so
     * the whole path - parsing, configuration validation, the session - can be exercised in-process.
     *
     * @param args see {@link CliArgs#USAGE}.
     * @param out  where progress lines go.
     * @param err  where the usage text goes on a bad command line.
     * @return 0 on success; {@link #EXIT_BAD_ARGUMENTS} with the reason and the usage on {@code err}
     *         for anything wrong on the command line, including a value {@link QfjConfig} rejects;
     *         {@link #EXIT_NO_LOGON} when an initiator never logged on.
     * @throws InterruptedException if the calling thread is interrupted while waiting.
     */
    public static int run(final String[] args, final PrintStream out, final PrintStream err)
        throws InterruptedException
    {
        return run(args, out, err, ShutdownHooks.JVM);
    }

    /** As {@link #run(String[], PrintStream, PrintStream)}, with the shutdown hook registry chosen by the caller. */
    static int run(final String[] args, final PrintStream out, final PrintStream err, final ShutdownHooks hooks)
        throws InterruptedException
    {
        if (CliArgs.isHelp(args))
        {
            out.println(CliArgs.USAGE);
            return 0;
        }

        final CliArgs parsed;
        final QfjConfig config;
        try
        {
            parsed = CliArgs.parse(args);
            // The range checks live in QfjConfig; a value it rejects is a usage error like any other,
            // not a stack trace.
            config = parsed.toConfig();
        }
        catch (final IllegalArgumentException e)
        {
            err.println(e.getMessage());
            err.println();
            err.println(CliArgs.USAGE);
            return EXIT_BAD_ARGUMENTS;
        }

        return parsed.role() == QfjRole.INITIATOR ?
            runInitiator(parsed, config, out, err, hooks) :
            runAcceptor(parsed, config, out, err, hooks);
    }

    private static int runInitiator(
        final CliArgs args,
        final QfjConfig config,
        final PrintStream out,
        final PrintStream err,
        final ShutdownHooks hooks) throws InterruptedException
    {
        try (QfjInitiator initiator = new QfjInitiator(config))
        {
            // Before start(): a Ctrl-C while waiting for the logon or for the execution reports
            // must still send a Logout.
            final ShutdownHook hook = ShutdownHook.install(initiator, hooks, err);
            try
            {
                initiator.printMessages(true);
                initiator.start();

                if (!initiator.awaitLogon(LOGON_TIMEOUT))
                {
                    err.println("Did not log on to " + args.host() + ':' + args.port() +
                        " within " + LOGON_TIMEOUT.toSeconds() + "s");
                    return EXIT_NO_LOGON;
                }

                if (args.runScenario())
                {
                    final List<OrderScenario.Step> steps = args.scenario().steps();
                    final List<String> clOrdIds = initiator.run(steps);
                    final List<String> execIds = steps.stream()
                        .filter(OrderScenario.Report.class::isInstance)
                        .map(step -> ((OrderScenario.Report)step).execId())
                        .toList();

                    // This is a drop copy session: what goes out is a COPY of an order session's
                    // traffic, and the peer is a consumer, not a venue. So the interesting number is
                    // what was sent, not what came back - an Artio acceptor receives all of it,
                    // publishes it to AMPS and correctly answers nothing.
                    out.println("== sent scenario: " + clOrdIds);
                    out.println("== sent " + steps.size() + " message(s): " + clOrdIds.size() +
                        " order event(s) and " + execIds.size() + " execution report(s)" +
                        (execIds.isEmpty() ? "" : ' ' + execIds.toString()));

                    // The one peer that does answer is this module's own acceptor mode, which is a
                    // venue: two reports per new order and one each for the replace and the cancel.
                    // The wait is short because nothing depends on it.
                    initiator.awaitReceived(Tags.MSG_TYPE_EXECUTION_REPORT, answerableBy(steps), REPLY_TIMEOUT);
                    final int answers = initiator.receivedMessages(Tags.MSG_TYPE_EXECUTION_REPORT).size();
                    out.println(answers == 0 ?
                        "== the peer answered with no execution reports, which is what a drop copy " +
                            "consumer does" :
                        "== the peer answered with " + answers + " execution report(s): it is a venue, " +
                            "not a drop copy consumer");
                    return 0;
                }

                out.println("== logged on; no scenario requested, waiting for Ctrl-C");
                hook.awaitShutdown();
                return 0;
            }
            finally
            {
                hook.uninstall();
            }
        }
    }

    /**
     * @param steps the steps that were sent.
     * @return how many execution reports a <em>venue</em> would answer them with: two per new order
     *         - an acknowledgement and a fill - and one each for a replace and a cancel. A drop copy
     *         consumer answers none, and an execution report is answered by nobody.
     */
    private static int answerableBy(final List<OrderScenario.Step> steps)
    {
        int answers = 0;
        for (final OrderScenario.Step step : steps)
        {
            answers += switch (step)
            {
                case OrderScenario.NewOrder ignored -> 2;
                case OrderScenario.Replace ignored -> 1;
                case OrderScenario.Cancel ignored -> 1;
                case OrderScenario.Report ignored -> 0;
            };
        }
        return answers;
    }

    private static int runAcceptor(
        final CliArgs args,
        final QfjConfig config,
        final PrintStream out,
        final PrintStream err,
        final ShutdownHooks hooks) throws InterruptedException
    {
        try (QfjAcceptor acceptor = new QfjAcceptor(config))
        {
            final ShutdownHook hook = ShutdownHook.install(acceptor, hooks, err);
            try
            {
                acceptor.printMessages(true);
                acceptor.start();
                out.println("== listening on " + args.host() + ':' + args.port() +
                    " as " + args.senderCompId() + "; Ctrl-C to stop");
                hook.awaitShutdown();
                return 0;
            }
            finally
            {
                hook.uninstall();
            }
        }
    }

    /** Where a shutdown hook is registered: the JVM's runtime, or a recorder in a test. */
    interface ShutdownHooks
    {
        /** The JVM's own registry: {@link Runtime#addShutdownHook(Thread)}. */
        ShutdownHooks JVM = new ShutdownHooks()
        {
            @Override
            public void add(final Thread hook)
            {
                Runtime.getRuntime().addShutdownHook(hook);
            }

            @Override
            public void remove(final Thread hook)
            {
                try
                {
                    Runtime.getRuntime().removeShutdownHook(hook);
                }
                catch (final IllegalStateException e)
                {
                    // Shutdown is already in progress: the hook is what released us, and it has run.
                }
            }
        };

        void add(Thread hook);

        void remove(Thread hook);
    }

    /**
     * Closes the engine from the JVM's shutdown hook, and lets the main thread park until that
     * has happened.
     *
     * <p>Installed before {@code start()}, so a Ctrl-C at any point after the engine exists - while
     * an initiator waits for its logon or its execution reports, or while an acceptor listens -
     * still logs the session out. Closing inside the hook rather than after a latch matters: the
     * JVM waits for shutdown hooks to finish but not for {@code main}, so a hook that merely
     * released the main thread would race the process exit and the counterparty would see a
     * dropped connection instead of a Logout. {@code close()} is idempotent, so the
     * try-with-resources block that also calls it is harmless.
     */
    static final class ShutdownHook
    {
        private final Thread thread;
        private final ShutdownHooks hooks;
        private final CountDownLatch stopped = new CountDownLatch(1);

        private ShutdownHook(final AutoCloseable engine, final ShutdownHooks hooks, final PrintStream err)
        {
            this.hooks = hooks;
            this.thread = new Thread(() ->
            {
                try
                {
                    engine.close();
                }
                catch (final Exception e)
                {
                    err.println("Shutdown failed: " + e);
                }
                finally
                {
                    stopped.countDown();
                }
            }, "qfj-shutdown");
        }

        /** Registers a hook that closes {@code engine}; call before the engine is started. */
        static ShutdownHook install(final AutoCloseable engine, final ShutdownHooks hooks, final PrintStream err)
        {
            final ShutdownHook hook = new ShutdownHook(engine, hooks, err);
            hooks.add(hook.thread);
            return hook;
        }

        /** Parks the caller until the hook has run, that is until Ctrl-C has closed the engine. */
        void awaitShutdown() throws InterruptedException
        {
            stopped.await();
        }

        /** Nothing is left to close at exit once the engine has been closed on the normal path. */
        void uninstall()
        {
            hooks.remove(thread);
        }

        /** @return the registered hook thread; a test runs it the way the JVM would. */
        Thread thread()
        {
            return thread;
        }
    }
}
