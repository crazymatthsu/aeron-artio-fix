# QuickFIX/J dictionaries to Artio dictionaries

How `:fix-dictionary` turns a QuickFIX/J FIX dictionary into one Artio accepts, why each
rule exists, and how `:fix-codecs` generates codecs from the result at build time.

Everything below was verified against **Artio 0.168** on 2026-09-06 by running
`uk.co.real_logic.artio.dictionary.DictionaryParser`, then
`uk.co.real_logic.artio.dictionary.CodecGenerationTool`, then `javac` on the generated
sources. Where a rule quotes a failure, that failure was produced from a dictionary that
breaks exactly one thing.

---

## 1. The headline

**QuickFIX/J's shipped `FIX42.xml` and `FIX44.xml` are already Artio dictionaries.**
Artio's parser accepts both unmodified, its generator produces codecs from both, and those
codecs compile and round-trip. The converter changes exactly one thing in FIX 4.2 and
nothing at all in FIX 4.4:

```
FIX42.xml -> build/artio-dictionaries/FIX42.xml
  FIX.4.2  messages=46 components=0 fields=403
  1 change(s):
  [field-type-alias] x1
      field MDEntryDate (272): UTCDATE -> UTCDATEONLY
FIX44.xml -> build/artio-dictionaries/FIX44.xml
  FIX.4.4  messages=92 components=24 fields=916
  no changes: the input was already an Artio dictionary
```

That is a useful result, not a disappointing one: it means a **venue's** dictionary --
which is where the real work is -- can be dropped in and the converter will say precisely
what about it Artio would have refused, instead of the build dying inside a code generator.
The rule set exists for those files.

---

## 2. The two formats side by side

Artio's own reference dictionary is
`artio-session-codecs/src/main/resources/session_dictionary.xml`, a slim FIX 4.4
admin-only dictionary. Structurally it is indistinguishable from a QuickFIX/J file:

```xml
<fix type="FIX" major="4" minor="4">
  <header>   <field name="BeginString" required="Y"/> ... </header>
  <trailer>  <field name="CheckSum"    required="Y"/> </trailer>
  <messages>
    <message name="Logon" msgtype="A" msgcat="admin">
      <field name="EncryptMethod" required="Y"/>
      ...
    </message>
  </messages>
  <components>
    <component name="Instrument"><field name="Symbol" required="Y"/></component>
  </components>
  <fields>
    <field number="54" name="Side" type="CHAR">
      <value enum="1" description="BUY"/>
      <value enum="2" description="SELL"/>
    </field>
  </fields>
</fix>
```

Same elements, same attributes, same nesting. What differs is what each engine *does* with
them:

| | QuickFIX/J | Artio |
| --- | --- | --- |
| When the dictionary is read | at runtime, into a `DataDictionary` used for validation | at **build** time, into `Dictionary` (`uk.co.real_logic.artio.dictionary.ir`), then turned into Java source |
| `<value description>` | a label; never becomes code | becomes a **Java enum constant name**, so it must be a legal, unique identifier |
| `msgcat` | optional, defaults to app | **mandatory**; `getValue(attributes, "msgcat")` throws if absent |
| `type` on a field | a string matched against a fixed table, unknown types tolerated in places | `Field.Type.lookup()` ends in `valueOf()`; unknown types throw |
| Missing header fields | the engine simply never sees them | the generated `HeaderEncoder` fails to implement `SessionHeaderEncoder` and **javac** fails |
| A DATA field's length partner | resolved by name across the message | must be a **direct sibling** in the same aggregate, named `<Name>Len` or `<Name>Length`, typed `LENGTH` or `INT` |
| Repeating group counters | `NoXxx` field, any integer type | `Group.of` synthesises a `NoXxxGroupCounter` field of type `NUMINGROUP` regardless of the declared type |
| `type` attribute on `<fix>` | present | optional; Artio defaults it to `FIX` (`DictionaryParser.DEFAULT_SPEC_TYPE`) |
| `required` attribute | required | optional; a missing `required` is read as `N` |

Three of Artio's behaviours are worth spelling out, because they are the reason so few
changes are needed:

