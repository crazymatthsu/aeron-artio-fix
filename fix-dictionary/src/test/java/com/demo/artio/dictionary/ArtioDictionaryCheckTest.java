package com.demo.artio.dictionary;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.co.real_logic.artio.dictionary.ir.Dictionary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs Artio's own {@code DictionaryParser} over the converter's output, and over dictionaries
 * that break one rule each, so every rule in {@link ArtioDictionaryConverter} is backed by an
 * observed Artio failure rather than by a guess.
 *
 * <p>Rules whose failure surfaces at <em>compile</em> time -- the session field contract, the enum
 * name collisions -- cannot be shown here, because Artio's parser is happy with those dictionaries;
 * {@code :fix-codecs:compileJava} is their check. Those cases are marked below.</p>
 */
class ArtioDictionaryCheckTest {

    private final ArtioDictionaryConverter converter = new ArtioDictionaryConverter();
    private final ArtioDictionaryWriter writer = new ArtioDictionaryWriter();

    @ParameterizedTest
    @ValueSource(strings = {Dictionaries.BUNDLED_FIX42, Dictionaries.BUNDLED_FIX44})
    void artioParsesTheConvertedBundledDictionaries(final String resource) {
        final FixDictionary source = Dictionaries.readBundled(resource);
        final String xml = writer.toXml(converter.convert(source).dictionary());

        final Dictionary parsed = ArtioDictionaryCheck.check(xml, resource);

        assertEquals(source.messages().size(), parsed.messages().size());
        assertEquals(source.major(), parsed.majorVersion());
        assertEquals(source.minor(), parsed.minorVersion());
        assertEquals("FIX", parsed.specType(), "Artio defaults a missing type attribute to FIX");
    }

    @ParameterizedTest
    @ValueSource(strings = {Dictionaries.BUNDLED_FIX42, Dictionaries.BUNDLED_FIX44})
    void theConvertedDictionaryStillDeclaresEverySessionMessageArtioNeeds(final String resource) {
        final String xml = writer.toXml(
                converter.convert(Dictionaries.readBundled(resource)).dictionary());

        final Dictionary parsed = ArtioDictionaryCheck.check(xml, resource);

        for (final String msgType : SessionContract.ADMIN_MSG_TYPES) {
            assertTrue(
                    parsed.messages().stream().anyMatch(m -> m.fullType().equals(msgType)),
                    "Artio's model has no message for msgtype " + msgType);
        }
    }

    @Test
    void artioRejectsAMessageWithoutMsgcatButAcceptsTheConversion() {
        final String broken = Dictionaries.minimal(
                "    <message name=\"News\" msgtype=\"B\"><field name=\"Text\" required=\"N\"/></message>\n",
                "", "");

        assertRejectedThenAccepted(broken, "Empty item for: msgcat");
    }

    @Test
    void artioRejectsAnUnknownFieldTypeButAcceptsTheConversion() {
        final String broken = Dictionaries.minimalWithField(
                "    <field number=\"9001\" name=\"Weird\" type=\"NUMBER\"/>\n");

        assertRejectedThenAccepted(broken, "No enum constant");
    }

    @Test
    void artioRejectsAValueWithoutADescriptionButAcceptsTheConversion() {
        final String broken = Dictionaries.minimalWithField("""
                    <field number="9002" name="Flavour" type="CHAR">
                      <value enum="1"/>
                    </field>
                """);

        assertRejectedThenAccepted(broken, "Empty item for: description");
    }

    @Test
    void artioRejectsAnEmptyDescriptionButAcceptsTheConversion() {
        final String broken = Dictionaries.minimalWithField("""
                    <field number="9003" name="Flavour" type="CHAR">
                      <value enum="1" description=""/>
                    </field>
                """);

        assertRejectedThenAccepted(broken, "StringIndexOutOfBounds");
    }

