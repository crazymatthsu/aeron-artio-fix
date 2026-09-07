package com.demo.artio.dictionary;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuickFixDictionaryReaderTest {

    private final QuickFixDictionaryReader reader = new QuickFixDictionaryReader();

    @Test
    void bundledFix42ParsesInto46MessagesAnd403FieldDeclarations() {
        final FixDictionary dictionary = Dictionaries.readBundled(Dictionaries.BUNDLED_FIX42);

        assertEquals(4, dictionary.major());
        assertEquals(2, dictionary.minor());
        assertEquals("FIX.4.2", dictionary.beginString());
        assertEquals(46, dictionary.messages().size());
        assertEquals(403, dictionary.fields().size());
        assertEquals(0, dictionary.components().size(), "QuickFIX/J's FIX 4.2 has no <components>");
    }

    @Test
    void bundledFix44ParsesInto92Messages24ComponentsAnd916FieldDeclarations() {
        final FixDictionary dictionary = Dictionaries.readBundled(Dictionaries.BUNDLED_FIX44);

        assertEquals("FIX.4.4", dictionary.beginString());
        assertEquals(92, dictionary.messages().size());
        assertEquals(24, dictionary.components().size());
        assertEquals(916, dictionary.fields().size());
    }

    @Test
    void bundledFix42DeclaresAllSevenSessionMessagesAsAdmin() {
        final FixDictionary dictionary = Dictionaries.readBundled(Dictionaries.BUNDLED_FIX42);

        for (final String msgType : List.of("0", "1", "2", "3", "4", "5", "A")) {
            final MessageDef message = dictionary.messageByMsgType(msgType)
                    .orElseThrow(() -> new AssertionError("no message for msgtype " + msgType));
            assertEquals("admin", message.msgCat(), message.name() + " should be an admin message");
        }
    }

    @Test
    void groupsAreReadAsNestedEntriesUnderTheirCounterField() {
        final FixDictionary dictionary = Dictionaries.readBundled(Dictionaries.BUNDLED_FIX42);
        final MessageDef logon = dictionary.messageByMsgType("A").orElseThrow();

        final GroupRef group = logon.entries().stream()
                .filter(GroupRef.class::isInstance)
                .map(GroupRef.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("FIX 4.2 Logon has a NoMsgTypes group"));

        assertEquals("NoMsgTypes", group.name());
        assertEquals(
                List.of("RefMsgType", "MsgDirection"),
                group.children().stream().map(DictionaryEntry::name).toList());
    }

    @Test
    void componentReferencesAreReadAsComponentEntries() {
        final FixDictionary dictionary = Dictionaries.readBundled(Dictionaries.BUNDLED_FIX44);
        final MessageDef newOrderSingle = dictionary.messageByMsgType("D").orElseThrow();

        assertTrue(
                newOrderSingle.entries().stream().anyMatch(ComponentRef.class::isInstance),
                "FIX 4.4 NewOrderSingle is built from components such as Instrument");
        assertTrue(dictionary.componentsByName().containsKey("Instrument"));
    }

    @Test
    void enumValuesAreReadWithRepresentationAndDescription() {
        final FixDictionary dictionary = Dictionaries.readBundled(Dictionaries.BUNDLED_FIX42);
        final FieldDef side = dictionary.fieldsByName().get("Side");

        assertNotNull(side);
        assertEquals(54, side.number());
        assertEquals("CHAR", side.type());
        assertTrue(side.isEnum());
        final Map<String, String> byRepresentation = side.values().stream()
                .collect(java.util.stream.Collectors.toMap(EnumValue::representation, EnumValue::description));
        assertEquals("BUY", byRepresentation.get("1"));
        assertEquals("SELL", byRepresentation.get("2"));
    }

    @Test
    void missingRequiredAttributeIsReadAsOptionalAsArtioDoes() {
        final FixDictionary dictionary = Dictionaries.read(
                Dictionaries.minimal("    <message name=\"News\" msgtype=\"B\" msgcat=\"app\">"
                        + "<field name=\"Text\"/></message>\n", "", ""));

        final MessageDef news = dictionary.messageByMsgType("B").orElseThrow();
        final FieldRef text = assertInstanceOf(FieldRef.class, news.entries().get(0));
        assertFalse(text.required());
    }

    @Test
    void aMissingMsgcatIsReadAsNullRatherThanRejected() {
        final FixDictionary dictionary = Dictionaries.read(
                Dictionaries.minimal("    <message name=\"News\" msgtype=\"B\">"
                        + "<field name=\"Text\" required=\"N\"/></message>\n", "", ""));

        assertNull(dictionary.messageByMsgType("B").orElseThrow().msgCat(),
                "the reader keeps the input faithful; the converter fills msgcat in");
    }

    @Test
    void nonFixRootElementIsRejectedWithTheElementNameInTheMessage() {
        final QuickFixDictionaryReader.DictionaryReadException e = assertThrows(
                QuickFixDictionaryReader.DictionaryReadException.class,
                () -> Dictionaries.read("<quickfix major=\"4\" minor=\"2\"/>"));

        assertTrue(e.getMessage().contains("<quickfix>"), e.getMessage());
    }

    @Test
    void malformedXmlIsRejectedWithTheParserReason() {
        assertThrows(
                QuickFixDictionaryReader.DictionaryReadException.class,
                () -> Dictionaries.read("<fix major=\"4\" minor=\"2\">"));
    }

    @Test
    void readerIsReusableAcrossDictionaries() throws Exception {
        try (var fix42 = Dictionaries.openBundled(Dictionaries.BUNDLED_FIX42);
             var fix44 = Dictionaries.openBundled(Dictionaries.BUNDLED_FIX44)) {
            assertEquals("FIX.4.2", reader.read(fix42).beginString());
            assertEquals("FIX.4.4", reader.read(fix44).beginString());
        }
    }
}