* **`Field.Type.lookup` has four legacy special cases** built in: `UTCDATE ->
  UTCDATEONLY`, `MONTH-YEAR -> MONTHYEAR`, `STIRNG -> STRING` (a typo in some real
  dictionary), `RATE -> PRICE`. Everything else must be a `Field.Type` constant.
* **`enumDescriptionToJavaName`** rewrites a description into an identifier by replacing
  every character that is not `Character.isJavaIdentifierPart` with `_`, and prefixing `_`
  when the first character cannot start an identifier. It does **not** deduplicate, and it
  does not know about Java keywords.
* **`correctMultiCharacterCharEnums`** retypes a `CHAR` field to `STRING` when any of its
  values is longer than one character, so a `CHAR` field with `enum="AB"` is fine.

---

## 3. The conversion rules

Each rule has a `RULE_` constant in `ArtioDictionaryConverter` and a `WHY_` string quoting
the failure. `ArtioDictionaryCheckTest` re-runs the reproduction for every rule whose
failure Artio's *parser* can see; the rest are covered by `:fix-codecs:compileJava`.

Each heading says where the failure shows up: **parser** = `DictionaryParser.parse` refuses
the dictionary, **javac** = the dictionary parses and generates but the generated Java does
not compile, **runtime** = it builds and breaks when a session starts.

### 3.1 `field-type-alias` and `unknown-field-type` — parser

A field type that is not a `Field.Type` constant:

```xml
<field number="9001" name="Weird" type="NUMBER"/>
```

```
java.lang.IllegalArgumentException: No enum constant
    uk.co.real_logic.artio.dictionary.ir.Field.Type.NUMBER
    at uk.co.real_logic.artio.dictionary.ir.Field$Type.lookup(Field.java:305)
    at uk.co.real_logic.artio.dictionary.DictionaryParser.lambda$parseFields$9
```

There is no line number and no field name in that message; on a 900-field dictionary it is
a hunt. The converter rewrites the four aliases Artio tolerates to their canonical names
(`field-type-alias`) and maps anything else to `STRING` with a note
(`unknown-field-type`) — every FIX value is ASCII text, so `STRING` is always decodable,
just untyped.

**Fires on the bundled files:** yes, once. FIX 4.2 declares `MDEntryDate (272)` as
`UTCDATE`. Artio would have survived it through the special case; rewriting it means the
output only uses names that are actually in the enum, which is what
`everyFieldTypeInTheOutputIsAnArtioFieldTypeConstant` asserts.

`ArtioFieldTypesTest.theTypeTableMatchesArtiosFieldTypeEnumExactly` compares the table
against `Field.Type.values()` by reflection, so an Artio upgrade that adds a type fails the
build rather than silently downgrading fields to `STRING`.

### 3.2 `message-category` — parser

```xml
<message name="NewOrderSingle" msgtype="D">
```

```
java.lang.NullPointerException: Empty item for: msgcat in {msgtype=D,name=NewOrderSingle}
    at uk.co.real_logic.artio.dictionary.DictionaryParser.getValue(DictionaryParser.java:449)
    at uk.co.real_logic.artio.dictionary.DictionaryParser.lambda$parseMessages$11
```

QuickFIX/J treats `msgcat` as optional. The converter adds `admin` for the seven session
msgtypes (`0 1 2 3 4 5 A`) and `app` for everything else.

**Fires on the bundled files:** no; all 46 and all 92 messages carry `msgcat`.

### 3.3 `enum-description-required` — parser

```xml
<value enum="1"/>                  <!-- no description -->
<value enum="2" description=""/>   <!-- empty description -->
```

```
java.lang.NullPointerException: Empty item for: description in {enum=1}
    at uk.co.real_logic.artio.dictionary.DictionaryParser.lambda$extractEnumValues$10
```

```
java.lang.StringIndexOutOfBoundsException: Index 0 out of bounds for length 0
    at uk.co.real_logic.artio.dictionary.DictionaryParser.enumDescriptionToJavaName
```

The converter synthesises `VALUE_<representation>`.

**Fires on the bundled files:** no.

### 3.4 `enum-description-java-name` — javac (through Artio's own rewrite)

Artio rewrites a description into an identifier itself, so this rule changes nothing Artio
would have rejected on its own. It exists because doing the rewrite *in the dictionary*
makes the collisions it creates visible, which is what 3.5 repairs. `A-B` and `A_B` both
become `A_B`; you only find out at compile time otherwise.