    @Test
    void artioRejectsADataFieldWithNoLengthPartnerButAcceptsTheConversion() {
        final String broken = Dictionaries.minimal("""
                    <message name="News" msgtype="B" msgcat="app">
                      <field name="RawData" required="N"/>
                    </message>
                """, "", "");

        assertRejectedThenAccepted(broken, "must have a corresponding LENGTH field");
    }

    @Test
    void artioRejectsAnUndeclaredFieldReferenceButAcceptsTheConversion() {
        final String broken = Dictionaries.minimal("""
                    <message name="News" msgtype="B" msgcat="app">
                      <field name="Ghost" required="N"/>
                    </message>
                """, "", "");

        assertRejectedThenAccepted(broken, "element for Ghost");
    }

    @Test
    void artioRejectsAnUndeclaredComponentReferenceButAcceptsTheConversion() {
        final String broken = Dictionaries.minimal("""
                    <message name="News" msgtype="B" msgcat="app">
                      <component name="GhostBlock" required="N"/>
                    </message>
                """, "", "");

        assertRejectedThenAccepted(broken, "element:GhostBlock");
    }

    @Test
    void artioRejectsAGroupWhoseCounterFieldIsNotDeclaredButAcceptsTheConversion() {
        final String broken = Dictionaries.minimal("""
                    <message name="News" msgtype="B" msgcat="app">
                      <group name="NoGhosts" required="N"><field name="Text" required="N"/></group>
                    </message>
                """, "", "");

        assertRejectedThenAccepted(broken, "Group.of");
    }

    @Test
    void artioRejectsAFieldNameDeclaredTwiceButAcceptsTheConversion() {
        final String broken = Dictionaries.minimalWithField(
                "    <field number=\"9004\" name=\"Symbol\" type=\"STRING\"/>\n");

        assertRejectedThenAccepted(broken, "same field name defined twice");
    }

    @Test
    void artioRejectsAFieldReachedTwiceInAMessageButAcceptsTheConversion() {
        final String broken = Dictionaries.minimal("""
                    <message name="News" msgtype="B" msgcat="app">
                      <field name="Symbol" required="N"/>
                      <component name="Instrument" required="N"/>
                    </message>
                """, "", "    <component name=\"Instrument\">"
                + "<field name=\"Symbol\" required=\"N\"/></component>\n");

        assertRejectedThenAccepted(broken, "same field defined more than once on a message");
    }

    @Test
    void artioRejectsAFieldRepeatedDirectlyInAComponentButAcceptsTheConversion() {
        final String broken = Dictionaries.minimal("""
                    <message name="News" msgtype="B" msgcat="app">
                      <component name="Dup" required="N"/>
                    </message>
                """, "", "    <component name=\"Dup\"><field name=\"Symbol\" required=\"N\"/>"
                + "<field name=\"Symbol\" required=\"N\"/></component>\n");

        assertRejectedThenAccepted(broken, "same field defined more than once on a message");
    }

