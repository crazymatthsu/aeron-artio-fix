package com.demo.artio.engine;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link ArtioRuntime} decides in its constructor, before anything is started: the runtime id
 * and the directory policy. No engine, no ports.
 */
class ArtioRuntimeTest
{
    private static final Pattern RUNTIME_ID = Pattern.compile("^(.+)-(acceptor|initiator)-(\\d+)-(\\d+)$");

    private static FixEngineConfig.Builder config(final String name)
    {
        return FixEngineConfig.acceptor()
            .name(name)
            .port(9880)
            .baseDirectory(Path.of("/tmp/base"));
    }

    @Test
    void runtimeIdIsNameModePidAndAPerJvmCounter()
    {
        final ArtioRuntime first = new ArtioRuntime(config("gw").build(), FixMessageSink.NO_OP);
        final ArtioRuntime second = new ArtioRuntime(config("gw").build(), FixMessageSink.NO_OP);

        final Matcher id = RUNTIME_ID.matcher(first.runtimeId());
        assertTrue(id.matches(), first.runtimeId());
        final Matcher other = RUNTIME_ID.matcher(second.runtimeId());
        assertTrue(other.matches(), second.runtimeId());
        assertAll(
            () -> assertEquals("gw", id.group(1)),
            () -> assertEquals("acceptor", id.group(2)),
            // The pid is what keeps two processes with the same name out of each other's Aeron
            // directory; a per-JVM counter alone repeats in every process.
            () -> assertEquals(String.valueOf(ProcessHandle.current().pid()), id.group(3)),
            () -> assertEquals(id.group(3), other.group(3)),
            () -> assertNotEquals(id.group(4), other.group(4), "the counter separates runtimes in one JVM"),
            () -> assertEquals(Path.of("/tmp/base", "artio-" + first.runtimeId()), first.directory()));
    }

    @Test
    void derivedDirectoriesLiveUnderTheRuntimeDirectoryAndAreWipedOnStart()
    {
        final ArtioRuntime runtime = new ArtioRuntime(config("derived").build(), FixMessageSink.NO_OP);

        assertAll(
            () -> assertEquals(runtime.directory().resolve("aeron"), runtime.aeronDirectory()),
            () -> assertEquals(runtime.directory().resolve("logs"), runtime.logFileDir()),
            () -> assertTrue(runtime.deletesAeronDirectoryOnStart()),
            () -> assertTrue(runtime.deletesLogFileDirOnStart()));
    }

    @Test
    void explicitDirectoriesAreUsedAsGivenAndNeverWipedOnStart()
    {
        final ArtioRuntime runtime = new ArtioRuntime(config("explicit")
            .aeronDirectory(Path.of("/tmp/shared-aeron"))
            .logFileDir(Path.of("/tmp/shared-logs"))
            .build(), FixMessageSink.NO_OP);

        assertAll(
            () -> assertEquals(Path.of("/tmp/shared-aeron"), runtime.aeronDirectory()),
            () -> assertEquals(Path.of("/tmp/shared-logs"), runtime.logFileDir()),
            // Something outside the process may be using an explicit directory; wiping it on
            // start is how two engines end up destroying each other's live media driver.
            () -> assertFalse(runtime.deletesAeronDirectoryOnStart()),
            () -> assertFalse(runtime.deletesLogFileDirOnStart()));
    }

    @Test
    void eachExplicitDirectoryIsDecidedOnItsOwn()
    {
        final ArtioRuntime aeronOnly = new ArtioRuntime(config("aeron")
            .aeronDirectory(Path.of("/tmp/only-aeron")).build(), FixMessageSink.NO_OP);
        final ArtioRuntime logsOnly = new ArtioRuntime(config("logs")
            .logFileDir(Path.of("/tmp/only-logs")).build(), FixMessageSink.NO_OP);

        assertAll(
            () -> assertFalse(aeronOnly.deletesAeronDirectoryOnStart()),
            () -> assertTrue(aeronOnly.deletesLogFileDirOnStart()),
            () -> assertTrue(logsOnly.deletesAeronDirectoryOnStart()),
            () -> assertFalse(logsOnly.deletesLogFileDirOnStart()));
    }
}
