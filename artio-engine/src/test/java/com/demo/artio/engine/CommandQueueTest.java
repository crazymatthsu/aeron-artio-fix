package com.demo.artio.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one guarantee {@link CommandQueue} adds over a plain queue: no command is ever left
 * unconsumed and uncompleted, whichever side of the consumer's exit it was offered on.
 */
class CommandQueueTest
{
    /** Records how it was failed; the future stands in for the caller's. */
    private static final class Probe extends CommandQueue.Command
    {
        private final List<Throwable> failures = new ArrayList<>();

        @Override
        void fail(final Throwable error)
        {
            failures.add(error);
        }
    }

    private final CommandQueue queue = new CommandQueue();
    private final IllegalStateException stop = new IllegalStateException("shutting down");

    @Test
    void aCommandOfferedWhileTheConsumerIsRunningIsHandedToPollUntouched()
    {
        final Probe probe = new Probe();

        assertAll(
            () -> assertTrue(queue.offer(probe)),
            () -> assertSame(probe, queue.poll()),
            () -> assertNull(queue.poll()),
            () -> assertEquals(List.of(), probe.failures),
            () -> assertFalse(queue.isStopped()));
    }

    @Test
    void stopFailsEverythingStillQueuedInOrder()
    {
        final Probe first = new Probe();
        final Probe second = new Probe();
        queue.offer(first);
        queue.offer(second);

        queue.stop(stop);

        assertAll(
            () -> assertEquals(List.of(stop), first.failures),
            () -> assertEquals(List.of(stop), second.failures),
            () -> assertNull(queue.poll(), "stop drained the queue"),
            () -> assertTrue(queue.isStopped()));
    }

    @Test
    void aCommandOfferedAfterStopIsFailedByTheProducerItselfSinceNobodyWillPollIt()
    {
        queue.stop(stop);
        final Probe late = new Probe();

        final boolean accepted = queue.offer(late);

        assertAll(
            // This is the race the class exists for: the consumer has drained for the last time and
            // gone; the producer's offer lands afterwards. The producer sees the stop flag - which
            // was set before that final drain - and fails its own command.
            () -> assertFalse(accepted),
            () -> assertEquals(List.of(stop), late.failures));
    }

    @Test
    void failingACommandTwiceIsHarmlessSoBothSidesMayFailTheSameLateCommand()
    {
        // A command can be offered just before stop() and still be seen by both the consumer's
        // final drain and the producer's post-offer check. Either order must be safe.
        final Probe both = new Probe();
        queue.offer(both);
        queue.stop(stop);
        queue.offer(both);

        assertEquals(2, both.failures.size(), "failed by the drain and again by the late offer; both no-ops on a real future");
    }
}
