package com.demo.artio.bridge;

import com.demo.artio.engine.FixEngineConfig;
import com.demo.artio.engine.FixVersion;
import com.demo.artio.qfj.QfjConfig;
import com.demo.artio.qfj.QfjVersion;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Free ports, a scratch directory outside the repository, and the two configurations every
 * integration test in this module builds. Copied in spirit from
 * {@code artio-engine/src/integrationTest/.../ItSupport}, not depended on: a test source set is not
 * a published artefact, and one module reaching into another's is how build graphs rot.
 */
final class BridgeItSupport
{
    /** The CompIDs used throughout: the bridge is ARTIO, the counterparty is QFJ. */
    static final String ARTIO_COMP_ID = "ARTIO";

    /** @see #ARTIO_COMP_ID */
    static final String QFJ_COMP_ID = "QFJ";

    /** Long enough for a media driver, an archive, an engine and a library on a laptop. */
    static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(30);

    /** Long enough for a message to cross loopback, an Aeron stream, a ring buffer and AMPS. */
    static final Duration MESSAGE_TIMEOUT = Duration.ofSeconds(20);

    private BridgeItSupport()
    {
    }

    /** @return a TCP port the operating system has just confirmed is free. */
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
     * Override with {@code -Dartio.it.dir=...}.
     *
     * @return the parent directory.
     */
    static Path baseDirectory()
    {
        final String override = System.getProperty("artio.it.dir", "");
        return Path.of(override.isBlank() ? System.getProperty("java.io.tmpdir") : override);
    }

    /**
     * @param version the FIX version to accept.
     * @param port    the bind port.
     * @return an Artio acceptor configuration.
     */
    static FixEngineConfig acceptorConfig(final FixVersion version, final int port)
    {
        return FixEngineConfig.acceptor()
            .name("bridge" + version.name())
            .address("localhost", port)
            .senderCompId(ARTIO_COMP_ID)
            .targetCompId(QFJ_COMP_ID)
            .fixVersion(version)
            .heartbeatIntervalSec(5)
            .baseDirectory(baseDirectory())
            .build();
    }

    /**
     * @param version the FIX version to speak.
     * @param port    where the acceptor is listening.
     * @return a QuickFIX/J initiator configuration pointing at it.
     */
    static QfjConfig initiatorConfig(final FixVersion version, final int port)
    {
        return QfjConfig.initiator()
            .version(counterpartyVersion(version))
            .address("localhost", port)
            .senderCompId(QFJ_COMP_ID)
            .targetCompId(ARTIO_COMP_ID)
            .heartbeatIntervalSec(5)
            .build();
    }

    /**
     * @param version the engine's FIX version.
     * @param uri     the AMPS URI from the harness.
     * @return a bridge configuration with the {@code artio-fix} flow's default routes.
     */
    static BridgeConfig bridgeConfig(final FixVersion version, final String uri)
    {
        return BridgeConfig.builder()
            .uri(uri)
            .clientName("artio-bridge-it-" + version.name().toLowerCase(java.util.Locale.ROOT))
            // Short, because a failing test should not sit for ten seconds at close().
            .flushTimeoutMs(5_000)
            .build();
    }

    /**
     * @param version the engine's FIX version.
     * @return the counterparty's equivalent. The two enums are separate on purpose.
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
     * @param rawFix a raw FIX message.
     * @param tag    the tag to read.
     * @return the field's value, or null if the message does not carry it.
     */
    static String field(final String rawFix, final int tag)
    {
        for (final String part : rawFix.split("\001"))
        {
            final int equals = part.indexOf('=');
            if (equals > 0 && part.substring(0, equals).chars().allMatch(Character::isDigit) &&
                Integer.parseInt(part.substring(0, equals)) == tag)
            {
                return part.substring(equals + 1);
            }
        }
        return null;
    }
}
