package com.demo.artio.bridge;

import com.demo.artio.engine.FixMessageView;
import com.demo.artio.engine.FixVersion;
import com.demo.artio.engine.SessionKey;
import com.demo.artio.testharness.AmpsAssumptions;
import com.demo.artio.testharness.AmpsComposeServer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import uk.co.real_logic.artio.util.MessageTypeEncoding;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * How fast the hand-off is, measured rather than guessed:
 *
 * <pre>./gradlew :artio-amps-bridge:publishBenchmark</pre>
 *
 * <p>Asserts nothing, and is excluded from {@code integrationTest} and from {@code check}. It
 * pushes messages through {@link AmpsFixPublisher#onMessage} at full speed - which is what the Artio
 * poll thread does - and reports two numbers that matter for a different reason each:
 *
 * <ul>
 *   <li><strong>write rate</strong>: how long the poll thread spends in the sink. This is the number
 *       that decides whether the bridge can be attached to a busy session at all, and it should be
 *       far higher than the publish rate, because the write is a bounds check and a memcpy.</li>
 *   <li><strong>end-to-end rate</strong>: how fast the publisher agent gets messages into AMPS. The
 *       ceiling of the whole bridge, and what the ring buffer's capacity has to cover a burst
 *       above.</li>
 * </ul>
 *
 * <p>Peak ring occupancy says how much of the buffer this load actually used; if it reaches the
 * capacity, the run was publish-bound and messages were dropped.
 *
 * <p>Numbers are hardware- and network-specific. Run it where you publish; the ones in
 * {@code docs/03-artio-amps-bridge-design.md} came from this class on one laptop.
 */
class BridgeThroughputBenchmark
{
    private static final int MESSAGE_COUNT = 50_000;
    private static final SessionKey SESSION = new SessionKey(1L, "ARTIO", "QFJ", "FIX.4.2", true);

    private static AmpsComposeServer amps;

    @BeforeAll
    static void startAmps() throws Exception
    {
        AmpsAssumptions.assumeAvailable();
        amps = AmpsComposeServer.start("artio-fix");
    }

    @AfterAll
    static void stopAmps()
    {
        if (amps != null)
        {
            amps.close();
        }
    }

    @Test
    void measurePublishThroughput() throws Exception
    {
        final BridgeConfig config = BridgeItSupport.bridgeConfig(FixVersion.FIX42, amps.uri())
            .toBuilder()
            .flushTimeoutMs(60_000)
            .build();

        final AmpsFixPublisher publisher = new AmpsFixPublisher(config);
        publisher.start();

        long peakPending = 0;
        final long writeStartNs;
        final long writeEndNs;
        // Everything the loop needs is built once: the point of the measurement is what the sink
        // costs, not what building a FIX message in a test costs.
        final byte[] raw = message("BENCH-000001");
        final UnsafeBuffer buffer = new UnsafeBuffer(raw);
        final FixMessageView view = new FixMessageView();
        final long msgType = MessageTypeEncoding.packMessageType("D");
        try
        {
            // Warm up the JIT before the clock starts; the first few thousand messages are
            // interpreted and would dominate a 50k measurement.
            for (int i = 0; i < 5_000; i++)
            {
                publisher.onMessage(view.wrap(
                    buffer, 0, raw.length, msgType, i, 0, 0L, 3, true, SESSION));
            }

            writeStartNs = System.nanoTime();
            for (int i = 0; i < MESSAGE_COUNT; i++)
            {
                publisher.onMessage(view.wrap(
                    buffer, 0, raw.length, msgType, i, 0, 0L, 3, true, SESSION));
                if ((i & 0x3FF) == 0)
                {
                    peakPending = Math.max(peakPending, publisher.pending());
                }
            }
            writeEndNs = System.nanoTime();
        }
        finally
        {
            publisher.close();
        }
        final long endToEndNs = System.nanoTime() - writeStartNs;
        final BridgeStats stats = publisher.stats();

        final long writeMs = TimeUnit.NANOSECONDS.toMillis(writeEndNs - writeStartNs);
        final long totalMs = TimeUnit.NANOSECONDS.toMillis(endToEndNs);
        System.out.printf("%n=== bridge throughput, %d messages of ~%d bytes ===%n",
            MESSAGE_COUNT, raw.length);
        System.out.printf("  sink write (Artio poll thread) : %d ms, %,.0f msg/s, %.2f us/msg%n",
            writeMs, MESSAGE_COUNT * 1000.0 / Math.max(1, writeMs),
            (writeEndNs - writeStartNs) / 1000.0 / MESSAGE_COUNT);
        System.out.printf("  end to end (into AMPS, flushed): %d ms, %,.0f msg/s%n",
            totalMs, MESSAGE_COUNT * 1000.0 / Math.max(1, totalMs));
        System.out.printf("  publishes                      : %d over %d topic(s), %,d bytes%n",
            stats.published(), stats.routes().size(), stats.bytesPublished());
        System.out.printf("  peak ring occupancy            : %,d of %,d bytes (%.1f%%)%n",
            peakPending, stats.ringCapacityBytes(),
            100.0 * peakPending / stats.ringCapacityBytes());
        System.out.printf("  dropped=%d unroutable=%d errors=%d lost=%d%n%n",
            stats.dropped(), stats.unroutable(), stats.publishErrors(), stats.lostWhileDisconnected());
    }

    /**
     * The same load under {@link OverflowPolicy#BLOCK}, which is the other half of the trade-off:
     * the writer is now paced by AMPS instead of dropping, so the write rate collapses to the
     * publish rate and nothing is lost. Run both and the choice stops being a matter of taste.
     */
    @Test
    void measureBlockingPublishThroughput() throws Exception
    {
        final BridgeConfig config = BridgeItSupport.bridgeConfig(FixVersion.FIX42, amps.uri())
            .toBuilder()
            .clientName("artio-bridge-bench-block")
            .overflowPolicy(OverflowPolicy.BLOCK)
            .overflowBlockTimeoutMs(30_000)
            .flushTimeoutMs(60_000)
            .build();

        final AmpsFixPublisher publisher = new AmpsFixPublisher(config);
        publisher.start();

        final byte[] raw = message("BLOCK-000001");
        final UnsafeBuffer buffer = new UnsafeBuffer(raw);
        final FixMessageView view = new FixMessageView();
        final long msgType = MessageTypeEncoding.packMessageType("D");
        final long startNs;
        final long writeEndNs;
        try
        {
            for (int i = 0; i < 5_000; i++)
            {
                publisher.onMessage(view.wrap(buffer, 0, raw.length, msgType, i, 0, 0L, 3, true, SESSION));
            }
            startNs = System.nanoTime();
            for (int i = 0; i < MESSAGE_COUNT; i++)
            {
                publisher.onMessage(view.wrap(buffer, 0, raw.length, msgType, i, 0, 0L, 3, true, SESSION));
            }
            writeEndNs = System.nanoTime();
        }
        finally
        {
            publisher.close();
        }
        final long totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
        final long writeMs = TimeUnit.NANOSECONDS.toMillis(writeEndNs - startNs);
        final BridgeStats stats = publisher.stats();

        System.out.printf("%n=== bridge throughput under BLOCK, %d messages ===%n", MESSAGE_COUNT);
        System.out.printf("  sink write (paced by AMPS)     : %d ms, %,.0f msg/s%n",
            writeMs, MESSAGE_COUNT * 1000.0 / Math.max(1, writeMs));
        System.out.printf("  end to end (into AMPS, flushed): %d ms, %,.0f msg/s%n",
            totalMs, MESSAGE_COUNT * 1000.0 / Math.max(1, totalMs));
        System.out.printf("  dropped=%d published=%d%n%n", stats.dropped(), stats.published());
    }

    private static byte[] message(final String clOrdId)
    {
        return ("8=FIX.4.2\0019=110\00135=D\00149=QFJ\00156=ARTIO\00134=2\001" +
            "11=" + clOrdId + "\00155=MSFT\00154=1\00138=100\00140=2\00144=101.25\00110=123\001")
            .getBytes(StandardCharsets.US_ASCII);
    }
}
