package com.demo.artio.qfj;

import java.util.Objects;

/**
 * Everything needed to build a QuickFIX/J initiator or acceptor. Validated on construction.
 *
 * @param role                 initiator (connect out) or acceptor (bind).
 * @param version              FIX 4.2 or 4.4.
 * @param host                 the host to connect to (initiator) or bind to (acceptor).
 * @param port                 the port to connect to or bind to.
 * @param senderCompId         this engine's CompID.
 * @param targetCompId         the counterparty's CompID.
 * @param heartbeatIntervalSec {@code HeartBtInt(108)}.
 * @param screenLog            true to print QuickFIX/J's own message log to stdout. Off by default:
 *                             the {@link RecordingApplication} already records everything, and two
 *                             copies of every message make test output unreadable.
 * @param reconnectIntervalSec how often an initiator retries a failed connection.
 * @param logonTimeoutSec      QuickFIX/J's {@code LogonTimeout}.
 * @param resetOnLogon         send {@code ResetSeqNumFlag(141)=Y} and restart from 1.
 */
public record QfjConfig(
    QfjRole role,
    QfjVersion version,
    String host,
    int port,
    String senderCompId,
    String targetCompId,
    int heartbeatIntervalSec,
    boolean screenLog,
    int reconnectIntervalSec,
    int logonTimeoutSec,
    boolean resetOnLogon)
{
    /** Validates every component. */
    public QfjConfig
    {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(version, "version");
        requireText(host, "host");
        if (port < 1 || port > 65535)
        {
            throw new IllegalArgumentException("port must be in 1..65535 but was " + port);
        }
        requireText(senderCompId, "senderCompId");
        requireText(targetCompId, "targetCompId");
        if (senderCompId.equals(targetCompId))
        {
            throw new IllegalArgumentException(
                "senderCompId and targetCompId must differ, both were '" + senderCompId + "'");
        }
        if (heartbeatIntervalSec < 1)
        {
            throw new IllegalArgumentException(
                "heartbeatIntervalSec must be at least 1 but was " + heartbeatIntervalSec);
        }
        if (reconnectIntervalSec < 1)
        {
            throw new IllegalArgumentException(
                "reconnectIntervalSec must be at least 1 but was " + reconnectIntervalSec);
        }
        if (logonTimeoutSec < 1)
        {
            throw new IllegalArgumentException(
                "logonTimeoutSec must be at least 1 but was " + logonTimeoutSec);
        }
    }

    private static void requireText(final String value, final String field)
    {
        if (value == null || value.isBlank())
        {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    /** @return a builder for an initiator, defaulting the host to localhost. */
    public static Builder initiator()
    {
        return new Builder(QfjRole.INITIATOR);
    }

    /** @return a builder for an acceptor, defaulting the bind address to localhost. */
    public static Builder acceptor()
    {
        return new Builder(QfjRole.ACCEPTOR);
    }

    /**
     * @param role initiator or acceptor.
     * @return a builder for that role.
     */
    public static Builder builder(final QfjRole role)
    {
        return new Builder(role);
    }

    /** Mutable builder for {@link QfjConfig}. */
    public static final class Builder
    {
        private final QfjRole role;
        private QfjVersion version = QfjVersion.FIX42;
        private String host = "localhost";
        private int port;
        private String senderCompId = "QFJ";
        private String targetCompId = "ARTIO";
        private int heartbeatIntervalSec = 10;
        private boolean screenLog;
        private int reconnectIntervalSec = 1;
        private int logonTimeoutSec = 10;
        private boolean resetOnLogon = true;

        private Builder(final QfjRole role)
        {
            this.role = Objects.requireNonNull(role, "role");
        }

        /** FIX version. Default {@link QfjVersion#FIX42}. */
        public Builder version(final QfjVersion version)
        {
            this.version = version;
            return this;
        }

        /** Connect host (initiator) or bind address (acceptor). Default {@code localhost}. */
        public Builder host(final String host)
        {
            this.host = host;
            return this;
        }

        /** Connect port or bind port. Required. */
        public Builder port(final int port)
        {
            this.port = port;
            return this;
        }

        /** Convenience for {@link #host(String)} plus {@link #port(int)}. */
        public Builder address(final String host, final int port)
        {
            return host(host).port(port);
        }

        /** This engine's CompID. Default {@code QFJ}. */
        public Builder senderCompId(final String senderCompId)
        {
            this.senderCompId = senderCompId;
            return this;
        }

        /** The counterparty's CompID. Default {@code ARTIO}. */
        public Builder targetCompId(final String targetCompId)
        {
            this.targetCompId = targetCompId;
            return this;
        }

        /** {@code HeartBtInt(108)} in seconds. Default 10. */
        public Builder heartbeatIntervalSec(final int heartbeatIntervalSec)
        {
            this.heartbeatIntervalSec = heartbeatIntervalSec;
            return this;
        }

        /** Print QuickFIX/J's own log to stdout. Default false. */
        public Builder screenLog(final boolean screenLog)
        {
            this.screenLog = screenLog;
            return this;
        }

        /** Initiator reconnect interval in seconds. Default 1. */
        public Builder reconnectIntervalSec(final int reconnectIntervalSec)
        {
            this.reconnectIntervalSec = reconnectIntervalSec;
            return this;
        }

        /** QuickFIX/J's {@code LogonTimeout} in seconds. Default 10. */
        public Builder logonTimeoutSec(final int logonTimeoutSec)
        {
            this.logonTimeoutSec = logonTimeoutSec;
            return this;
        }

        /** Send {@code ResetSeqNumFlag=Y} at logon. Default true. */
        public Builder resetOnLogon(final boolean resetOnLogon)
        {
            this.resetOnLogon = resetOnLogon;
            return this;
        }

        /**
         * @return the validated configuration.
         * @throws IllegalArgumentException if any component is missing or out of range.
         */
        public QfjConfig build()
        {
            return new QfjConfig(role, version, host, port, senderCompId, targetCompId,
                heartbeatIntervalSec, screenLog, reconnectIntervalSec, logonTimeoutSec, resetOnLogon);
        }
    }
}
