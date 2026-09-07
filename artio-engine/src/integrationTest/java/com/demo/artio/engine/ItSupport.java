package com.demo.artio.engine;

import com.demo.artio.qfj.QfjVersion;
import uk.co.real_logic.artio.builder.Encoder;
import uk.co.real_logic.artio.fields.UtcTimestampEncoder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared plumbing for the Artio integration suite: free ports, a scratch directory outside the
 * repository, the {@link FixVersion} to {@link QfjVersion} mapping, and encoders built with the
 * generated codecs.
 */
final class ItSupport
{
    /** Long enough for a media driver, an archive, an engine and a library to come up on a laptop. */
    static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(30);

    /** Long enough for a message to cross loopback and be polled off an Aeron stream. */
    static final Duration MESSAGE_TIMEOUT = Duration.ofSeconds(20);

    private ItSupport()
    {
    }

    /**
     * @return a TCP port the operating system has just confirmed is free. No test in this suite
     * hard-codes one: several runtimes start in the same JVM and the suite may run in parallel with
     * others.
     */
    static int freePort()
    {
        try (ServerSocket socket = new ServerSocket(0))
        {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
        catch (final IOException e)
        {
            throw new UncheckedIOException("Could not find a free TCP port", e);
        }
    }

    /**
     * Where each runtime puts its Aeron directory, archive and Artio log directory.
     *
     * <p>{@code java.io.tmpdir} rather than {@code build/}: Aeron leaves a mapped file behind if a
     * JVM is killed mid-test, and nothing that survives a crash should land inside the repository.
     * Override with {@code -Dartio.it.dir=...} when you want to look at what was written.
     *
     * @return the parent directory for every runtime started by this suite.
     */
    static Path baseDirectory()
    {
        final String override = System.getProperty("artio.it.dir", "");
        return Path.of(override.isBlank() ? System.getProperty("java.io.tmpdir") : override);
    }

    /**
     * @param version the engine's FIX version.
     * @return the counterparty's equivalent. The two enums are separate on purpose - see the module
     * comment in {@code quickfixj-counterparty/build.gradle.kts} - so the mapping lives here.
     */
    static QfjVersion counterpartyVersion(final FixVersion version)
    {
        return switch (version)
        {
            case FIX42 -> QfjVersion.FIX42;
            case FIX44 -> QfjVersion.FIX44;
        };
    }

    /**
     * Builds a {@code NewOrderSingle} with the codecs {@code :fix-codecs} generated.
     *
     * <p>This is where the FIX 4.2 / 4.4 encoder API difference shows up: 4.4 keeps {@code Symbol}
     * in the {@code Instrument} component and {@code OrderQty} in {@code OrderQtyData}, so those go
     * through component encoders, while 4.2 is flat. {@code HandlInst(21)} is required by the FIX
     * 4.2 dictionary only.
     *
     * @param version       which generated codec to use.
     * @param clOrdId       {@code ClOrdID(11)}.
     * @param symbol        {@code Symbol(55)}.
     * @param quantity      {@code OrderQty(38)}, an integer number of shares.
     * @param priceUnscaled {@code Price(44)} mantissa, e.g. 10125.
     * @param priceScale    {@code Price(44)} scale, e.g. 2 for 101.25.
     * @return the encoder, ready for {@code ArtioRuntime.send}.
     */
    static Encoder newOrderSingle(
        final FixVersion version,
        final String clOrdId,
        final String symbol,
        final long quantity,
        final long priceUnscaled,
        final int priceScale)
    {
        final UtcTimestampEncoder transactTime = new UtcTimestampEncoder();
        final int transactTimeLength = transactTime.encode(System.currentTimeMillis());

        if (version == FixVersion.FIX42)
        {
            final com.demo.artio.fix42.builder.NewOrderSingleEncoder encoder =
                new com.demo.artio.fix42.builder.NewOrderSingleEncoder();
            encoder.clOrdID(clOrdId);
            encoder.handlInst(
                com.demo.artio.fix42.HandlInst.AUTOMATED_EXECUTION_ORDER_PRIVATE_NO_BROKER_INTERVENTION);
            encoder.symbol(symbol);
            encoder.side(com.demo.artio.fix42.Side.BUY);
            encoder.orderQty(quantity, 0);
            encoder.ordType(com.demo.artio.fix42.OrdType.LIMIT);
            encoder.price(priceUnscaled, priceScale);
            encoder.transactTime(transactTime.buffer(), transactTimeLength);
            return encoder;
        }

        final com.demo.artio.fix44.builder.NewOrderSingleEncoder encoder =
            new com.demo.artio.fix44.builder.NewOrderSingleEncoder();
        encoder.clOrdID(clOrdId);
        encoder.instrument().symbol(symbol);
        encoder.side(com.demo.artio.fix44.Side.BUY);
        encoder.orderQtyData().orderQty(quantity, 0);
        encoder.ordType(com.demo.artio.fix44.OrdType.LIMIT);
        encoder.price(priceUnscaled, priceScale);
        encoder.transactTime(transactTime.buffer(), transactTimeLength);
        return encoder;
    }

    /**
     * Builds an {@code ExecutionReport} acknowledging an order, with the codecs
     * {@code :fix-codecs} generated.
     *
     * <p>The version difference here is {@code ExecTransType(20)}: FIX 4.2 requires it and FIX 4.4
     * removed it, so the 4.4 encoder has no such setter at all. {@code Symbol} moves into the
     * {@code Instrument} component in 4.4, as it does on {@code NewOrderSingle}.
     *
     * @param version  which generated codec to use.
     * @param orderId  {@code OrderID(37)}.
     * @param execId   {@code ExecID(17)}.
     * @param clOrdId  {@code ClOrdID(11)} of the order being reported on.
     * @param symbol   {@code Symbol(55)}.
     * @param quantity {@code LeavesQty(151)}; {@code CumQty} is reported as zero.
     * @return the encoder, ready for {@code ArtioRuntime.send}.
     */
    static Encoder executionReport(
        final FixVersion version,
        final String orderId,
        final String execId,
        final String clOrdId,
        final String symbol,
        final long quantity)
    {
        if (version == FixVersion.FIX42)
        {
            final com.demo.artio.fix42.builder.ExecutionReportEncoder encoder =
                new com.demo.artio.fix42.builder.ExecutionReportEncoder();
            encoder.orderID(orderId);
            encoder.execID(execId);
            encoder.clOrdID(clOrdId);
            encoder.execTransType('0');
            encoder.execType('0');
            encoder.ordStatus('0');
            encoder.symbol(symbol);
            encoder.side('1');
            encoder.leavesQty(quantity, 0);
            encoder.cumQty(0, 0);
            encoder.avgPx(0, 0);
            return encoder;
        }

        final com.demo.artio.fix44.builder.ExecutionReportEncoder encoder =
            new com.demo.artio.fix44.builder.ExecutionReportEncoder();
        encoder.orderID(orderId);
        encoder.execID(execId);
        encoder.clOrdID(clOrdId);
        encoder.execType('0');
        encoder.ordStatus('0');
        encoder.instrument().symbol(symbol);
        encoder.side('1');
        encoder.leavesQty(quantity, 0);
        encoder.cumQty(0, 0);
        encoder.avgPx(0, 0);
        return encoder;
    }

    /** @return the names of every live thread in this JVM right now. */
    static Set<String> liveThreadNames()
    {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(Thread::isAlive)
            .map(Thread::getName)
            .collect(Collectors.toSet());
    }

    /**
     * Words that appear in the thread names Aeron and Artio create.
     *
     * <p>Artio's threads can be identified precisely, because {@link ArtioRuntime} sets
     * {@code agentNamePrefix} to the runtime id. Aeron's cannot: agrona's
     * {@code AgentRunner.startOnThread} overwrites whatever a thread factory named the thread with
     * {@code agent.roleName()}, so a media driver in SHARED mode is always called something like
     * {@code aeron-shared} no matter who started it. Hence a word list, applied only to threads
     * that did not exist before the runtime was started.
     */
    private static final String[] ARTIO_OR_AERON_THREAD_WORDS =
    {
        "aeron", "archive", "artio", "conductor", "driver", "framer", "indexer",
        "logger", "monitoring", "error printer", "shared", "sender", "receiver"
    };

    /**
     * @param threadName a thread name.
     * @return true if it looks like a thread Aeron or Artio started.
     */
    static boolean looksLikeArtioOrAeronThread(final String threadName)
    {
        final String lower = threadName.toLowerCase();
        for (final String word : ARTIO_OR_AERON_THREAD_WORDS)
        {
            if (lower.contains(word))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * @param before the thread names alive before the runtime was started.
     * @return the names of threads that exist now, did not exist then, and look like Aeron's or
     * Artio's.
     */
    static Set<String> leakedThreadNames(final Set<String> before)
    {
        return liveThreadNames().stream()
            .filter(name -> !before.contains(name))
            .filter(ItSupport::looksLikeArtioOrAeronThread)
            .collect(Collectors.toSet());
    }
}
