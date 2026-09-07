package com.demo.artio.dictionary;

import uk.co.real_logic.artio.dictionary.DictionaryParser;
import uk.co.real_logic.artio.dictionary.ir.Dictionary;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs Artio's own {@code DictionaryParser} over a converted dictionary.
 *
 * <p>The point is failing here, with Artio's reason, instead of half way through code generation
 * or -- worse -- at compile time in {@code :fix-codecs}, where the message is a javac error about
 * an abstract method rather than anything about the dictionary.</p>
 *
 * <p>Note that this only covers what the <em>parser</em> validates. The session contract in
 * {@link SessionContract} is enforced by javac on the generated code, not here; that is why
 * {@code :fix-codecs} compiling is the real end-to-end check.</p>
 */
public final class ArtioDictionaryCheck {

    private ArtioDictionaryCheck() {
    }

    /** Parses {@code file} with Artio and returns Artio's own dictionary model. */
    public static Dictionary check(final Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return check(in, file.toString());
        }
    }

    /** Parses {@code xml} with Artio and returns Artio's own dictionary model. */
    public static Dictionary check(final String xml, final String description) {
        return check(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), description);
    }

    /** Parses {@code in} with Artio and returns Artio's own dictionary model. */
    public static Dictionary check(final InputStream in, final String description) {
        try {
            // false: do not allow duplicate fields. A dictionary that needs the escape hatch is a
            // dictionary the converter should have repaired.
            return new DictionaryParser(false).parse(in, null);
        } catch (final Exception e) {
            throw new DictionaryRejectedException(
                    "Artio's DictionaryParser rejected " + description + ": "
                            + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    /** Thrown when Artio will not accept a dictionary, carrying Artio's own reason. */
    public static final class DictionaryRejectedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public DictionaryRejectedException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