    /**
     * The rules have to compose: a venue dictionary usually breaks several things at once.
     * This takes QuickFIX/J's FIX 4.2 and breaks eight of them, then checks the converter
     * repairs all eight in one pass and Artio accepts the result.
     */
    @Test
    void aFix42DictionaryBrokenInEightWaysIsRepairedInOnePassAndAcceptedByArtio() throws Exception {
        final String original;
        try (var in = Dictionaries.openBundled(Dictionaries.BUNDLED_FIX42)) {
            original = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        final String broken = original
                // session-header-field, twice
                .replace("    <field name=\"SenderSubID\" required=\"N\"/>\n", "")
                .replace("    <field name=\"LastMsgSeqNumProcessed\" required=\"N\"/>\n", "")
                // session-message-field
                .replace("      <field name=\"SessionRejectReason\" required=\"N\"/>\n", "")
                // session-message-missing
                .replaceAll("(?s)\\s*<message name=\"TestRequest\".*?</message>", "")
                // unknown-field-type, enum-description-java-name, -dedup and -java-keyword
                .replace("  <fields>", """
                          <fields>
                            <field number="9001" name="VenueWeird" type="NUMBER"/>
                            <field number="9002" name="VenueFlavour" type="CHAR">
                              <value enum="1" description="A-B"/>
                              <value enum="2" description="A_B"/>
                              <value enum="3" description="new"/>
                            </field>""")
                // data-field-length
                .replace("    <message name=\"Logon\" msgtype=\"A\" msgcat=\"admin\">", """
                            <message name="VenueBlob" msgtype="U1" msgcat="app">
                              <field name="RawData" required="N"/>
                            </message>
                            <message name="Logon" msgtype="A" msgcat="admin">""");

        assertThrows(
                ArtioDictionaryCheck.DictionaryRejectedException.class,
                () -> ArtioDictionaryCheck.check(broken, "broken FIX42"),
                "Artio should refuse the broken dictionary");

        final ConversionResult result = converter.convert(Dictionaries.read(broken));
        final String repaired = writer.toXml(result.dictionary());
        ArtioDictionaryCheck.check(repaired, "repaired FIX42");

        final var rules = result.notes().stream().map(ConversionNote::rule).collect(
                java.util.stream.Collectors.toSet());
        assertEquals(
                java.util.Set.of(
                        ArtioDictionaryConverter.RULE_SESSION_HEADER_FIELD,
                        ArtioDictionaryConverter.RULE_SESSION_MESSAGE_FIELD,
                        ArtioDictionaryConverter.RULE_SESSION_MESSAGE_MISSING,
                        ArtioDictionaryConverter.RULE_UNKNOWN_FIELD_TYPE,
                        ArtioDictionaryConverter.RULE_FIELD_TYPE_ALIAS,
                        ArtioDictionaryConverter.RULE_ENUM_DESCRIPTION_JAVA_NAME,
                        ArtioDictionaryConverter.RULE_ENUM_DESCRIPTION_DEDUP,
                        ArtioDictionaryConverter.RULE_ENUM_DESCRIPTION_JAVA_KEYWORD,
                        ArtioDictionaryConverter.RULE_DATA_FIELD_LENGTH),
                rules,
                () -> "unexpected rule set: " + result.notes());

        // ... and repairing the repair changes nothing.
        assertTrue(converter.convert(Dictionaries.read(repaired)).unchanged());
    }

    @Test
    void aRejectionCarriesArtiosOwnReason() {
        final ArtioDictionaryCheck.DictionaryRejectedException e = assertThrows(
                ArtioDictionaryCheck.DictionaryRejectedException.class,
                () -> ArtioDictionaryCheck.check("<fix major=\"4\" minor=\"2\"><fields/>", "broken.xml"));

        assertTrue(e.getMessage().startsWith("Artio's DictionaryParser rejected broken.xml:"), e.getMessage());
    }

    /**
     * Asserts Artio refuses {@code broken} for the stated reason, then accepts what the converter
     * makes of it. {@code expectedReason} is matched against the whole exception chain so both the
     * message and the stack frame that raised it can be used.
     */
    private void assertRejectedThenAccepted(final String broken, final String expectedReason) {
        final ArtioDictionaryCheck.DictionaryRejectedException rejection = assertThrows(
                ArtioDictionaryCheck.DictionaryRejectedException.class,
                () -> ArtioDictionaryCheck.check(broken, "unconverted"),
                "Artio was expected to reject this dictionary");
        assertTrue(describe(rejection).contains(expectedReason),
                () -> "expected Artio to complain about '" + expectedReason + "' but it said:\n"
                        + describe(rejection));

        final String converted = writer.toXml(converter.convert(Dictionaries.read(broken)).dictionary());
        ArtioDictionaryCheck.check(converted, "converted");
    }

    private static String describe(final Throwable throwable) {
        final StringBuilder text = new StringBuilder();
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            text.append(t).append('\n');
            for (final StackTraceElement frame : t.getStackTrace()) {
                text.append("    ").append(frame).append('\n');
            }
        }
        return text.toString();
    }
}
