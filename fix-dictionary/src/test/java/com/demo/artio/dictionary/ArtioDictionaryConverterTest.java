package com.demo.artio.dictionary;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtioDictionaryConverterTest {

    private final ArtioDictionaryConverter converter = new ArtioDictionaryConverter();

    // ---------------------------------------------------------------------------------------
    // The bundled dictionaries
    // ---------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {Dictionaries.BUNDLED_FIX42, Dictionaries.BUNDLED_FIX44})
    void everyMessageComponentAndFieldSurvivesConversion(final String resource) {
        final FixDictionary source = Dictionaries.readBundled(resource);
        final FixDictionary converted = converter.convert(source).dictionary();

        assertEquals(
                source.messages().stream().map(MessageDef::name).toList(),
                converted.messages().stream().map(MessageDef::name).toList(),
                "no message may be dropped or reordered");
        assertEquals(
                source.components().stream().map(ComponentDef::name).toList(),
                converted.components().stream().map(ComponentDef::name).toList());
        assertEquals(
                source.fields().stream().map(FieldDef::name).toList(),
                converted.fields().stream().map(FieldDef::name).toList());
        assertEquals(source.major(), converted.major());
        assertEquals(source.minor(), converted.minor());
        assertEquals(source.beginString(), converted.beginString());
    }

    @ParameterizedTest
    @ValueSource(strings = {Dictionaries.BUNDLED_FIX42, Dictionaries.BUNDLED_FIX44})
    void conversionIsIdempotent(final String resource) {
        final ArtioDictionaryWriter writer = new ArtioDictionaryWriter();

        final ConversionResult first = converter.convert(Dictionaries.readBundled(resource));
        final String firstXml = writer.toXml(first.dictionary());

        final ConversionResult second = converter.convert(Dictionaries.read(firstXml));

        assertEquals(List.of(), second.notes(), "a converted dictionary needs no further changes");
        assertEquals(firstXml, writer.toXml(second.dictionary()), "and renders to identical bytes");
    }

    @ParameterizedTest
    @ValueSource(strings = {Dictionaries.BUNDLED_FIX42, Dictionaries.BUNDLED_FIX44})
    void everyFieldTypeInTheOutputIsAnArtioFieldTypeConstant(final String resource) {
        final FixDictionary converted = converter.convert(Dictionaries.readBundled(resource)).dictionary();

        for (final FieldDef field : converted.fields()) {
            assertTrue(
                    ArtioFieldTypes.isCanonical(field.type()),
                    field.name() + " has type " + field.type() + ", which is not a Field.Type constant");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {Dictionaries.BUNDLED_FIX42, Dictionaries.BUNDLED_FIX44})
    void everyEnumDescriptionIsAUniqueLegalJavaIdentifierWithinItsField(final String resource) {
        final FixDictionary converted = converter.convert(Dictionaries.readBundled(resource)).dictionary();

        for (final FieldDef field : converted.fields()) {
            final Set<String> names = new HashSet<>();
            final Set<String> representations = new HashSet<>();
            for (final EnumValue value : field.values()) {
                assertTrue(names.add(value.description()),
                        field.name() + " has two values called " + value.description());
                assertTrue(representations.add(value.representation()),
                        field.name() + " has two values with enum=" + value.representation());
                assertEquals(value.description(),
                        ArtioDictionaryConverter.toJavaName(value.description()),
                        field.name() + "." + value.description() + " is not a Java identifier");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {Dictionaries.BUNDLED_FIX42, Dictionaries.BUNDLED_FIX44})
    void theConvertedHeaderCarriesEveryFieldArtiosSessionHeaderCodecsDeclare(final String resource) {
        final FixDictionary converted = converter.convert(Dictionaries.readBundled(resource)).dictionary();
        final Set<String> header = converted.header().stream()
                .map(DictionaryEntry::name).collect(Collectors.toSet());

        for (final String required : SessionContract.HEADER_FIELDS) {
            assertTrue(header.contains(required),
                    required + " is missing from the header; HeaderEncoder would not implement "
                            + "SessionHeaderEncoder");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {Dictionaries.BUNDLED_FIX42, Dictionaries.BUNDLED_FIX44})
    void everyAdminMessageCarriesTheFieldsItsArtioCodecMustImplement(final String resource) {
        final FixDictionary converted = converter.convert(Dictionaries.readBundled(resource)).dictionary();

        for (final String msgType : SessionContract.ADMIN_MSG_TYPES) {
            final MessageDef message = converted.messageByMsgType(msgType)
                    .orElseThrow(() -> new AssertionError("admin message " + msgType + " is missing"));
            final Set<String> present = message.entries().stream()
                    .map(DictionaryEntry::name).collect(Collectors.toSet());
            for (final String field : SessionContract.messageFields(msgType)) {
                assertTrue(present.contains(field),
                        message.name() + " is missing " + field);
            }
        }
    }

    @Test
    void fix42NeedsOnlyTheUtcdateAliasRewritten() {
        final ConversionResult result = converter.convert(Dictionaries.readBundled(Dictionaries.BUNDLED_FIX42));

        assertEquals(1, result.notes().size(), () -> "unexpected notes: " + result.notes());
        final ConversionNote note = result.notes().get(0);
        assertEquals(ArtioDictionaryConverter.RULE_FIELD_TYPE_ALIAS, note.rule());
        assertEquals("field MDEntryDate (272)", note.element());
        assertEquals("UTCDATE", note.before());
        assertEquals("UTCDATEONLY", note.after());
    }

    @Test
    void fix44NeedsNoChangesAtAll() {
        final ConversionResult result = converter.convert(Dictionaries.readBundled(Dictionaries.BUNDLED_FIX44));

        assertTrue(result.unchanged(), () -> "unexpected notes: " + result.notes());
    }

    // ---------------------------------------------------------------------------------------
    // One rule at a time. Each fixture breaks exactly one thing Artio needs; see
    // ArtioRejectsUnconvertedDictionaryTest for the matching failure from Artio itself.
    // ---------------------------------------------------------------------------------------

    @Test
    void aMessageWithoutMsgcatGetsAdminForASessionMsgtypeAndAppOtherwise() {
        final String xml = Dictionaries.minimal(
                "    <message name=\"News\" msgtype=\"B\"><field name=\"Text\" required=\"N\"/></message>\n",
                "", "").replace("<message name=\"Logon\" msgtype=\"A\" msgcat=\"admin\">",
                "<message name=\"Logon\" msgtype=\"A\">");

        final ConversionResult result = Dictionaries.convertIdempotently(xml);

        assertEquals("app", result.dictionary().messageByMsgType("B").orElseThrow().msgCat());
        assertEquals("admin", result.dictionary().messageByMsgType("A").orElseThrow().msgCat());
        assertEquals(2, notesFor(result, ArtioDictionaryConverter.RULE_MESSAGE_CATEGORY).size());
    }

    @Test
    void anUnknownFieldTypeBecomesStringWithANote() {
        final ConversionResult result = Dictionaries.convertIdempotently(
                Dictionaries.minimalWithField("    <field number=\"9001\" name=\"Weird\" type=\"NUMBER\"/>\n"));

        assertEquals("STRING", result.dictionary().fieldsByName().get("Weird").type());
        final List<ConversionNote> notes = notesFor(result, ArtioDictionaryConverter.RULE_UNKNOWN_FIELD_TYPE);
        assertEquals(1, notes.size());
        assertEquals("NUMBER", notes.get(0).before());
        assertEquals("STRING", notes.get(0).after());
    }

    @Test
    void theFourLegacyTypeSpellingsAreRewrittenToArtioConstants() {
        final ConversionResult result = Dictionaries.convertIdempotently(Dictionaries.minimalWithField("""
                    <field number="9002" name="MaturityDate" type="UTCDATE"/>
                    <field number="9003" name="Coupon" type="RATE"/>
                    <field number="9004" name="Contract" type="MONTH-YEAR"/>
                    <field number="9005" name="Typo" type="STIRNG"/>
                """));

        final var fields = result.dictionary().fieldsByName();
        assertEquals("UTCDATEONLY", fields.get("MaturityDate").type());
        assertEquals("PRICE", fields.get("Coupon").type());
        assertEquals("MONTHYEAR", fields.get("Contract").type());
        assertEquals("STRING", fields.get("Typo").type());
        assertEquals(4, notesFor(result, ArtioDictionaryConverter.RULE_FIELD_TYPE_ALIAS).size());
    }

    @Test
    void aValueWithoutADescriptionGetsOneDerivedFromItsRepresentation() {
        final ConversionResult result = Dictionaries.convertIdempotently(Dictionaries.minimalWithField("""
                    <field number="9006" name="Flavour" type="CHAR">
                      <value enum="1"/>
                      <value enum="2" description=""/>
                    </field>
                """));

        assertEquals(
                List.of("VALUE_1", "VALUE_2"),
                result.dictionary().fieldsByName().get("Flavour").values().stream()
                        .map(EnumValue::description).toList());
        assertEquals(2, notesFor(result, ArtioDictionaryConverter.RULE_ENUM_DESCRIPTION_REQUIRED).size());
    }

    @Test
    void twoDescriptionsThatNormaliseToTheSameJavaNameAreMadeUnique() {
        final ConversionResult result = Dictionaries.convertIdempotently(Dictionaries.minimalWithField("""
                    <field number="9007" name="Flavour" type="CHAR">
                      <value enum="1" description="A-B"/>
                      <value enum="2" description="A_B"/>
                    </field>
                """));

        assertEquals(
                List.of("A_B", "A_B_2"),
                result.dictionary().fieldsByName().get("Flavour").values().stream()
                        .map(EnumValue::description).toList());
        assertEquals(1, notesFor(result, ArtioDictionaryConverter.RULE_ENUM_DESCRIPTION_JAVA_NAME).size());
        assertEquals(1, notesFor(result, ArtioDictionaryConverter.RULE_ENUM_DESCRIPTION_DEDUP).size());
    }

    @Test
    void deduplicationNeverStealsANameAnotherValueAlreadyHas() {
        final ConversionResult result = Dictionaries.convertIdempotently(Dictionaries.minimalWithField("""
                    <field number="9013" name="Flavour" type="CHAR">
                      <value enum="1" description="ONE"/>
                      <value enum="2" description="ONE"/>
                      <value enum="3" description="ONE_2"/>
                    </field>
                """));

        assertEquals(
                List.of("ONE", "ONE_3", "ONE_2"),
                result.dictionary().fieldsByName().get("Flavour").values().stream()
                        .map(EnumValue::description).toList(),
                "the third value was unique in the source and must keep its name");
        assertEquals(1, notesFor(result, ArtioDictionaryConverter.RULE_ENUM_DESCRIPTION_DEDUP).size());
    }

    @Test
    void aDescriptionThatIsAJavaKeywordGetsAnUnderscoreSuffix() {
        final ConversionResult result = Dictionaries.convertIdempotently(Dictionaries.minimalWithField("""
                    <field number="9008" name="Flavour" type="CHAR">
                      <value enum="1" description="new"/>
                      <value enum="2" description="class"/>
                    </field>
                """));

        assertEquals(
                List.of("new_", "class_"),
                result.dictionary().fieldsByName().get("Flavour").values().stream()
                        .map(EnumValue::description).toList());
        assertEquals(2, notesFor(result, ArtioDictionaryConverter.RULE_ENUM_DESCRIPTION_JAVA_KEYWORD).size());
    }

    @Test
    void aRepeatedEnumRepresentationIsDropped() {
        final ConversionResult result = Dictionaries.convertIdempotently(Dictionaries.minimalWithField("""
                    <field number="9009" name="Flavour" type="CHAR">
                      <value enum="1" description="ONE"/>
                      <value enum="1" description="UNO"/>
                    </field>
                """));

        assertEquals(
                List.of("ONE"),
                result.dictionary().fieldsByName().get("Flavour").values().stream()
                        .map(EnumValue::description).toList());
        assertEquals(1, notesFor(result, ArtioDictionaryConverter.RULE_ENUM_REPRESENTATION_DEDUP).size());
    }

    @Test
    void aFieldNameDeclaredTwiceKeepsTheFirstDeclaration() {
        final ConversionResult result = Dictionaries.convertIdempotently(
                Dictionaries.minimalWithField("    <field number=\"9010\" name=\"Symbol\" type=\"STRING\"/>\n"));

        assertEquals(55, result.dictionary().fieldsByName().get("Symbol").number());
        assertEquals(1, notesFor(result, ArtioDictionaryConverter.RULE_DUPLICATE_FIELD_DECLARATION).size());
    }

    @Test
    void referencesToUndeclaredFieldsComponentsAndGroupCountersAreDropped() {
        final ConversionResult result = Dictionaries.convertIdempotently(Dictionaries.minimal("""
                    <message name="News" msgtype="B" msgcat="app">
                      <field name="Ghost" required="N"/>
                      <component name="GhostBlock" required="N"/>
                      <group name="NoGhosts" required="N"><field name="Text" required="N"/></group>
                      <field name="Text" required="N"/>
                    </message>
                """, "", ""));

        final MessageDef news = result.dictionary().messageByMsgType("B").orElseThrow();
        assertEquals(List.of("Text"), news.entries().stream().map(DictionaryEntry::name).toList());
        assertEquals(3, notesFor(result, ArtioDictionaryConverter.RULE_UNDEFINED_REFERENCE).size());
    }

    @Test
    void aFieldReachedBothDirectlyAndThroughAComponentIsDroppedFromTheMessage() {
        final ConversionResult result = Dictionaries.convertIdempotently(Dictionaries.minimal("""
                    <message name="News" msgtype="B" msgcat="app">
                      <field name="Symbol" required="N"/>
                      <component name="Instrument" required="N"/>
                    </message>
                """, "", "    <component name=\"Instrument\">"
                + "<field name=\"Symbol\" required=\"N\"/></component>\n"));

        final MessageDef news = result.dictionary().messageByMsgType("B").orElseThrow();
        assertEquals(List.of("Instrument"), news.entries().stream().map(DictionaryEntry::name).toList());
        assertEquals(1, notesFor(result, ArtioDictionaryConverter.RULE_DUPLICATE_FIELD_IN_MESSAGE).size());
    }

    @Test
    void aFieldReachedThroughAGroupAndThenDirectlyLosesTheLaterDirectCopy() {
        final ConversionResult result = Dictionaries.convertIdempotently(Dictionaries.minimal("""
                    <message name="News" msgtype="B" msgcat="app">
                      <group name="NoLegs" required="N"><field name="Symbol" required="N"/></group>
                      <field name="Symbol" required="N"/>
                    </message>
                """, "    <field number=\"555\" name=\"NoLegs\" type=\"NUMINGROUP\"/>\n", ""));

        final MessageDef news = result.dictionary().messageByMsgType("B").orElseThrow();
        assertEquals(List.of("NoLegs"), news.entries().stream().map(DictionaryEntry::name).toList(),
                "Artio reports the later occurrence, and a group is not shared, so the later copy goes");
        final GroupRef group = (GroupRef) news.entries().get(0);
        assertEquals(List.of("Symbol"), group.children().stream().map(DictionaryEntry::name).toList());
        assertEquals(1, notesFor(result, ArtioDictionaryConverter.RULE_DUPLICATE_FIELD_IN_MESSAGE).size());
    }

    @Test
    void aFieldReachedDirectlyAndThenThroughAGroupLosesTheGroupCopy() {
        final ConversionResult result = Dictionaries.convertIdempotently(Dictionaries.minimal("""
                    <message name="News" msgtype="B" msgcat="app">
                      <field name="Symbol" required="N"/>
                      <group name="NoLegs" required="N">
                        <field name="Symbol" required="N"/>
                        <field name="Text" required="N"/>
                      </group>
                    </message>
                """, "    <field number=\"555\" name=\"NoLegs\" type=\"NUMINGROUP\"/>\n", ""));

        final MessageDef news = result.dictionary().messageByMsgType("B").orElseThrow();
        assertEquals(List.of("Symbol", "NoLegs"), news.entries().stream().map(DictionaryEntry::name).toList());
        final GroupRef group = (GroupRef) news.entries().get(1);
        assertEquals(List.of("Text"), group.children().stream().map(DictionaryEntry::name).toList());
        assertEquals(1, notesFor(result, ArtioDictionaryConverter.RULE_DUPLICATE_FIELD_IN_MESSAGE).size());
    }

    @Test
    void aFieldReachedThroughTwoComponentsIsDroppedFromTheSecondComponentsDefinition() {
        final ConversionResult result = Dictionaries.convertIdempotently(Dictionaries.minimal("""
                    <message name="News" msgtype="B" msgcat="app">
                      <component name="Instrument" required="N"/>
                      <component name="UnderlyingInstrument" required="N"/>
                    </message>
                """, "", """
                    <component name="Instrument"><field name="Symbol" required="N"/></component>
                    <component name="UnderlyingInstrument">
                      <field name="Symbol" required="N"/>
                      <field name="Text" required="N"/>
                    </component>
                """));

        final MessageDef news = result.dictionary().messageByMsgType("B").orElseThrow();
        assertEquals(List.of("Instrument", "UnderlyingInstrument"),
                news.entries().stream().map(DictionaryEntry::name).toList(),
                "the message keeps both components");
        final var components = result.dictionary().componentsByName();
        assertEquals(List.of("Symbol"),
                components.get("Instrument").entries().stream().map(DictionaryEntry::name).toList());
        assertEquals(List.of("Text"),
                components.get("UnderlyingInstrument").entries().stream().map(DictionaryEntry::name).toList(),
                "the later component definition loses the field");
        final List<ConversionNote> notes = notesFor(result, ArtioDictionaryConverter.RULE_DUPLICATE_FIELD_IN_MESSAGE);
        assertEquals(1, notes.size());
        assertEquals("component UnderlyingInstrument", notes.get(0).element());
        assertTrue(notes.get(0).before().contains("News"), "the note names the message that caused it: " + notes.get(0));
    }

    @Test
    void aFieldReachedDirectlyAndThroughANestedComponentIsDroppedFromTheMessage() {
        final ConversionResult result = Dictionaries.convertIdempotently(Dictionaries.minimal("""
                    <message name="News" msgtype="B" msgcat="app">
                      <field name="Symbol" required="N"/>
                      <component name="InstrumentBlock" required="N"/>
                    </message>
                """, "", """
                    <component name="InstrumentBlock"><component name="Instrument" required="N"/></component>
                    <component name="Instrument"><field name="Symbol" required="N"/></component>
                """));

        final MessageDef news = result.dictionary().messageByMsgType("B").orElseThrow();
        assertEquals(List.of("InstrumentBlock"), news.entries().stream().map(DictionaryEntry::name).toList());
        final var components = result.dictionary().componentsByName();
        assertEquals(List.of("Symbol"),
                components.get("Instrument").entries().stream().map(DictionaryEntry::name).toList(),
                "a component is shared, so it keeps its copy");
        assertEquals(1, notesFor(result, ArtioDictionaryConverter.RULE_DUPLICATE_FIELD_IN_MESSAGE).size());
    }

    @Test
    void aComponentReachedTwiceIsDroppedTheSecondTimeAsAWhole() {
        final ConversionResult result = Dictionaries.convertIdempotently(Dictionaries.minimal("""
                    <message name="News" msgtype="B" msgcat="app">
                      <component name="Instrument" required="N"/>
                      <component name="InstrumentBlock" required="N"/>
                    </message>
                """, "", """
                    <component name="InstrumentBlock"><component name="Instrument" required="N"/></component>
                    <component name="Instrument"><field name="Symbol" required="N"/></component>
                """));

        final MessageDef news = result.dictionary().messageByMsgType("B").orElseThrow();
        assertEquals(List.of("Instrument", "InstrumentBlock"),
                news.entries().stream().map(DictionaryEntry::name).toList());
        assertEquals(List.of(),
                result.dictionary().componentsByName().get("InstrumentBlock").entries(),
                "the nested reference is what Artio reaches second");
        assertEquals(1, notesFor(result, ArtioDictionaryConverter.RULE_DUPLICATE_FIELD_IN_MESSAGE).size());
    }

    @Test
    void aDataFieldWhoseLengthPartnerIsAlreadyReachedThroughAComponentIsRetypedNotDuplicated() {
        final ConversionResult result = Dictionaries.convertIdempotently(Dictionaries.minimal("""
                    <message name="News" msgtype="B" msgcat="app">
                      <component name="Lengths" required="N"/>
                      <field name="RawData" required="N"/>
                    </message>
                """, "", "    <component name=\"Lengths\"><field name=\"RawDataLength\" required=\"N\"/></component>\n"));

        final MessageDef news = result.dictionary().messageByMsgType("B").orElseThrow();
        assertEquals(List.of("Lengths", "RawData"), news.entries().stream().map(DictionaryEntry::name).toList(),
                "no second RawDataLength may be inserted beside the DATA field");
        assertEquals("STRING", result.dictionary().fieldsByName().get("RawData").type());
        assertEquals(1, notesFor(result, ArtioDictionaryConverter.RULE_DATA_FIELD_LENGTH).size());
        assertEquals(0, notesFor(result, ArtioDictionaryConverter.RULE_DUPLICATE_FIELD_IN_MESSAGE).size());
    }

    @Test
    void missingSessionHeaderFieldsAreAppendedAndDeclared() {
        final String xml = Dictionaries.minimal()
                .replace("    <field name=\"SenderSubID\" required=\"N\"/>\n", "")
                .replace("    <field name=\"LastMsgSeqNumProcessed\" required=\"N\"/>\n", "")
                .replace("    <field number=\"369\" name=\"LastMsgSeqNumProcessed\" type=\"SEQNUM\"/>\n", "");

        final ConversionResult result = Dictionaries.convertIdempotently(xml);

        final Set<String> header = result.dictionary().header().stream()
                .map(DictionaryEntry::name).collect(Collectors.toSet());
        assertTrue(header.contains("SenderSubID"));
        assertTrue(header.contains("LastMsgSeqNumProcessed"));
        final FieldDef declared = result.dictionary().fieldsByName().get("LastMsgSeqNumProcessed");
        assertNotNull(declared, "the field also has to be declared in <fields>");
        assertEquals(369, declared.number());
        assertEquals("SEQNUM", declared.type());
        assertFalse(notesFor(result, ArtioDictionaryConverter.RULE_SESSION_HEADER_FIELD).isEmpty());
    }

    @Test
    void missingAdminMessageFieldsAreAppended() {
        final String xml = Dictionaries.minimal()
                .replace("      <field name=\"SessionRejectReason\" required=\"N\"/>\n", "")
                .replace("      <field name=\"ResetSeqNumFlag\" required=\"N\"/>\n", "");

        final ConversionResult result = Dictionaries.convertIdempotently(xml);

        assertTrue(fieldNames(result, "3").contains("SessionRejectReason"));
        assertTrue(fieldNames(result, "A").contains("ResetSeqNumFlag"));
        assertEquals(2, notesFor(result, ArtioDictionaryConverter.RULE_SESSION_MESSAGE_FIELD).size());
    }

    @Test
    void anAdminMessageArtioDrivesItselfIsAddedWhenTheDictionaryOmitsIt() {
        final String xml = Dictionaries.minimal()
                .replace("""
                            <message name="TestRequest" msgtype="1" msgcat="admin">
                              <field name="TestReqID" required="Y"/>
                            </message>
                        """, "")
                .replace("      <value enum=\"1\" description=\"TEST_REQUEST\"/>\n", "");

        final ConversionResult result = Dictionaries.convertIdempotently(xml);

        final MessageDef testRequest = result.dictionary().messageByMsgType("1").orElseThrow();
        assertEquals("TestRequest", testRequest.name());
        assertEquals("admin", testRequest.msgCat());
        assertEquals(List.of("TestReqID"), testRequest.entries().stream().map(DictionaryEntry::name).toList());

        final FieldDef msgType = result.dictionary().fieldsByName().get("MsgType");
        assertTrue(msgType.values().stream().anyMatch(v -> v.representation().equals("1")),
                "tag 35 should list the msgtype of a message that was added");
        assertEquals(2, notesFor(result, ArtioDictionaryConverter.RULE_SESSION_MESSAGE_MISSING).size());
    }

    @Test
    void aDataFieldGetsItsLengthPartnerInsertedIntoTheSameAggregate() {
        final ConversionResult result = Dictionaries.convertIdempotently(Dictionaries.minimal("""
                    <message name="News" msgtype="B" msgcat="app">
                      <field name="RawData" required="N"/>
                    </message>
                """, "", ""));

        final MessageDef news = result.dictionary().messageByMsgType("B").orElseThrow();
        assertEquals(
                List.of("RawDataLength", "RawData"),
                news.entries().stream().map(DictionaryEntry::name).toList(),
                "the length field must precede the DATA field it measures");
        assertEquals(1, notesFor(result, ArtioDictionaryConverter.RULE_DATA_FIELD_LENGTH).size());
    }

    @Test
    void aDataFieldWithNoDeclaredLengthPartnerAnywhereIsRetypedToString() {
        final ConversionResult result = Dictionaries.convertIdempotently(Dictionaries.minimal("""
                    <message name="News" msgtype="B" msgcat="app">
                      <field name="Blob" required="N"/>
                    </message>
                """, "    <field number=\"9011\" name=\"Blob\" type=\"DATA\"/>\n", ""));

        assertEquals("STRING", result.dictionary().fieldsByName().get("Blob").type());
        assertEquals(1, notesFor(result, ArtioDictionaryConverter.RULE_DATA_FIELD_LENGTH).size());
    }

    @Test
    void everyNoteNamesTheRuleTheElementTheBeforeTheAfterAndTheReason() {
        final ConversionResult result = Dictionaries.convertIdempotently(Dictionaries.minimalWithField(
                "    <field number=\"9012\" name=\"Weird\" type=\"NUMBER\"/>\n"));

        for (final ConversionNote note : result.notes()) {
            assertFalse(note.rule().isBlank());
            assertFalse(note.element().isBlank());
            assertNotNull(note.before());
            assertNotNull(note.after());
            assertTrue(note.why().length() > 20, "the reason must name the Artio failure: " + note.why());
        }
    }

    private static List<ConversionNote> notesFor(final ConversionResult result, final String rule) {
        return result.notes().stream().filter(n -> n.rule().equals(rule)).toList();
    }

    private static Set<String> fieldNames(final ConversionResult result, final String msgType) {
        return result.dictionary().messageByMsgType(msgType).orElseThrow().entries().stream()
                .map(DictionaryEntry::name).collect(Collectors.toSet());
    }
}
