package com.demo.artio.dictionary;

/**
 * One change the converter made, and the reason Artio needed it.
 *
 * @param rule the rule name, e.g. {@code field-type-alias}; the same names are used in
 *             {@code docs/02-quickfixj-to-artio-dictionary.md}
 * @param element what was changed, e.g. {@code field MaturityDate (541)}
 * @param before the value before the change, or {@code "(absent)"} when something was added
 * @param after the value after the change, or {@code "(removed)"} when something was dropped
 * @param why the Artio parser, generator or javac failure the change avoids
 */
public record ConversionNote(String rule, String element, String before, String after, String why) {

    public static final String ABSENT = "(absent)";
    public static final String REMOVED = "(removed)";

    public ConversionNote {
        if (rule == null || rule.isBlank()) {
            throw new IllegalArgumentException("a note needs a rule name");
        }
        if (why == null || why.isBlank()) {
            throw new IllegalArgumentException("a note needs a reason: " + rule);
        }
    }

    /** A note for something the converter added. */
    public static ConversionNote added(
            final String rule, final String element, final String after, final String why) {
        return new ConversionNote(rule, element, ABSENT, after, why);
    }

    /** A note for something the converter removed. */
    public static ConversionNote removed(
            final String rule, final String element, final String before, final String why) {
        return new ConversionNote(rule, element, before, REMOVED, why);
    }

    @Override
    public String toString() {
        return rule + ": " + element + ": " + before + " -> " + after + " (" + why + ")";
    }
}
