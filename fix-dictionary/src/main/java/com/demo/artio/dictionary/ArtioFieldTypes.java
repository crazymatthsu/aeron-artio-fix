package com.demo.artio.dictionary;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The field type names {@code uk.co.real_logic.artio.dictionary.ir.Field.Type} accepts, and the
 * legacy QuickFIX/J spellings that map onto them.
 *
 * <p>Artio calls {@code Field.Type.lookup(type)}, which ends in {@code valueOf(name.trim())};
 * anything not in the enum throws
 * {@code IllegalArgumentException: No enum constant ...Field.Type.NUMBER} while the dictionary is
 * still being parsed, so the failure has no line number and no field name. Normalising the type
 * in the dictionary keeps the output inside the enum and turns an unknown type into a note.</p>
 */
public final class ArtioFieldTypes {

    /** Every constant of Artio 0.168's {@code Field.Type}, checked by {@code ArtioFieldTypesTest}. */
    private static final Set<String> CANONICAL = new LinkedHashSet<>(Arrays.asList(
            "INT", "LENGTH", "SEQNUM", "NUMINGROUP", "DAYOFMONTH", "LONG",
            "FLOAT", "PRICE", "PRICEOFFSET", "QTY", "QUANTITY", "PERCENTAGE", "AMT",
            "CHAR", "MULTIPLECHARVALUE", "STRING", "MULTIPLEVALUESTRING", "MULTIPLESTRINGVALUE",
            "TENOR", "CURRENCY", "EXCHANGE", "COUNTRY", "LANGUAGE",
            "DATA", "XMLDATA", "BOOLEAN",
            "UTCTIMESTAMP", "UTCTIMEONLY", "UTCDATEONLY", "LOCALMKTDATE", "MONTHYEAR",
            "TZTIMEONLY", "TZTIMESTAMP"));

    /**
     * The four spellings Artio only survives because {@code Field.Type.lookup} special-cases them.
     * FIX 4.2 as shipped by QuickFIX/J uses {@code UTCDATE} throughout; the rest turn up in
     * hand-edited venue dictionaries. Rewriting them means the output only uses names that are
     * actually in the enum.
     */
    private static final Map<String, String> ALIASES = Map.of(
            "UTCDATE", "UTCDATEONLY",
            "MONTH-YEAR", "MONTHYEAR",
            "STIRNG", "STRING",
            "RATE", "PRICE");

    /** The type an unknown QuickFIX/J type is mapped onto: every FIX value is ASCII text. */
    public static final String FALLBACK = "STRING";

    private ArtioFieldTypes() {
    }

    public static Set<String> canonicalTypes() {
        return Set.copyOf(CANONICAL);
    }

    public static boolean isCanonical(final String type) {
        return type != null && CANONICAL.contains(type.trim());
    }

    public static boolean isKnownAlias(final String type) {
        return type != null && ALIASES.containsKey(normalise(type));
    }

    /**
     * The canonical Artio name for {@code type}, or null when Artio has no constant for it.
     */
    public static String canonicalise(final String type) {
        if (type == null) {
            return null;
        }
        final String upper = normalise(type);
        if (CANONICAL.contains(upper)) {
            return upper;
        }
        return ALIASES.get(upper);
    }

    private static String normalise(final String type) {
        return type.trim().toUpperCase(Locale.ROOT);
    }

    /** True for the two types Artio's {@code Type.isDataBased()} reports, which need a length field. */
    public static boolean isDataBased(final String type) {
        return "DATA".equals(type) || "XMLDATA".equals(type);
    }

    /** True for the types Artio accepts as the length partner of a DATA field. */
    public static boolean isLengthLike(final String type) {
        return "LENGTH".equals(type) || "INT".equals(type);
    }
}