**Fires on the bundled files:** no.

### 3.5 `enum-description-dedup` — javac

```xml
<field number="9006" name="Flavour" type="CHAR">
  <value enum="1" description="ONE"/>
  <value enum="2" description="ONE"/>
</field>
```

Parser: fine. Generator: fine. `javac`:

```
Flavour.java:16: error: variable ONE is already defined in enum Flavour
```

The converter suffixes `_2`, `_3`, ... The suffix keeps the wire representation untouched;
only the generated constant name changes.

**Fires on the bundled files:** no. FIX 4.2 and 4.4 have no colliding descriptions.

### 3.6 `enum-description-java-keyword` — javac

```xml
<value enum="1" description="new"/>
```

```
Kw.java:15: error: enum constant expected here
Kw.java:16: error: <identifier> expected
```

`enumDescriptionToJavaName` produces a valid *identifier* but not a valid *name*: Java
keywords pass every `isJavaIdentifierPart` test. The converter appends `_`.

**Fires on the bundled files:** no; FIX descriptions are upper-case.

### 3.7 `enum-representation-dedup` — javac

```xml
<value enum="1" description="ONE"/>
<value enum="1" description="UNO"/>
```

```
Flavour3.java:47: error: duplicate case label
```

Artio generates a `switch` over the wire representation. The converter keeps the first
value and drops the rest.

**Fires on the bundled files:** no.

### 3.8 `duplicate-field-declaration` — parser

Two `<field>` declarations with the same `name` in `<fields>`:

```
java.lang.IllegalStateException: Cannot have the same field name defined twice; this is
against the FIX spec.Details to follow:
Field : Symbol (9100)
Field : Symbol (55)
    at uk.co.real_logic.artio.dictionary.DictionaryParser.lambda$parseFields$9(DictionaryParser.java:284)
```

Artio refuses outright. The converter keeps the first declaration and notes what it
dropped, so the standard tag wins over a venue's later re-declaration.

**Fires on the bundled files:** no.

### 3.9 `duplicate-field-in-message` — parser

The classic venue-dictionary bug: a field included both explicitly and through a component.

```xml
<message name="News" msgtype="B" msgcat="app">
  <field name="Symbol" required="N"/>
  <component name="Instrument" required="N"/>   <!-- also contains Symbol -->
</message>
```

```
java.lang.IllegalStateException: Cannot have the same field defined more than once on a
message; this is against the FIX spec. Details to follow:
Message: News Field : Symbol (55) Through Path: [Instrument]
Use -Dfix.codecs.allow_duplicate_fields=true to allow duplicated fields (Dangerous. May
break parser).
    at uk.co.real_logic.artio.dictionary.DictionaryParser.identifyDuplicateFieldDefinitionsForMessages
```

Artio offers an escape hatch; the converter fixes the dictionary instead, because the
escape hatch is documented as dangerous.

**The rule is a walk, not a comparison, and it has to be Artio's walk.** Artio's
`DictionaryParser` visits a message's entries **in document order** and recurses into
**both** groups and components, accumulating **one tag set for the whole message**; the
second time a tag appears anywhere in that traversal, it throws. So a converter that only
compares a message's direct fields against the tags reachable through its components
answers a strictly smaller question, and three real shapes slip past it with **zero
notes** — only for `ArtioDictionaryCheck` to reject the output a moment later, which is a
worse experience than not converting at all:

* a tag that is direct on the message and also inside one of its **groups**;
* a tag reachable through **two different components** on the same message;
* a tag reachable through a **component nested inside another component**.

The converter therefore performs exactly that walk: entry order, recursing into groups and
components, one tag set per message, and the **later** occurrence — whichever it is,
direct field or not — is the one dropped, with a `ConversionNote` naming it. The same walk
runs over each **component** in its own right, because a component that is
self-inconsistent fails the same way wherever it is included.

Two consequences of walking rather than comparing:

* A field repeated twice **directly** in one aggregate is the same rule, not a special
  case. Artio reports it with no `Through Path` suffix:

  ```
  Message: News Field : Symbol (55)
  ```

* The `Len`/`Length` partner that rule 3.11 inserts for a DATA field is **not** inserted
  when the walk already reaches that tag through a component. Inserting it would create
  the duplicate this rule exists to remove — trading 3.11's failure for 3.9's, in the same
  pass, which is how a converter ends up oscillating.

