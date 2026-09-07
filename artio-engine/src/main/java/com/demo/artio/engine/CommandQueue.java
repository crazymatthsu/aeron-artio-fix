package com.demo.artio.engine;

import org.agrona.concurrent.ManyToOneConcurrentLinkedQueue;

import java.util.Objects;

/**
 * The queue between {@link ArtioRuntime}'s callers and its poll thread, with one guarantee the raw
 * Agrona queue does not give: <strong>every command offered is eventually completed</strong>, even
 * one offered after the consumer has gone.
 *
 * <p>The race this closes: a producer reads "not closed", is descheduled, the poll thread drains
 * the queue for the last time in {@code onClose()} and exits, and only then does the producer's
 * {@code offer} land. Nobody would ever poll that command and its future would never complete. So
 * the consumer sets {@link #stop(Throwable)} <em>before</em> its final drain, and a producer checks
 * it <em>after</em> its offer: whichever order the two land in, either the drain sees the command
 * or the producer sees the stop flag (or both, which is harmless - failing a completed future is a
 * no-op).
 *
 * <p>Many producers, one consumer: {@link #poll()} and {@link #stop(Throwable)} must only be called
 * from the poll thread.
 */
final class CommandQueue
{
    /** A unit of work for the poll thread. */
    abstract static class Command
    {
        /** Completes the command's future exceptionally. Idempotent. */
        abstract void fail(Throwable error);
    }

    private final ManyToOneConcurrentLinkedQueue<Command> queue = new ManyToOneConcurrentLinkedQueue<>();

    /** Non-null once the consumer has stopped; the error every late command is failed with. */
    private volatile Throwable stopped;

    /**
     * Producer side, any thread. The command is either consumed by the poll thread or failed
     * here with the consumer's stop error.
     *
     * @param command the command.
     * @return true if the consumer may still pick it up, false if it was failed because the
     * consumer has stopped.
     */
    boolean offer(final Command command)
    {
        queue.offer(command);
        final Throwable error = stopped;
        if (error != null)
        {
            command.fail(error);
            return false;
        }
        return true;
    }

    /**
     * Consumer side only.
     *
     * @return the next command, or null if the queue is empty.
     */
    Command poll()
    {
        return queue.poll();
    }

    /**
     * Consumer side only: marks the queue stopped and then fails everything still in it. The
     * order is what makes {@link #offer(Command)}'s guarantee hold; do not reverse it.
     *
     * @param error what every late or undrained command is failed with.
     */
    void stop(final Throwable error)
    {
        stopped = Objects.requireNonNull(error, "error");
        Command command;
        while ((command = queue.poll()) != null)
        {
            command.fail(error);
        }
    }

    /** @return true once {@link #stop(Throwable)} has been called. */
    boolean isStopped()
    {
        return stopped != null;
    }
}
