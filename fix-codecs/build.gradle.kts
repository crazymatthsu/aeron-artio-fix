// Artio codecs for FIX 4.2 and FIX 4.4, generated at build time from the dictionaries
// :fix-dictionary converts. See docs/02-quickfixj-to-artio-dictionary.md.
plugins {
    `java-library`
}

dependencies {
    // Artio's codec runtime: AsciiBuffer, DecimalFloat, Encoder/Decoder, FixDictionary and the
    // Abstract*Encoder/Decoder interfaces the generated session codecs implement. `api` because
    // every consumer of a generated codec touches these types.
    api(libs.artio.codecs)
    // Artio's own FIX 4.4 session dictionary and codecs; the engine falls back to these when no
    // generated dictionary is supplied.
    api(libs.artio.session.codecs)
    // agrona (DirectBuffer, MutableDirectBuffer) arrives transitively through artio-codecs but the
    // generated sources import it directly, so declare it.
    api(libs.agrona)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
}

// ---------------------------------------------------------------------------
// The converted dictionaries, consumed from :fix-dictionary's convertDictionaries
// output through a configuration rather than a hard-coded path.
// ---------------------------------------------------------------------------
val artioDictionaries: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, "artio-dictionary"))
    }
}

// The classpath the generator itself runs on: Artio's CodecGenerationTool plus its dependencies.
val codecGenerator: Configuration by configurations.creating

dependencies {
    artioDictionaries(project(":fix-dictionary"))
    codecGenerator(libs.artio.codecs)
}

val dictionaryDir: Provider<File> = artioDictionaries.elements.map { it.single().asFile }

val generatedRoot: Provider<Directory> = layout.buildDirectory.dir("generated/sources/artio")

/**
 * Registers a codec generation task for one dictionary.
 *
 * CodecGenerationTool reads the dictionary with Artio's DictionaryParser and writes encoders,
 * decoders, field enums, Constants and FixDictionaryImpl under <out>/<parentPackage>/.
 */
fun registerCodecGeneration(
    taskName: String,
    dictionaryFile: String,
    parentPackage: String,
    outputName: String
) = tasks.register<JavaExec>(taskName) {
    group = "build"
    description = "Generates Artio codecs for $parentPackage from $dictionaryFile."

    val outputDir = generatedRoot.map { it.dir(outputName) }

    classpath = codecGenerator
    mainClass.set("uk.co.real_logic.artio.dictionary.CodecGenerationTool")

    // agrona 2.x reaches jdk.internal.misc.Unsafe; without these the generator dies with
    // "class org.agrona.UnsafeApi cannot access class jdk.internal.misc.Unsafe".
    jvmArgs(
        "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED"
    )
    // Everything that changes what the generator emits. Gradle does not track a JavaExec's system
    // properties, so each one is declared as an input as well as set: change a value and the
    // codecs are regenerated rather than restored from a cache entry built with the old one.
    val codecOptions = mapOf(
        "fix.codecs.parent_package" to parentPackage,
        // Flyweight codecs decode lazily; the engine reads whole messages, so the simpler
        // eager codecs are enough and generate ~25% fewer classes.
        "fix.codecs.flyweight" to "false",
        "fix.codecs.wrap_empty_buffer" to "false",
        // Tag numbers in the generated javadoc make the codecs navigable from a raw FIX log.
        "fix.codecs.tags_in_javadoc" to "true"
    )
    codecOptions.forEach { (key, value) ->
        systemProperty(key, value)
        inputs.property(key, value)
    }

    val dir = dictionaryDir
    // NONE, not the default absolute sensitivity: the dictionary arrives from :fix-dictionary's
    // build directory, whose path is different in every checkout and on every CI agent. Only its
    // content decides what the generator writes, so a cache entry from one working copy is a hit
    // in the next.
    inputs.file(dir.map { File(it, dictionaryFile) })
        .withPropertyName("dictionary")
        .withPathSensitivity(PathSensitivity.NONE)
    // Same reason for the generator's own jars: what matters is their content and their order on
    // the classpath, not where Gradle's module cache put them.
    inputs.files(codecGenerator)
        .withPropertyName("generatorClasspath")
        .withNormalizer(ClasspathNormalizer::class)
    outputs.dir(outputDir).withPropertyName("generatedSources")
    outputs.cacheIf { true }

    argumentProviders.add(CommandLineArgumentProvider {
        listOf(outputDir.get().asFile.absolutePath, File(dir.get(), dictionaryFile).absolutePath)
    })

    doFirst {
        // The generator appends; start from a clean tree so a removed message leaves no stale class.
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
    }
}

val generateFix42Codecs = registerCodecGeneration(
    "generateFix42Codecs", "FIX42.xml", "com.demo.artio.fix42", "fix42")
val generateFix44Codecs = registerCodecGeneration(
    "generateFix44Codecs", "FIX44.xml", "com.demo.artio.fix44", "fix44")

sourceSets.main {
    java.srcDir(generateFix42Codecs.map { it.outputs.files })
    java.srcDir(generateFix44Codecs.map { it.outputs.files })
}

tasks.named<JavaCompile>("compileJava") {
    dependsOn(generateFix42Codecs, generateFix44Codecs)
    // The root build sets -Xlint:all. Artio's generated decoders build nested group flyweights in
    // their constructors, which trips [this-escape] ~100 times in FIX 4.4 alone (javac's warning
    // cap). The code is generated, so the warnings are neither actionable nor informative here;
    // scoped to this module only.
    options.compilerArgs.removeIf { it.startsWith("-Xlint") }
    options.compilerArgs.add("-Xlint:none")
    options.isWarnings = false
}

// Generated sources are enormous; javadoc over them is slow and adds nothing.
tasks.named<Javadoc>("javadoc") {
    enabled = false
}

// Any JVM that instantiates an Artio codec needs these: the codecs allocate an agrona
// UnsafeBuffer in their constructors, and agrona 2.x reaches jdk.internal.misc.Unsafe. Without
// them the first `new NewOrderSingleEncoder()` dies with
// "class org.agrona.UnsafeApi cannot access class jdk.internal.misc.Unsafe". Every downstream
// module that runs Artio has to repeat this.
tasks.withType<Test>().configureEach {
    jvmArgs(
        "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED"
    )
}
