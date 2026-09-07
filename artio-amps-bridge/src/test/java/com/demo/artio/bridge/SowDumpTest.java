package com.demo.artio.bridge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Command-line handling of {@link SowDump}, without an AMPS. Anything that needs a server is in
 * the module's integration suite.
 */
class SowDumpTest
{
    @Test
    void aTimeoutThatIsNotANumberIsAUsageErrorWithExitCodeTwoNotAStackTrace()
    {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();

        final int code = SowDump.run(
            new String[]{"--topic", "fix.orders", "--timeout-ms", "soon"},
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));

        final String errText = err.toString(StandardCharsets.UTF_8);
        assertAll(
            () -> assertEquals(SowDump.EXIT_USAGE, code),
            () -> assertTrue(errText.contains("--timeout-ms must be an integer"), errText),
            () -> assertTrue(errText.contains("usage: SowDump"), "usage is printed with the error"),
            () -> assertEquals("", out.toString(StandardCharsets.UTF_8), "nothing on stdout"));
    }

    @Test
    void anUnknownOptionAndAMissingTopicAreUsageErrors()
    {
        assertAll(
            () -> assertEquals(SowDump.EXIT_USAGE, run("--bogus")),
            () -> assertEquals(SowDump.EXIT_USAGE, run()),
            () -> assertEquals(SowDump.EXIT_USAGE, run("--topic")),
            () -> assertEquals(SowDump.EXIT_USAGE, run("--topic", "fix.orders", "--timeout-ms", "-1")));
    }

    @Test
    void helpPrintsUsageAndExitsZeroWithoutConnecting()
    {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final int code = SowDump.run(new String[]{"--help"},
            new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()));

        assertAll(
            () -> assertEquals(0, code),
            () -> assertTrue(out.toString(StandardCharsets.UTF_8).contains("usage: SowDump")));
    }

    @Test
    void aFilterIsKeptForAReplayRatherThanSilentlyIgnored()
    {
        final SowDump.Options options = SowDump.parse(
            new String[]{"--replay", "fix.raw", "--filter", "/35 = 'D'", "--timeout-ms", " 250 "});

        assertAll(
            () -> assertEquals("fix.raw", options.replayTopic()),
            () -> assertNull(options.topic()),
            () -> assertEquals("/35 = 'D'", options.filter()),
            () -> assertEquals(250, options.idleMs()));
    }

    @Test
    void theUriDefaultsToTheBridgeDefaultAndCanBeOverridden()
    {
        assertAll(
            () -> assertEquals(BridgeConfig.DEFAULT_URI, SowDump.parse(new String[]{"--topic", "t"}).uri()),
            () -> assertEquals("tcp://amps:9007/amps/fix",
                SowDump.parse(new String[]{"--topic", "t", "--uri", "tcp://amps:9007/amps/fix"}).uri()),
            () -> assertThrows(IllegalArgumentException.class, () -> SowDump.parse(new String[]{"--uri"})));
    }

    private static int run(final String... args)
    {
        final PrintStream sink = new PrintStream(new ByteArrayOutputStream());
        return SowDump.run(args, sink, sink);
    }
}