**Fires on the bundled files:** no.

### 3.10 `undefined-reference` — parser

Three shapes, three different NPEs:

| Input | Failure |
| --- | --- |
| `<field name="Ghost"/>` where `Ghost` is not in `<fields>` | `NullPointerException: element for Ghost must not be null` (`Verify.notNull`, `DictionaryParser.lambda$extractEntries$12`) |
| `<component name="GhostBlock"/>` never declared | `NullPointerException: element:GhostBlock must not be null` (`reconnectForwardReferences`) |
| `<group name="NoGhosts">` whose counter field is not declared | `NullPointerException: Cannot invoke "...Field.name()" because "field" is null` at `Group.of(Group.java:42)` |

The converter drops the reference and records what was dropped, so a truncated venue
dictionary produces a readable list rather than an NPE.

**Fires on the bundled files:** no.

### 3.11 `data-field-length` — parser

```xml
<message name="News" msgtype="B" msgcat="app">
  <field name="RawData" required="N"/>     <!-- no RawDataLength alongside it -->
</message>
```

```
java.lang.IllegalStateException: Each DATA field must have a corresponding LENGTH field
using the suffix 'Len' or 'Length'. RawData is missing a length field in News
    at uk.co.real_logic.artio.dictionary.DictionaryParser.checkAssociatedLengthField(DictionaryParser.java:177)
```

The check (`validateDataFieldsInAggregate`) is per aggregate and looks only at that
aggregate's **direct** field entries — `Aggregate.fieldEntries()` filters `entries()`, it
does not descend into components. Groups and components are validated separately as
aggregates of their own. So the partner has to sit next to the DATA field, not one level up.

The converter, mirroring that scope exactly:

* if `<Name>Length` or `<Name>Len` is declared in `<fields>` with type `LENGTH` or `INT`,
  inserts a reference to it immediately **before** the DATA field (the order FIX requires
  on the wire);
* otherwise retypes the DATA field itself to `STRING`, since there is no length field to
  point at.

**Fires on the bundled files:** no. QuickFIX/J is careful about this; every
`RawData`/`SecureData`/`XmlData`/`EncodedText`/`Signature` already sits next to its length.

### 3.12 `session-header-field` — javac

This is the rule that matters most, and the one you cannot discover from the parser.

Artio's generated `HeaderEncoder` and `HeaderDecoder` must implement
`uk.co.real_logic.artio.builder.SessionHeaderEncoder` and
`uk.co.real_logic.artio.decoder.SessionHeaderDecoder`. Those interfaces are fixed. The
generator only emits an accessor for a field the dictionary puts in `<header>`. Delete one
and the *dictionary still parses*; the build dies in `:fix-codecs:compileJava`:

| Field removed from `<header>` | javac says |
| --- | --- |
| `SenderSubID` | `HeaderDecoder is not abstract and does not override abstract method senderSubID(AsciiSequenceView) in SessionHeaderDecoder` |
| `SenderLocationID` | `... senderLocationID(AsciiSequenceView) ...` |
| `TargetSubID` | `... targetSubID(AsciiSequenceView) ...` |
| `TargetLocationID` | `... targetLocationID(AsciiSequenceView) ...` |
| `PossDupFlag` | `HeaderEncoder ... does not override abstract method hasPossDupFlag() in SessionHeaderEncoder` |
| `PossResend` | `... possResend() ...` |
| `OrigSendingTime` | `... origSendingTime(AsciiSequenceView) ...` |
| `LastMsgSeqNumProcessed` | `HeaderEncoder ... does not override abstract method lastMsgSeqNumProcessed(int) in SessionHeaderEncoder` |

