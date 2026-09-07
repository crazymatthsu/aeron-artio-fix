package com.demo.artio.dictionary;

import org.junit.jupiter.api.Test;
import uk.co.real_logic.artio.dictionary.ir.Field;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtioFieldTypesTest {

    @Test
    void theTypeTableMatchesArtiosFieldTypeEnumExactly() {
        final Set<String> artio = Arrays.stream(Field.Type.values())
                .map(Enum::name)
                .collect(Collectors.toSet());

        assertEquals(artio, ArtioFieldTypes.canonicalTypes(),
                "the table drifted from uk.co.real_logic.artio.dictionary.ir.Field.Type");
    }

    @Test
    void everyCanonicalTypeSurvivesArtiosOwnLookup() {
        for (final String type : ArtioFieldTypes.canonicalTypes()) {
            assertEquals(type, Field.Type.lookup(type).name());
        }
    }

    @Test
    void theFourLegacySpellingsMapOntoTheSameTypeArtioPicks() {
        assertEquals(Field.Type.lookup("UTCDATE").name(), ArtioFieldTypes.canonicalise("UTCDATE"));
        assertEquals(Field.Type.lookup("MONTH-YEAR").name(), ArtioFieldTypes.canonicalise("MONTH-YEAR"));
        assertEquals(Field.Type.lookup("STIRNG").name(), ArtioFieldTypes.canonicalise("STIRNG"));
        assertEquals(Field.Type.lookup("RATE").name(), ArtioFieldTypes.canonicalise("RATE"));
    }

    @Test
    void aTypeArtioHasNoConstantForCanonicalisesToNull() {
        assertNull(ArtioFieldTypes.canonicalise("NUMBER"));
        assertTrue(ArtioFieldTypes.isKnownAlias("UTCDATE"));
    }

    @Test
    void dataAndXmldataAreTheTypesThatNeedALengthPartner() {
        for (final String type : ArtioFieldTypes.canonicalTypes()) {
            assertEquals(
                    Field.Type.valueOf(type).isDataBased(),
                    ArtioFieldTypes.isDataBased(type),
                    type + " disagrees with Field.Type.isDataBased()");
        }
    }
}
