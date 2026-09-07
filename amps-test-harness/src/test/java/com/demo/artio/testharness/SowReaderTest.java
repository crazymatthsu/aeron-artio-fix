package com.demo.artio.testharness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link SowReader#printable} only. Everything else in that class needs a
 * server and lives in the integration suite.
 */
class SowReaderTest {

    private static final char SOH = '\u0001';

    @Test
    void printableReplacesEverySohWithAPipe() {
        String fix = "8=FIX.4.2" + SOH + "35=D" + SOH + "11=ORDER-1" + SOH + "10=123" + SOH;

        assertEquals("8=FIX.4.2|35=D|11=ORDER-1|10=123|", SowReader.printable(fix));
    }

    @Test
    void printableLeavesAMessageWithNoSohUnchanged() {
        assertEquals("nothing to replace", SowReader.printable("nothing to replace"));
    }

    @Test
    void printableIsNullSafeSoItCanBeUsedInAFailureMessage() {
        // Assertion messages are built before anyone knows whether the value is
        // there; throwing here would hide the failure being reported.
        assertNull(SowReader.printable(null));
    }

    @Test
    void printableDoesNotAlterTheOriginalWhichIsWhatAssertionsCompare() {
        String fix = "8=FIX.4.2" + SOH + "35=D" + SOH;

        SowReader.printable(fix);

        assertTrue(fix.indexOf(SOH) >= 0,
                "printable() must not be used to normalise the payload: comparisons are "
                        + "against the raw bytes, separators included");
    }

    @Test
    void sohConstantIsTheFixFieldSeparator() {
        assertEquals(1, SowReader.SOH);
    }
}
