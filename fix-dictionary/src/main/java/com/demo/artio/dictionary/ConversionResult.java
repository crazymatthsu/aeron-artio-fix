package com.demo.artio.dictionary;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The converted dictionary and the list of changes that produced it.
 *
 * <p>An empty note list means the input was already an Artio dictionary; that is what makes
 * the conversion idempotent and what the idempotency test asserts.</p>
 */
public record ConversionResult(FixDictionary dictionary, List<ConversionNote> notes) {

    public ConversionResult {
        notes = List.copyOf(notes);
    }

    public boolean unchanged() {
        return notes.isEmpty();
    }

    /** Notes grouped by rule name, for the CLI summary and the documentation. */
    public Map<String, List<ConversionNote>> notesByRule() {
        final Map<String, List<ConversionNote>> byRule = new TreeMap<>();
        for (final ConversionNote note : notes) {
            byRule.computeIfAbsent(note.rule(), k -> new java.util.ArrayList<>()).add(note);
        }
        return byRule;
    }
}
