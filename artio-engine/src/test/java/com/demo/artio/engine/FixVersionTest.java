package com.demo.artio.engine;

import org.junit.jupiter.api.Test;
import uk.co.real_logic.artio.dictionary.FixDictionary;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixVersionTest
{
    @Test
    void eachVersionIsBoundToTheGeneratedDictionaryWhoseBeginStringMatchesIt()
    {
        assertAll(
            () -> assertEquals("FIX.4.2", FixVersion.FIX42.beginString()),
            () -> assertEquals("FIX.4.4", FixVersion.FIX44.beginString()),
            () -> assertEquals("FIX.4.2",
                FixDictionary.of(FixVersion.FIX42.dictionary()).beginString()),
            () -> assertEquals("FIX.4.4",
                FixDictionary.of(FixVersion.FIX44.dictionary()).beginString()));
    }

    @Test
    void bothDictionariesImplementArtiosFixDictionaryInterface()
    {
        assertAll(
            () -> assertTrue(FixDictionary.class.isAssignableFrom(FixVersion.FIX42.dictionary())),
            () -> assertTrue(FixDictionary.class.isAssignableFrom(FixVersion.FIX44.dictionary())));
    }

    @Test
    void aVersionCanBeNamedByBeginStringOrByEnumNameSoTheCommandLineAcceptsBoth()
    {
        assertAll(
            () -> assertEquals(FixVersion.FIX42, FixVersion.ofBeginString("FIX.4.2")),
            () -> assertEquals(FixVersion.FIX42, FixVersion.ofBeginString("fix42")),
            () -> assertEquals(FixVersion.FIX44, FixVersion.ofBeginString("FIX44")),
            () -> assertEquals(FixVersion.FIX44, FixVersion.ofBeginString("fix.4.4")));
    }

    @Test
    void anUnsupportedVersionIsRejectedWithTheSupportedOnesNamed()
    {
        final String message = assertThrows(IllegalArgumentException.class,
            () -> FixVersion.ofBeginString("FIX.5.0")).getMessage();

        assertAll(
            () -> assertTrue(message.contains("FIX.5.0"), message),
            () -> assertTrue(message.contains("FIX.4.2"), message),
            () -> assertTrue(message.contains("FIX.4.4"), message));
    }
}
