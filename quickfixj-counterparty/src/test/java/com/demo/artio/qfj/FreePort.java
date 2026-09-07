package com.demo.artio.qfj;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;

/**
 * Hands out a TCP port the operating system has just confirmed is free.
 *
 * <p>No test in this build hard-codes a port: two suites running in parallel, or a developer with
 * something else already listening, would otherwise produce a failure that has nothing to do with
 * the code under test.
 */
public final class FreePort
{
    private FreePort()
    {
    }

    /**
     * @return a port that was free a moment ago. There is an unavoidable race between closing the
     * probe socket and the engine binding it; on a loopback interface it has never been observed to
     * matter, and the alternative - handing the listening socket over - is not something QuickFIX/J
     * or Artio support.
     */
    public static int next()
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
}
