package com.demo.artio.engine;

import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import uk.co.real_logic.artio.util.MessageTypeEncoding;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SinkTest
{
    private static final SessionKey SESSION = new SessionKey(1L, "ARTIO", "QFJ", "FIX.4.2", true);

    private final FixMessageView view = new FixMessageView();

    private FixMessageView message(final String msgType, final String rawFix, final int sequenceNumber)
    {
        final byte[] bytes = rawFix.getBytes(StandardCharsets.US_ASCII);
        return view.wrap(new UnsafeBuffer(bytes), 0, bytes.length,
            MessageTypeEncoding.packMessageType(msgType), sequenceNumber, 0, 0L, 1, true, SESSION);
    }

    @Test
    void countingSinkSeparatesApplicationMessagesFromSessionLevelOnes()
    {
        final CountingSink sink = new CountingSink();

        sink.onMessage(message("A", "8=FIX.4.2\00135=A\001", 1));
        sink.onMessage(message("D", "8=FIX.4.2\00135=D\00111=ORD-1\001", 2));
        sink.onMessage(message("D", "8=FIX.4.2\00135=D\00111=ORD-2\001", 3));
        sink.onMessage(message("0", "8=FIX.4.2\00135=0\001", 4));

        assertAll(
            () -> assertEquals(4, sink.count()),
            () -> assertEquals(2, sink.applicationCount()),
            () -> assertEquals(2, sink.adminCount()),
            () -> assertEquals(0, sink.droppedCount()),
            () -> assertEquals(2, sink.countOfType("D")));
    }

    @Test
    void countingSinkCopiesTheBytesSoAMessageSurvivesTheCallback()
    {
        final CountingSink sink = new CountingSink();
        sink.onMessage(message("D", "8=FIX.4.2\00135=D\00111=ORD-1\00155=MSFT\001", 9));
        view.unwrap();

        final CountingSink.Captured captured = sink.messages().get(0);

        assertAll(
            () -> assertEquals("D", captured.msgType()),
            () -> assertEquals(9, captured.sequenceNumber()),
            () -> assertEquals("ORD-1", captured.field(11)),
            () -> assertEquals("MSFT", captured.field(55)),
            () -> assertEquals(1L, captured.sessionId()),
            () -> assertEquals(false, captured.admin()),
            () -> assertTrue(captured.printable().contains("|35=D|"), captured.printable()));
    }

    @Test
    void countingSinkCountsButDoesNotKeepMessagesPastItsCapacity()
    {
        final CountingSink sink = new CountingSink(2);

        for (int i = 1; i <= 5; i++)
        {
            sink.onMessage(message("D", "8=FIX.4.2\00135=D\00111=ORD-" + i + "\001", i));
        }

        assertAll(
            () -> assertEquals(5, sink.count()),
            () -> assertEquals(3, sink.droppedCount()),
            () -> assertEquals(2, sink.messages().size()),
            () -> assertEquals("ORD-1", sink.messages().get(0).field(11)));
    }

    @Test
    void countingSinkResetForgetsCountsAndMessages()
    {
        final CountingSink sink = new CountingSink();
        sink.onMessage(message("D", "8=FIX.4.2\00135=D\001", 1));
        sink.reset();

        assertAll(
            () -> assertEquals(0, sink.count()),
            () -> assertEquals(0, sink.messages().size()));
    }

    @Test
    void aNegativeCapacityIsRejected()
    {
        assertEquals("capacity must not be negative but was -1",
            assertThrows(IllegalArgumentException.class, () -> new CountingSink(-1)).getMessage());
    }

    @Test
    void compositeSinkCallsEveryDelegateInOrderWithTheSameView()
    {
        final List<String> calls = new ArrayList<>();
        final List<FixMessageView> seen = new ArrayList<>();
        final FixMessageSink first = m ->
        {
            calls.add("first");
            seen.add(m);
        };
        final FixMessageSink second = m -> calls.add("second");

        new CompositeSink(first, second).onMessage(message("D", "8=FIX.4.2\00135=D\001", 1));

        assertAll(
            () -> assertEquals(List.of("first", "second"), calls),
            () -> assertSame(view, seen.get(0)));
    }

    @Test
    void compositeSinkOfCollapsesZeroAndOneDelegateCases()
    {
        final FixMessageSink only = m ->
        {
        };

        assertAll(
            () -> assertSame(FixMessageSink.NO_OP, CompositeSink.of()),
            () -> assertSame(only, CompositeSink.of(only)),
            () -> assertTrue(CompositeSink.of(only, only) instanceof CompositeSink));
    }

    @Test
    void compositeSinkRejectsANullDelegateAtConstructionRatherThanOnTheHotPath()
    {
        assertThrows(NullPointerException.class, () -> new CompositeSink(m ->
        {
        }, null));
    }

    @Test
    void compositeSinkTakesACopyOfItsDelegatesSoTheCallerCannotChangeThemLater()
    {
        final List<String> calls = new ArrayList<>();
        final FixMessageSink[] delegates = {m -> calls.add("original")};
        final CompositeSink sink = new CompositeSink(delegates);
        delegates[0] = m -> calls.add("swapped");

        sink.onMessage(message("D", "8=FIX.4.2\00135=D\001", 1));

        assertEquals(List.of("original"), calls);
    }

    @Test
    void loggingSinkSkipsSessionLevelMessagesWhenAskedTo()
    {
        final List<String> withAdmin = new ArrayList<>();
        final List<String> withoutAdmin = new ArrayList<>();
        final LoggingSink adminIncluded = new LoggingSink("test ", true, withAdmin::add);
        final LoggingSink adminExcluded = new LoggingSink("test ", false, withoutAdmin::add);

        for (final LoggingSink sink : List.of(adminIncluded, adminExcluded))
        {
            sink.onMessage(message("A", "8=FIX.4.2\00135=A\001", 1));
            sink.onMessage(message("D", "8=FIX.4.2\00135=D\00111=ORD-1\001", 2));
        }

        assertAll(
            () -> assertEquals(
                List.of("test <- [admin A seq=1] 8=FIX.4.2|35=A|",
                    "test <- [app D seq=2] 8=FIX.4.2|35=D|11=ORD-1|"),
                withAdmin),
            () -> assertEquals(List.of("test <- [app D seq=2] 8=FIX.4.2|35=D|11=ORD-1|"), withoutAdmin),
            () -> assertTrue(adminIncluded.includesAdminMessages()),
            () -> assertFalse(adminExcluded.includesAdminMessages()));
    }

    @Test
    void theDefaultLoggingSinkLogsApplicationMessagesOnlyAndPrefixesNothing()
    {
        final LoggingSink defaults = new LoggingSink();
        // Same arguments the no-arg constructor uses, but with the lines captured rather than logged.
        final List<String> lines = new ArrayList<>();
        final LoggingSink capturing =
            new LoggingSink(defaults.prefix(), defaults.includesAdminMessages(), lines::add);

        capturing.onMessage(message("A", "8=FIX.4.2\00135=A\001", 1));
        capturing.onMessage(message("0", "8=FIX.4.2\00135=0\001", 2));
        capturing.onMessage(message("D", "8=FIX.4.2\00135=D\00111=ORD-1\001", 3));

        assertAll(
            () -> assertFalse(defaults.includesAdminMessages(), "the default sink is application messages only"),
            () -> assertEquals("", defaults.prefix()),
            () -> assertEquals("LoggingSink{prefix='', includeAdmin=false}", defaults.toString()),
            () -> assertEquals(List.of("<- [app D seq=3] 8=FIX.4.2|35=D|11=ORD-1|"), lines,
                "the Logon and the Heartbeat were skipped"));
    }

    @Test
    void theNoOpSinkAcceptsAnythingAndDoesNothing()
    {
        final FixMessageView delivered = message("D", "8=FIX.4.2\00135=D\001", 1);

        FixMessageSink.NO_OP.onMessage(delivered);

        assertAll(
            // "Does nothing" is the contract: the view it was handed comes back untouched, so the
            // next sink in a CompositeSink sees the same message.
            () -> assertEquals("D", delivered.msgTypeAsString()),
            () -> assertEquals(1, delivered.sequenceNumber()),
            () -> assertEquals("8=FIX.4.2\00135=D\001", delivered.toFixString()));
    }
}
