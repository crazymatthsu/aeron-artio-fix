# fix-codecs

Artio encoders, decoders and `FixDictionaryImpl` classes for **FIX 4.2** and **FIX 4.4**,
generated at build time from the dictionaries `:fix-dictionary` converts. There is no
hand-written main source in this module; everything under `build/generated/sources/artio/`
is produced by Artio's `CodecGenerationTool`.

## Why this module exists

Artio ships `artio-session-codecs`, but those codecs are **FIX 4.4**. An Artio session
checks the counterparty's `BeginString` against the `FixDictionary` it was given, so a
FIX 4.2 counterparty needs a dictionary generated from a FIX 4.2 XML. That is what
`com.demo.artio.fix42.FixDictionaryImpl` is for.

## What the engine module needs to know

| Thing | Fully qualified name |
| --- | --- |
| FIX 4.2 dictionary (`implements uk.co.real_logic.artio.dictionary.FixDictionary`, `beginString()` = `FIX.4.2`) | `com.demo.artio.fix42.FixDictionaryImpl` |
| FIX 4.4 dictionary (`beginString()` = `FIX.4.4`) | `com.demo.artio.fix44.FixDictionaryImpl` |
| FIX 4.2 encoders | `com.demo.artio.fix42.builder.*` e.g. `NewOrderSingleEncoder`, `LogonEncoder`, `HeaderEncoder` |
| FIX 4.2 decoders | `com.demo.artio.fix42.decoder.*` e.g. `NewOrderSingleDecoder`, `LogonDecoder`, `HeaderDecoder` |
| FIX 4.2 field enums, tag constants | `com.demo.artio.fix42.*` e.g. `Side`, `OrdType`, `Constants` |
| FIX 4.4 equivalents | `com.demo.artio.fix44`, `com.demo.artio.fix44.builder`, `com.demo.artio.fix44.decoder` |

Artio's `EngineConfiguration`/`LibraryConfiguration` take a `Class<? extends FixDictionary>`,
so the engine passes `com.demo.artio.fix42.FixDictionaryImpl.class`.

**Every JVM that instantiates a codec needs these flags.** Artio's codecs allocate an agrona
`UnsafeBuffer` in their constructors and agrona 2.x reaches `jdk.internal.misc.Unsafe`:

```
--add-opens   java.base/jdk.internal.misc=ALL-UNNAMED
--add-exports java.base/jdk.internal.misc=ALL-UNNAMED
```

