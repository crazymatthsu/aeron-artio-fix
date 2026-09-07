package com.demo.artio.dictionary;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConvertDictionaryCliTest {

    @Test
    void convertingWritesAnArtioDictionaryArtioCanParse(@TempDir final Path dir) throws IOException {
        final Path input = copyBundled(Dictionaries.BUNDLED_FIX42, dir.resolve("FIX42.xml"));
        final Path output = dir.resolve("out").resolve("FIX42.artio.xml");

        final ConversionResult result = ConvertDictionary.convert(input, output);

        assertTrue(Files.exists(output), "the CLI must write the output file");
        assertTrue(Files.size(output) > 10_000, "the output should be a whole dictionary");
        assertEquals(46, result.dictionary().messages().size());
        ArtioDictionaryCheck.check(output);
    }

    @Test
    void convertingTwiceProducesByteIdenticalOutput(@TempDir final Path dir) throws IOException {
        final Path input = copyBundled(Dictionaries.BUNDLED_FIX44, dir.resolve("FIX44.xml"));
        final Path first = dir.resolve("first.xml");
        final Path second = dir.resolve("second.xml");

        ConvertDictionary.convert(input, first);
        final ConversionResult again = ConvertDictionary.convert(first, second);

        assertTrue(again.unchanged(), () -> "second pass made changes: " + again.notes());
        assertEquals(Files.readString(first), Files.readString(second));
    }

    @Test
    void theBatchFormConvertsSeveralDictionariesInOneRun(@TempDir final Path dir) throws IOException {
        final Path fix42 = copyBundled(Dictionaries.BUNDLED_FIX42, dir.resolve("FIX42.xml"));
        final Path fix44 = copyBundled(Dictionaries.BUNDLED_FIX44, dir.resolve("FIX44.xml"));
        final Path out42 = dir.resolve("out/FIX42.xml");
        final Path out44 = dir.resolve("out/FIX44.xml");

        final int exitCode = ConvertDictionary.run(new String[] {
                "--batch",
                fix42.toString(), out42.toString(),
                fix44.toString(), out44.toString()});

        assertEquals(0, exitCode);
        assertTrue(Files.exists(out42));
        assertTrue(Files.exists(out44));
        assertEquals("FIX.4.2", new QuickFixDictionaryReader().read(out42).beginString());
        assertEquals("FIX.4.4", new QuickFixDictionaryReader().read(out44).beginString());
    }

    @Test
    void anythingButInputOutputPairsIsAUsageErrorWithExitCode2() {
        assertEquals(ConvertDictionary.EXIT_BAD_ARGUMENTS, ConvertDictionary.run(new String[0]));
        assertEquals(ConvertDictionary.EXIT_BAD_ARGUMENTS, ConvertDictionary.run(new String[] {"only-in.xml"}));
        assertEquals(ConvertDictionary.EXIT_BAD_ARGUMENTS, ConvertDictionary.run(new String[] {"--batch"}));
        assertEquals(ConvertDictionary.EXIT_BAD_ARGUMENTS,
                ConvertDictionary.run(new String[] {"--batch", "a.xml", "b.xml", "c.xml"}));
    }

    @Test
    void aConversionThatFailsGivesExitCode1AndDoesNotWriteTheOutput(@TempDir final Path dir) throws IOException {
        final Path missing = dir.resolve("missing.xml");
        final Path output = dir.resolve("out.xml");

        assertEquals(ConvertDictionary.EXIT_FAILED,
                ConvertDictionary.run(new String[] {missing.toString(), output.toString()}));
        assertFalse(Files.exists(output));

        final Path malformed = dir.resolve("malformed.xml");
        Files.writeString(malformed, "<fix major=\"4\" minor=\"2\">");
        assertEquals(ConvertDictionary.EXIT_FAILED,
                ConvertDictionary.run(new String[] {malformed.toString(), output.toString()}),
                "an unreadable dictionary is a failure, not a usage error");
        assertFalse(Files.exists(output));
    }

    private static Path copyBundled(final String resource, final Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (InputStream in = Dictionaries.openBundled(resource)) {
            Files.copy(in, target);
        }
        return target;
    }
}
