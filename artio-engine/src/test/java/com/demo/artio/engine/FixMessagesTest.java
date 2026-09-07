package com.demo.artio.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FixMessagesTest
{
    private static final String RAW =
        "8=FIX.4.2\0019=100\00135=D\00149=QFJ\00156=ARTIO\00134=7\00111=ORD-1\00155=MSFT\00110=123\001";

    @Test
    void printableAndFromPrintableAreInverses()
    {
        final String printable = FixMessages.printable(RAW);

        assertAll(
            () -> assertEquals(RAW.replace('\001', '|'), printable),
            () -> assertEquals(RAW, FixMessages.fromPrintable(printable)),
            () -> assertNull(FixMessages.printable(null)),
            () -> assertNull(FixMessages.fromPrintable(null)));
    }

    @Test
    void fieldReadsATagFromEitherTheWireFormOrTheLogForm()
    {
        assertAll(
            () -> assertEquals("ORD-1", FixMessages.field(RAW, 11)),
            () -> assertEquals("ORD-1", FixMessages.field(FixMessages.printable(RAW), 11)),
            () -> assertEquals("FIX.4.2", FixMessages.field(RAW, 8)),
            () -> assertEquals("123", FixMessages.field(RAW, 10)));
    }

    @Test
    void fieldReturnsNullForAnAbsentTagRatherThanThrowing()
    {
        assertAll(
            () -> assertNull(FixMessages.field(RAW, 44)),
            () -> assertNull(FixMessages.field(null, 11)));
    }

    @Test
    void fieldDoesNotMatchATagNumberThatOnlyAppearsInsideAnotherFieldsValue()
    {
        // "1=..." appears inside "11=ORD-1" and inside the value "ORD-1"; neither is a field start.
        final String message = "8=FIX.4.2\00111=ORD-1\0011=ACCOUNT\001";

        assertAll(
            () -> assertEquals("ACCOUNT", FixMessages.field(message, 1)),
            () -> assertEquals("ORD-1", FixMessages.field(message, 11)));
    }

    @Test
    void aFieldWithAnEmptyValueIsReportedAsEmptyNotAbsent()
    {
        assertEquals("", FixMessages.field("8=FIX.4.2\00158=\00110=000\001", 58));
    }
}
