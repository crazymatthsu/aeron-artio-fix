package com.demo.artio.dictionary;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Command line entry point: {@code ConvertDictionary <in.xml> <out.xml>}.
 *
 * <p>Reads a QuickFIX/J dictionary, converts it, checks the result with Artio's own parser,
 * writes it out and prints every change it made.</p>
 *
 * <p>{@code ConvertDictionary --batch <in> <out> [<in> <out> ...]} converts several dictionaries
 * in one JVM; the {@code convertDictionaries} Gradle task uses that form.</p>
 */
public final class ConvertDictionary {

    private static final Logger LOG = LoggerFactory.getLogger(ConvertDictionary.class);

    private ConvertDictionary() {
    }

    public static void main(final String[] args) {
        final List<String> paths = arguments(args);
        if (paths.isEmpty() || paths.size() % 2 != 0) {
            System.err.println("Usage: ConvertDictionary <in.xml> <out.xml>");
            System.err.println("       ConvertDictionary --batch <in.xml> <out.xml> [<in.xml> <out.xml> ...]");
            System.exit(2);
            return;
        }
        try {
            for (int i = 0; i < paths.size(); i += 2) {
                convert(Path.of(paths.get(i)), Path.of(paths.get(i + 1)));
            }
        } catch (final IOException | RuntimeException e) {
            LOG.error("dictionary conversion failed", e);
            System.err.println("Conversion failed: " + e.getMessage());
            System.exit(1);
        }
    }

    private static List<String> arguments(final String[] args) {
        final List<String> all = List.of(args);
        return all.isEmpty() || !"--batch".equals(all.get(0)) ? all : all.subList(1, all.size());
    }

    /**
     * Converts one dictionary and writes it, returning the notes so callers can assert on them.
     *
     * @throws ArtioDictionaryCheck.DictionaryRejectedException if Artio will not parse the result
     */
    public static ConversionResult convert(final Path input, final Path output) throws IOException {
        final FixDictionary source = new QuickFixDictionaryReader().read(input);
        final ConversionResult result = new ArtioDictionaryConverter().convert(source);

        final String xml = new ArtioDictionaryWriter().toXml(result.dictionary());
        ArtioDictionaryCheck.check(xml, output.toString());
        new ArtioDictionaryWriter().write(result.dictionary(), output);

        print(input, output, result);
        return result;
    }

    private static void print(final Path input, final Path output, final ConversionResult result) {
        final FixDictionary dictionary = result.dictionary();
        System.out.printf("%s -> %s%n", input, output);
        System.out.printf("  %s  messages=%d components=%d fields=%d%n",
                dictionary.beginString(),
                dictionary.messages().size(),
                dictionary.components().size(),
                dictionary.fields().size());
        if (result.unchanged()) {
            System.out.println("  no changes: the input was already an Artio dictionary");
            return;
        }
        System.out.printf("  %d change(s):%n", result.notes().size());
        for (final Map.Entry<String, List<ConversionNote>> rule : result.notesByRule().entrySet()) {
            final List<ConversionNote> notes = rule.getValue();
            System.out.printf("  [%s] x%d -- %s%n", rule.getKey(), notes.size(), notes.get(0).why());
            final int shown = Math.min(notes.size(), 5);
            for (int i = 0; i < shown; i++) {
                final ConversionNote note = notes.get(i);
                System.out.printf("      %s: %s -> %s%n", note.element(), note.before(), note.after());
            }
            if (notes.size() > shown) {
                System.out.printf("      ... and %d more%n", notes.size() - shown);
            }
        }
    }
}