Each of those eight was produced by deleting exactly that one line from a working
dictionary. The converter appends any that are missing as `required="N"` and, if the field
is not declared in `<fields>` at all, adds the standard declaration (tag number and Artio
type taken from artio-session-codecs' own `session_dictionary.xml`).

**Fires on the bundled files:** no; QuickFIX/J's FIX 4.2 and 4.4 headers already carry all
eight.

### 3.13 `session-message-field` — javac

The same story for the seven admin messages, whose codecs must implement Artio's
`Abstract<Message>Encoder`/`Decoder`:

| Message (msgtype) | Fields Artio's interfaces force |
| --- | --- |
| Heartbeat (`0`) | `TestReqID` |
| TestRequest (`1`) | `TestReqID` |
| ResendRequest (`2`) | `BeginSeqNo`, `EndSeqNo` |
| Reject (`3`) | `RefSeqNum`, `RefTagID`, `SessionRejectReason`, `Text` |
| SequenceReset (`4`) | `GapFillFlag`, `NewSeqNo` |
| Logout (`5`) | `Text` |
| Logon (`A`) | `EncryptMethod`, `HeartBtInt`, `ResetSeqNumFlag` |

Sample failures, again from deleting one field at a time:

```
RejectEncoder is not abstract and does not override abstract method
    sessionRejectReason(int) in AbstractRejectEncoder
RejectEncoder is not abstract and does not override abstract method
    resetRefTagID() in AbstractRejectEncoder
LogonDecoder is not abstract and does not override abstract method
    resetSeqNumFlag() in AbstractLogonDecoder
SequenceResetDecoder is not abstract and does not override abstract method
    gapFillFlag() in AbstractSequenceResetDecoder
HeartbeatEncoder is not abstract and does not override abstract method
    resetTestReqID() in AbstractHeartbeatEncoder
```

Fields Artio guards with a `supportsXxx()` method are **not** in the table, because the
generator emits `supportsUsername() { return false; }` when the field is absent and the
class still compiles. Those are `Username`, `Password`, `CancelOnDisconnectType`,
`CODTimeoutWindow` on Logon and `RefMsgType` on Reject — verified by deleting each and
seeing the build stay green. That tolerance is exactly what makes gotcha 5.1 below
possible.

**Fires on the bundled files:** no.

### 3.14 `session-message-missing` — runtime

An admin message the dictionary never declares does **not** fail the build. The generator
emits:

```java
public AbstractLogonEncoder makeLogonEncoder()
{
    return null;
}
```

The session layer dereferences that at logon. The converter adds the message with its
contract fields, and adds the matching `<value>` to tag 35 when tag 35 is enumerated.

**Fires on the bundled files:** no; both declare all seven.

### 3.15 Things that look like rules and are not

Deliberately *not* changed, because Artio accepts them and a conversion nobody needs is a
conversion that can go wrong:

* **Group counters typed `INT` instead of `NUMINGROUP`.** FIX 4.2 types all 18 of its
  `NoXxx` counters `INT`; FIX 4.4 mixes `NUMINGROUP` and `INT`. `Group.of` builds a
  synthetic `NoXxxGroupCounter` field of type `NUMINGROUP` whatever the declared type is,
  so both generate and compile. The only visible consequence is a naming one:
  `ensureNumInGroupStartsWithNo` renames a `NUMINGROUP` field that does not start with `No`,
  so FIX 4.2's `LinesOfText` counter generates `linesOfTextGroupCounter()` while FIX 4.4's
  generates `noLinesOfTextGroupCounter()`.
* **A `msgtype` with no `<value>` on tag 35.** Compiles and runs; Artio packs the message
  type from the `msgtype` attribute directly. (FIX 4.4 has the reverse: `<value enum="n"/>`
  with no message.)
* **A `<value>` on a `BOOLEAN` field.** The generator skips the enum class entirely.
* **Multi-character `enum` values on a `CHAR` field.** `correctMultiCharacterCharEnums`
  retypes the field to `STRING` before generation.
* **A missing `type` attribute on `<fix>`.** Defaults to `FIX`.
* **A missing `required` attribute.** Read as `N`, by Artio and by this reader.
* **Nested groups.** Generate and compile fine at any depth QuickFIX/J produces.

One near-miss in that list deserves its own line, because it is a *silent* change of
meaning rather than a rejection. QuickFIX/J compares `required` **case-insensitively**, so
`required="y"` means required — but a reader that tests for the literal `"Y"` sees "not
required", the writer then emits `N`, and a required field has quietly become optional in
a dictionary that parses, generates and compiles perfectly. `QuickFixDictionaryReader`
reads it case-insensitively and the writer normalises to `Y`/`N`. It produces **no
`ConversionNote`**: nothing about the dictionary's meaning changed, only its spelling, and
a note for every `y` in a venue file would bury the notes that matter.

---

## 4. Build-time generation

```
  fix-dictionary/src/main/resources/quickfixj/{FIX42,FIX44}.xml
        │
        │  :fix-dictionary:convertDictionaries   (JavaExec, ConvertDictionary --batch)
        │      read -> convert -> ArtioDictionaryCheck (Artio's DictionaryParser) -> write
        ▼
  fix-dictionary/build/artio-dictionaries/{FIX42,FIX44}.xml
        │
        │  published as a Gradle artifact on configuration `artioDictionaryElements`
        │  (attribute Usage = "artio-dictionary"); :fix-codecs resolves it, so no path
        │  crosses a module boundary
        ▼
  :fix-codecs:generateFix42Codecs   CodecGenerationTool  -Dfix.codecs.parent_package=com.demo.artio.fix42
  :fix-codecs:generateFix44Codecs   CodecGenerationTool  -Dfix.codecs.parent_package=com.demo.artio.fix44
        ▼
  fix-codecs/build/generated/sources/artio/{fix42,fix44}   (both in the main source set)
        ▼
  :fix-codecs:compileJava
```

`ArtioDictionaryCheck` runs *before* the file is written, so a dictionary Artio would refuse
fails `convertDictionaries` with Artio's own reason rather than half-generating codecs.

Every task declares its inputs (dictionary file, parent package, generator classpath) and
its output directory, and both generation tasks are `outputs.cacheIf { true }`. A build with
nothing changed is up to date; a build-cache hit skips generation entirely.

The generation task also has to pass:

```
--add-opens   java.base/jdk.internal.misc=ALL-UNNAMED
--add-exports java.base/jdk.internal.misc=ALL-UNNAMED
```

`CodecGenerationTool` builds a `MutableAsciiBuffer` while generating, agrona 2.x reaches
`jdk.internal.misc.Unsafe`, and without the flags the generator dies with
`IllegalAccessError: class org.agrona.UnsafeApi ... cannot access class jdk.internal.misc.Unsafe`
after writing a partial tree. The same flags are needed by **any** JVM that instantiates a
codec, so the engine, the bridge and the Spring Boot application all need them.

### Cost

Measured on Corretto 21, Apple Silicon:

| | FIX 4.2 | FIX 4.4 |
| --- | --- | --- |
| dictionary -> Artio XML | <0.1 s (both, one JVM: ~1 s including JVM start) | |
| `CodecGenerationTool` | 1.4 s | 2.3 s |
| generated `.java` files | 177 | 467 |
| generated lines | ~290 000 | ~1 300 000 |
| compiled `.class` files | 291 | 1163 |

Cold `:fix-codecs:compileJava` end to end: ~13 s. Cold
`:fix-dictionary:build :fix-codecs:build --no-build-cache` from `clean`, both test suites
included: ~15 s. Warm: ~1 s. From the build cache (`generateFix42Codecs FROM-CACHE`): ~4 s.

`fix.codecs.flyweight=true` was measured too: it generates 226 files for FIX 4.2 instead of
177 and compiles clean, but flyweight codecs decode lazily and this project reads whole
messages, so it stays off.

### Generated layout

```
com.demo.artio.fix42
├── FixDictionaryImpl          implements uk.co.real_logic.artio.dictionary.FixDictionary
├── Constants                  every tag as a constant, plus packed message types
├── Side, OrdType, ExecType…   one enum per enumerated field
├── builder/                   *Encoder, HeaderEncoder, TrailerEncoder
└── decoder/                   *Decoder, HeaderDecoder, TrailerDecoder
```

`com.demo.artio.fix44` is the same shape.

### Lint

The root build sets `-Xlint:all`. Artio's generated decoders construct nested group
flyweights in their constructors, which trips `[this-escape]` ~100 times for FIX 4.4 alone
(javac's warning cap, so real warnings would be hidden behind it). `:fix-codecs:compileJava`
therefore drops the inherited `-Xlint` argument and compiles `-Xlint:none`; no other module
is affected. There are **no** compile *errors* from either generated tree.

---

## 4a. Worked example: a venue dictionary that breaks eight rules

To show the rules compose, take QuickFIX/J's `FIX42.xml` and break it the way a venue's
file usually is: delete `SenderSubID` and `LastMsgSeqNumProcessed` from the header, delete
`SessionRejectReason` from `Reject`, delete the whole `TestRequest` message, add a field
with a made-up type, add a field whose three enum descriptions collide and include a Java
keyword, and add a message carrying `RawData` with no length field beside it.

Artio's parser on the broken file:

```
IllegalArgumentException: No enum constant uk.co.real_logic.artio.dictionary.ir.Field.Type.NUMBER
```

— it stops at the first problem and never mentions the other seven.
`./gradlew :fix-dictionary:convertDictionary --args="venue-fix42.xml venue-fix42-artio.xml"`
finds all of them in one pass:

```
FIX.4.2  messages=47 components=0 fields=405
10 change(s):
[data-field-length] x1
    message VenueBlob (35=U1): (absent) -> <field name="RawDataLength" required="N"/> before RawData
[enum-description-dedup] x1
    field VenueFlavour (9002) value enum="2": A_B -> A_B_2
[enum-description-java-keyword] x1
    field VenueFlavour (9002) value enum="3": new -> new_
[enum-description-java-name] x1
    field VenueFlavour (9002) value enum="1": A-B -> A_B
[field-type-alias] x1
    field MDEntryDate (272): UTCDATE -> UTCDATEONLY
[session-header-field] x2
    header: (absent) -> <field name="SenderSubID" required="N"/>
    header: (absent) -> <field name="LastMsgSeqNumProcessed" required="N"/>
[session-message-field] x1
    message Reject (35=3): (absent) -> <field name="SessionRejectReason" required="N"/>
[session-message-missing] x1
    messages: (absent) -> <message name="TestRequest" msgtype="1" msgcat="admin"/>
[unknown-field-type] x1
    field VenueWeird (9001): NUMBER -> STRING
```

The repaired dictionary generates 180 Java files and compiles with zero errors, and
converting it again produces no notes and byte-identical output.
`ArtioDictionaryCheckTest.aFix42DictionaryBrokenInEightWaysIsRepairedInOnePassAndAcceptedByArtio`
is this example as a test.

---

## 5. Gotchas

### 5.1 A FIX 4.2 session needs the generated dictionary

Artio bundles `artio-session-codecs`, and those codecs are **FIX 4.4**. An Artio session
validates the counterparty's `BeginString` against the `FixDictionary` it was handed, so a
FIX 4.2 counterparty needs `com.demo.artio.fix42.FixDictionaryImpl`
(`beginString()` = `FIX.4.2`), generated here. This is why dictionary conversion is a
first-phase module and not an extra.

### 5.2 A FIX 4.2 Logon cannot carry credentials

QuickFIX/J's FIX 4.2 `Logon` predates `Username(553)`/`Password(554)`:

```xml
<message name="Logon" msgtype="A" msgcat="admin">
  <field name="EncryptMethod" required="Y"/>
  <field name="HeartBtInt" required="Y"/>
  <field name="RawDataLength" required="N"/>
  <field name="RawData" required="N"/>
  <field name="ResetSeqNumFlag" required="N"/>
  <field name="MaxMessageSize" required="N"/>
  <group name="NoMsgTypes" required="N">…</group>
</message>
```

Artio's generator emits `supportsUsername() { return false; }`, the codec compiles, and
credentials configured on a FIX 4.2 session are **silently not sent**. FIX 4.4's Logon
declares both, so `supportsUsername()` is true there.
`Fix42CodecTest.fix42LogonCannotCarryCredentialsBecauseTheDictionaryHasNoUsernameField`
pins this. If a FIX 4.2 counterparty needs credentials, add `Username` and `Password` to
that message in the source dictionary — the converter will not do it, because Artio does
not need it.

The same applies to `NextExpectedMsgSeqNum(789)`: FIX 4.4 has it, FIX 4.2 does not, so
next-expected-sequence-number logon is a 4.4-only feature here.

### 5.3 The encoder API is not flat in FIX 4.4

FIX 4.4 keeps `Symbol` in the `Instrument` component and `OrderQty` in `OrderQtyData`, so
the generated **encoder** exposes them through component encoders:

```java
encoder.instrument().symbol("VOD.L");
encoder.orderQtyData().orderQty(new DecimalFloat(250, 0));
```

FIX 4.2 has no components at all, so its encoder is flat: `encoder.symbol("MSFT")`. The
**decoders** implement the component interfaces, so `decoder.symbolAsString()` works on
both.

### 5.4 `CodecGenerationTool` exits, it does not throw

Its `main` catches `Throwable`, prints the stack trace and calls `System.exit(-1)`, which
Gradle turns into a task failure — but the message you get is a raw stack trace with no
mention of which dictionary. That is why `convertDictionaries` runs Artio's parser itself
first.

### 5.5 The generator appends

`CodecGenerationTool` writes into an existing directory without clearing it, so a message
removed from a dictionary would leave a stale codec behind. The generation tasks delete
their output directory in `doFirst`.

### 5.6 Field counts differ between this model and Artio's

`:fix-dictionary` reports 403 fields for FIX 4.2; Artio's parser reports 421. The
difference is the 18 synthetic `NoXxxGroupCounter` fields `Group.of` creates. Likewise 916
vs 974 for FIX 4.4. Neither number is wrong; they count different things.

---

## 6. Adding another dictionary

1. Put the QuickFIX/J XML in `fix-dictionary/src/main/resources/quickfixj/` and record its
   provenance in the `NOTICE.md` beside it.
2. Add the input/output pair to `convertDictionaries` in `fix-dictionary/build.gradle.kts`.
3. Add a `registerCodecGeneration(...)` call in `fix-codecs/build.gradle.kts` with a new
   parent package, and add its output to the main source set.
4. `./gradlew :fix-dictionary:convertDictionaries` and read the notes: each one is
   something Artio would have refused.
5. If `ArtioDictionaryCheck` fails, the message is Artio's own. Add or widen a rule in
   `ArtioDictionaryConverter` — with a `RULE_` constant, a `WHY_` string quoting the
   failure, a test in `ArtioDictionaryCheckTest` that shows Artio rejecting the
   unconverted fixture, and a section here.

A FIXT/FIX 5.x pair needs `CodecGenerationTool`'s two-file argument
(`<fixt-xml>;<app-xml>`) and `DictionaryParser.parse(in, fixtDictionary)`; neither is wired
up yet.

### 6.1 The CLI's exit codes

`ConvertDictionary.run(String[] args)` **returns** the exit code; `main` is a wrapper that
calls `System.exit` with it. That split is not decoration — a CLI that exits inside a test
kills the Gradle test worker, so every CLI test in this module drives `run`.

| Code | Means | Where the detail goes |
| --- | --- | --- |
| `0` | every pair converted; the notes are on stdout | stdout |
| `1` (`EXIT_FAILED`) | the input could not be read, the conversion threw, or `ArtioDictionaryCheck` rejected the result | stderr, with **Artio's own message** verbatim, plus the stack trace through SLF4J |
| `2` (`EXIT_BAD_ARGUMENTS`) | the arguments are not input/output pairs: none, or an odd number after `--batch` | stderr, followed by the two usage lines |

The distinction between 1 and 2 is the one that matters in a build: a `2` is the caller's
fault, a `1` is the dictionary's. `--batch` converts its pairs **in order and stops at the
first failure**, so the pairs before the failing one have already been written — do not
read a partially populated output directory as a success.

---

## 7. Reference

* Artio 0.168 sources used for every quote above:
  `uk/co/real_logic/artio/dictionary/DictionaryParser.java`,
  `.../ir/Field.java`, `.../ir/Group.java`, `.../ir/Aggregate.java`,
  `.../CodecGenerationTool.java`, `.../generation/CodecConfiguration.java`,
  `uk/co/real_logic/artio/builder/*`, `uk/co/real_logic/artio/decoder/*`
  (`artio-codecs-0.168-sources.jar`, Maven Central).
* `CodecGenerationTool` system properties: `fix.codecs.parent_package`,
  `fix.codecs.flyweight`, `fix.codecs.wrap_empty_buffer`, `fix.codecs.tags_in_javadoc`,
  `fix.codecs.allow_duplicate_fields`, `fix.codecs.float_overflow_handler`,
  `reject.unknown.enum.value`.
* Artio's reference dictionary:
  `artio-session-codecs/src/main/resources/session_dictionary.xml`.
