package com.demo.artio.boot;

import com.demo.artio.engine.EngineMode;
import com.demo.artio.engine.FixEngineConfig;
import com.demo.artio.engine.FixVersion;
import com.demo.artio.engine.IdleStrategyType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

/**
 * The Artio half of the configuration, bound from {@code artio.*}.
 *
 * <p>The key names are deliberately the ones
 * {@link com.demo.artio.bridge.BridgeMain#engineConfig(java.util.Properties) BridgeMain} reads from
 * {@code bridge.properties} - {@code artio.senderCompId}, {@code artio.fixVersion} and the rest -
 * so one file configures either entry point. Spring's relaxed binding maps
 * {@code artio.senderCompId}, {@code artio.sender-comp-id} and {@code ARTIO_SENDERCOMPID} onto the
 * same component; the canonical spelling in YAML is kebab-case.
 *
 * <p>Validation happens at bind time, so a bad port fails the context refresh with the offending
 * key named, not later inside Artio.
 *
 * @param name                     short label for thread names, directories and logs.
 * @param enabled                  false leaves the {@link com.demo.artio.engine.ArtioRuntime} bean
 *                                 out of the context entirely, which is how the unit tests load
 *                                 the application without a media driver.
 * @param mode                     {@code acceptor} (bind and wait) or {@code initiator}
 *                                 (connect out).
 * @param host                     bind address for an acceptor, remote address for an initiator.
 * @param port                     bind port, or remote port.
 * @param senderCompId             this engine's {@code SenderCompID(49)}.
 * @param targetCompId             the counterparty's {@code TargetCompID(56)}.
 * @param fixVersion               {@code FIX.4.2} or {@code FIX.4.4}; also accepted as
 *                                 {@code FIX42} / {@code FIX44}.
 * @param heartbeatIntervalSec     {@code HeartBtInt(108)} proposed at logon.
 * @param baseDirectory            parent of the derived Aeron, archive and Artio log directories;
 *                                 blank means {@code java.io.tmpdir}.
 * @param resetSeqNumsOnLogon      send {@code ResetSeqNumFlag(141)=Y} (initiator).
 * @param idleStrategy             how the library poll thread idles.
 * @param logonTimeoutMs           how long start-up waits for an initiator's session to go active.
 * @param replyTimeoutMs           Artio's engine/library reply timeout.
 * @param shutdownTimeoutMs        how long {@code close()} waits for a graceful FIX logout.
 * @param deleteDirectoriesOnClose delete the Aeron and Artio directories on shutdown.
 * @param logMessages              compose a {@link com.demo.artio.engine.LoggingSink} ahead of the
 *                                 publisher, so received messages appear on the console as
 *                                 printable FIX. A demo affordance: it builds a String per
 *                                 message. Turn it off and the path allocates nothing.
 */
@Validated
@ConfigurationProperties(prefix = "artio")
public record ArtioProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("acceptor") @NotNull EngineMode mode,
    @DefaultValue("bridge") @NotBlank String name,
    @DefaultValue("0.0.0.0") @NotBlank String host,
    @DefaultValue("9880") @Min(1) @Max(65535) int port,
    @DefaultValue("ARTIO") @NotBlank String senderCompId,
    @DefaultValue("QFJ") @NotBlank String targetCompId,
    @DefaultValue("FIX.4.2")
    @Pattern(regexp = "(?i)(FIX\\.4\\.[24]|FIX4[24])", message = "must be FIX.4.2 or FIX.4.4")
    String fixVersion,
    @DefaultValue("30") @Min(1) int heartbeatIntervalSec,
    @DefaultValue("") String baseDirectory,
    @DefaultValue("true") boolean resetSeqNumsOnLogon,
    @DefaultValue("BACKOFF") @NotNull IdleStrategyType idleStrategy,
    @DefaultValue("20000") @Positive long logonTimeoutMs,
    @DefaultValue("10000") @Positive long replyTimeoutMs,
    @DefaultValue("5000") @Positive long shutdownTimeoutMs,
    @DefaultValue("true") boolean deleteDirectoriesOnClose,
    @DefaultValue("true") boolean logMessages)
{
    /**
     * Translates the bound properties into the engine's own configuration record, which validates
     * again - the pair of CompIDs must differ, a CompID may not contain SOH, and so on. Anything
     * jakarta constraints cannot express is caught there.
     *
     * @return the engine configuration.
     * @throws IllegalArgumentException if the combination is invalid.
     */
    public FixEngineConfig toFixEngineConfig()
    {
        return FixEngineConfig.builder(mode)
            .name(name.trim())
            .address(host.trim(), port)
            .senderCompId(senderCompId.trim())
            .targetCompId(targetCompId.trim())
            .fixVersion(version())
            .heartbeatIntervalSec(heartbeatIntervalSec)
            .baseDirectory(resolvedBaseDirectory())
            .resetSeqNumsOnLogon(resetSeqNumsOnLogon)
            .idleStrategy(idleStrategy)
            .logonTimeoutMs(logonTimeoutMs)
            .replyTimeoutMs(replyTimeoutMs)
            .shutdownTimeoutMs(shutdownTimeoutMs)
            .deleteDirectoriesOnClose(deleteDirectoriesOnClose)
            .build();
    }

    /**
     * @return the FIX version, parsed from either spelling.
     * @throws IllegalArgumentException if it is neither 4.2 nor 4.4.
     */
    public FixVersion version()
    {
        return FixVersion.ofBeginString(fixVersion.trim());
    }

    /**
     * @return where the per-runtime Aeron, archive and log directories are created; the system
     * temporary directory when {@code artio.base-directory} is blank.
     */
    public Path resolvedBaseDirectory()
    {
        return baseDirectory == null || baseDirectory.isBlank() ?
            Path.of(System.getProperty("java.io.tmpdir")) : Path.of(baseDirectory.trim());
    }
}
