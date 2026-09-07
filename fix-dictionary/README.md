# fix-dictionary

Reads a **QuickFIX/J** FIX dictionary, writes an **Artio** dictionary, and explains every
change it made. `:fix-codecs` consumes the output; nothing else in the build depends on
this module at runtime.

The two formats are the same XML schema. The differences are semantic, and each one this
module repairs exists because Artio's `DictionaryParser`, its `CodecGenerationTool`, or
`javac` on the generated codecs fails without it. The full list, with the failure that
motivated it, is in [`docs/02-quickfixj-to-artio-dictionary.md`](../docs/02-quickfixj-to-artio-dictionary.md).

## What is in here

| Path | What it is |
| --- | --- |
| `src/main/resources/quickfixj/FIX42.xml`, `FIX44.xml` | QuickFIX/J's dictionaries, verbatim. Provenance and licence in `NOTICE.md` next to them. |
| `FixDictionary` and friends | A record tree for a dictionary: root attributes, header, trailer, messages, components, fields, enum values, nested groups. |
| `QuickFixDictionaryReader` | JDK DOM parse into that tree. No external XML library, DTDs and external entities disabled. |
| `ArtioDictionaryConverter` | `convert(FixDictionary) -> ConversionResult`: the converted tree plus a `ConversionNote` per change (rule, element, before, after, why). |
| `ArtioDictionaryWriter` | Renders the tree back to XML, deterministically, so converting twice gives byte-identical output. |
| `ArtioDictionaryCheck` | Parses a candidate with **Artio's own** `DictionaryParser` and throws with Artio's reason if it is rejected. |
| `SessionContract` | The header and admin-message fields Artio's session codecs require. |
| `ArtioFieldTypes` | Artio's `Field.Type` constants and the four legacy QuickFIX/J spellings that map onto them. |
| `ConvertDictionary` | The CLI. |

## Running it

### The Gradle task

```bash
./gradlew :fix-dictionary:convertDictionaries
```

Writes `fix-dictionary/build/artio-dictionaries/FIX42.xml` and `FIX44.xml` and prints the
changes. Inputs (the bundled XML plus this module's classes) and outputs are declared, so
the task is incremental and cacheable; `:fix-codecs` consumes the output directory through
a Gradle configuration rather than a path.

Today's output:

```
src/main/resources/quickfixj/FIX42.xml -> build/artio-dictionaries/FIX42.xml
  FIX.4.2  messages=46 components=0 fields=403
  1 change(s):
  [field-type-alias] x1 -- Field.Type.lookup ends in valueOf(), so an unknown type throws ...
      field MDEntryDate (272): UTCDATE -> UTCDATEONLY
src/main/resources/quickfixj/FIX44.xml -> build/artio-dictionaries/FIX44.xml
  FIX.4.4  messages=92 components=24 fields=916
  no changes: the input was already an Artio dictionary
```

QuickFIX/J's stock FIX 4.2 and 4.4 are almost Artio-ready as they ship. The rules matter
for the venue dictionaries you will be handed instead.

### The CLI

```bash
./gradlew :fix-dictionary:convertDictionary \
    --args="/path/to/venue-fix42.xml fix-dictionary/build/venue-fix42-artio.xml"
```

Or directly, once the module is built:

```bash
java -cp "fix-dictionary/build/classes/java/main:$(find ~/.gradle/caches -name 'artio-codecs-0.168.jar' | head -1):$(find ~/.gradle/caches -name 'slf4j-api-2.0.17.jar' | head -1)" \
     com.demo.artio.dictionary.ConvertDictionary venue-fix42.xml build/venue-fix42-artio.xml
```

`ConvertDictionary --batch <in> <out> [<in> <out> ...]` converts several files in one JVM;
that is the form `convertDictionaries` uses.

Exit codes: `0` converted, `1` the input could not be read or Artio rejected the result
(with the reason on stderr), `2` bad arguments.

## Adding another dictionary

1. Drop the QuickFIX/J XML in `src/main/resources/quickfixj/` and note its provenance in
   `NOTICE.md`.
2. Add the pair to the `argumentProviders` block of `convertDictionaries` in
   `build.gradle.kts`:

   ```kotlin
   "src/main/resources/quickfixj/FIX50SP2.xml", "${dir}/FIX50SP2.xml"
   ```

3. Add a `registerCodecGeneration(...)` call in `fix-codecs/build.gradle.kts` for the new
   file and parent package, and add its source directory to the main source set (the
   existing calls show the shape).
4. Run `./gradlew :fix-dictionary:convertDictionaries` and read the notes. A note you do
   not expect means the dictionary carried something Artio would have refused.
5. If Artio rejects the result, `ArtioDictionaryCheck` fails the task with Artio's own
   message; the rule that would have fixed it is either missing or too narrow. Add it to
   `ArtioDictionaryConverter`, give it a `RULE_` constant and a `WHY_` string quoting the
   failure, and document it in `docs/02`.

A FIXT/FIX 5.x pair needs the `CodecGenerationTool` two-file form
(`<fixt-xml>;<app-xml>`); this module does not wire that up yet.

## Tests

```bash
./gradlew :fix-dictionary:test
```

66 tests, no external dependencies. They cover:

* both bundled dictionaries parse, with their message/component/field counts;
* every message, component and field survives conversion, in order;
* conversion is idempotent -- a converted dictionary produces no notes and renders to
  identical bytes;
* Artio's `DictionaryParser` accepts both converted outputs;
* for each rule whose failure the parser can see, Artio *rejects* the unconverted fixture
  with the expected message and *accepts* the converted one
  (`ArtioDictionaryCheckTest`);
* the type table matches `uk.co.real_logic.artio.dictionary.ir.Field.Type` exactly, checked
  by reflection against the jar, so it cannot drift;
* the CLI writes a file and the batch form converts several;
* a FIX 4.2 dictionary broken in eight different ways is repaired in one pass, accepted by
  Artio, and stable on a second pass
  (`ArtioDictionaryCheckTest.aFix42DictionaryBrokenInEightWaysIsRepairedInOnePassAndAcceptedByArtio`).

Rules whose failure only appears when the generated Java is compiled -- the session field
contract, the enum-name collisions -- are checked structurally here and end to end by
`:fix-codecs:compileJava`.
