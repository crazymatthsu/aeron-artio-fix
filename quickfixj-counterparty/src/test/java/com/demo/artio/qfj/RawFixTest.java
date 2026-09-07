package com.demo.artio.qfj;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawFixTest
{
    private static final String RAW =
        "8=FIX.4.4\0019=90\00135=8\00149=ARTIO\00156=QFJ\00134=3\00137=ORDER-1\00117=EXEC-1\00110=011\001";

    @Test
    void printableReplacesEverySohWithAPipe()
    {
        final String printable = RawFix.printable(RAW);

        assertAll(
            () -> assertEquals(RAW.replace('\001', '|'), printable),
            () -> assertFalse(printable.indexOf('\001') >= 0),
            () -> assertNull(RawFix.printable(null)));
    }

    @Test
    void fieldReadsATagFromEitherSeparatorForm()
    {
        assertAll(
            () -> assertEquals("ORDER-1", RawFix.field(RAW, 37)),
            () -> assertEquals("ORDER-1", RawFix.field(RawFix.printable(RAW), 37)),
            () -> assertEquals("FIX.4.4", RawFix.field(RAW, 8)),
            () -> assertEquals("8", RawFix.field(RAW, 35)));
    }

    @Test
    void fieldReturnsNullForAnAbsentTagOrANullMessage()
    {
        assertAll(
            () -> assertNull(RawFix.field(RAW, 44)),
            () -> assertNull(RawFix.field(null, 37)));
    }

    @Test
    void aTagNumberThatOccursInsideAnotherFieldIsNotMistakenForAFieldStart()
    {
        final String message = "8=FIX.4.2\00137=ORDER-7\0017=SEVEN\001";

        assertAll(
            () -> assertEquals("SEVEN", RawFix.field(message, 7)),
            () -> assertEquals("ORDER-7", RawFix.field(message, 37)));
    }

    @Test
    void theSevenSessionLevelTypesAreAdminAndApplicationTypesAreNot()
    {
        assertAll(
            () -> assertTrue(RawFix.isAdminMsgType("0")),
            () -> assertTrue(RawFix.isAdminMsgType("A")),
            () -> assertTrue(RawFix.isAdminMsgType("5")),
            () -> assertFalse(RawFix.isAdminMsgType("D")),
            () -> assertFalse(RawFix.isAdminMsgType("8")),
            () -> assertFalse(RawFix.isAdminMsgType("AE")),
            () -> assertFalse(RawFix.isAdminMsgType(null)));
    }
}