Without them the first `new NewOrderSingleEncoder()` throws
`IllegalAccessError: class org.agrona.UnsafeApi ... cannot access class jdk.internal.misc.Unsafe`.
This module sets them on its `Test` tasks and on the generation tasks; every downstream
module that runs Artio has to set them too. Two, not three: nothing here launches a
`FixEngine`, so `--add-opens java.base/sun.nio.ch=ALL-UNNAMED` is not needed until
`:artio-engine` — see [its README](../artio-engine/README.md#jvm-flags--all-three-mandatory).

## Building

```bash
./gradlew :fix-codecs:build
```

The chain is:

```
:fix-dictionary:convertDictionaries      (QuickFIX/J XML -> Artio XML, build/artio-dictionaries)
        │  consumed as a Gradle configuration, attribute Usage="artio-dictionary"
        ▼
:fix-codecs:generateFix42Codecs          CodecGenerationTool -> build/generated/sources/artio/fix42
:fix-codecs:generateFix44Codecs          CodecGenerationTool -> build/generated/sources/artio/fix44
        ▼
:fix-codecs:compileJava                  both directories are in the main source set
```

Both generation tasks declare their dictionary, parent package and generator classpath as
inputs and their output directory as an output, and are marked cacheable, so a build with
nothing changed is up to date in about a second.

They are also **relocatable**: the dictionary input is declared `PathSensitivity.NONE` (the
generated code depends on the file's contents, never on where it sits), the generator
classpath uses Gradle's classpath normaliser, and each `fix.codecs.*` system property is a
declared input in its own right. Without those three, the cache key embedded an absolute
path and every checkout — a second clone, a CI workspace — regenerated a million lines it
already had. With them, a fresh checkout goes straight to `FROM-CACHE`; a changed
`fix.codecs.*` property still invalidates, which is the point of declaring them rather than
dropping them.

Measured on this machine (Corretto 21, Apple Silicon):

| | FIX 4.2 | FIX 4.4 |
| --- | --- | --- |
| generation | 1.4 s | 2.3 s |
| generated `.java` files | 177 | 467 |
| generated lines | ~290 k | ~1.30 M |
| compiled `.class` files | 291 | 1163 |

Cold `:fix-codecs:compileJava` (convert + generate + compile, no cache): ~13 s.
Cold `./gradlew :fix-dictionary:build :fix-codecs:build --no-build-cache` from `clean`,
including both test suites: ~15 s. Warm, nothing changed: ~1 s; from the build cache, ~4 s.

### Generator settings

Set as system properties on the `JavaExec` tasks:

| Property | Value | Why |
| --- | --- | --- |
| `fix.codecs.parent_package` | `com.demo.artio.fix42` / `...fix44` | keeps the two versions apart |
| `fix.codecs.flyweight` | `false` | flyweight codecs decode lazily and generate ~25% more classes; the bridge reads whole messages |
| `fix.codecs.wrap_empty_buffer` | `false` | Artio's default |
| `fix.codecs.tags_in_javadoc` | `true` | the generated javadoc names the tag number, which makes the codecs navigable from a raw FIX log |

### Lint

The root build sets `-Xlint:all`. Artio's generated decoders build their nested group
flyweights in constructors, which trips `[this-escape]` about 100 times in FIX 4.4 alone --
javac's warning cap, so it also hides anything real. This module therefore removes the
inherited `-Xlint` argument and compiles with `-Xlint:none`, **scoped to
`:fix-codecs:compileJava` only**. Nothing else in the build loses lint coverage. Javadoc is
disabled here for the same reason: a million lines of generated source.

## Tests

```bash
./gradlew :fix-codecs:test
```

12 tests. For each version: encode a `NewOrderSingle` with the generated encoder, decode it
with the generated decoder and check `ClOrdID`, `Symbol`, `Side`, `OrderQty` (and `Price`
for 4.2); check the bytes are well-formed FIX starting `8=FIX.4.x`; check
`FixDictionaryImpl.beginString()`; check it implements `uk.co.real_logic.artio.dictionary.FixDictionary`
and that every `make*Encoder`/`make*Decoder` the session layer calls returns non-null.

Two tests pin a behavioural difference the engine has to live with:

* `Fix42CodecTest.fix42LogonCannotCarryCredentialsBecauseTheDictionaryHasNoUsernameField` --
  QuickFIX/J's FIX 4.2 `Logon` predates `Username(553)`/`Password(554)`, so
  `supportsUsername()` is **false**. Credentials configured on a FIX 4.2 session are
  silently not sent.
* `Fix44CodecTest.fix44LogonCanCarryCredentialsUnlikeFix42` -- FIX 4.4 declares both, so
  `supportsUsername()` is true.

Note the API shape difference: FIX 4.4 keeps `Symbol` in the `Instrument` component and
`OrderQty` in `OrderQtyData`, so the **encoder** exposes them as
`encoder.instrument().symbol(...)` and `encoder.orderQtyData().orderQty(...)`, while FIX 4.2
has them flat. The **decoders** implement the component interfaces, so on both versions the
accessor is flat: `decoder.symbolAsString()`.

## Adding another FIX version

See [`fix-dictionary/README.md`](../fix-dictionary/README.md). In this module it is one call:

```kotlin
val generateFix50Codecs = registerCodecGeneration(
    "generateFix50Codecs", "FIX50SP2.xml", "com.demo.artio.fix50", "fix50")

sourceSets.main { java.srcDir(generateFix50Codecs.map { it.outputs.files }) }
tasks.named<JavaCompile>("compileJava") { dependsOn(generateFix50Codecs) }
```
