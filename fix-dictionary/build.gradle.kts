// QuickFIX/J FIX dictionary -> Artio dictionary converter (library + CLI).
// See docs/02-quickfixj-to-artio-dictionary.md.
plugins {
    `java-library`
}

dependencies {
    // Artio's own DictionaryParser is used by ArtioDictionaryCheck so a dictionary
    // that Artio would reject fails here, with the reason, not later in generation.
    api(libs.artio.codecs)
    implementation(libs.slf4j.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
}

// ---------------------------------------------------------------------------
// convertDictionaries: turns the bundled QuickFIX/J dictionaries into Artio
// dictionaries under build/artio-dictionaries. :fix-codecs consumes this output.
// ---------------------------------------------------------------------------
val quickfixjDictionaries: FileCollection =
    layout.projectDirectory.dir("src/main/resources/quickfixj").asFileTree.matching { include("*.xml") }

val artioDictionariesDir: Provider<Directory> = layout.buildDirectory.dir("artio-dictionaries")

// slf4j-simple only for the CLI: the library itself binds nothing, so consumers keep their own.
val cliRuntime: Configuration by configurations.creating
dependencies {
    cliRuntime(libs.slf4j.simple)
}

val convertDictionaries by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Converts the bundled QuickFIX/J FIX42/FIX44 dictionaries into Artio dictionaries."

    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath + cliRuntime
    mainClass.set("com.demo.artio.dictionary.ConvertDictionary")
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "info")

    inputs.files(quickfixjDictionaries).withPropertyName("quickfixjDictionaries")
    inputs.files(sourceSets.main.get().output).withPropertyName("converterClasses")
    outputs.dir(artioDictionariesDir).withPropertyName("artioDictionaries")
    outputs.cacheIf { true }

    val outDir = artioDictionariesDir
    argumentProviders.add(CommandLineArgumentProvider {
        val dir = outDir.get().asFile
        listOf(
            "--batch",
            "src/main/resources/quickfixj/FIX42.xml", "${dir}/FIX42.xml",
            "src/main/resources/quickfixj/FIX44.xml", "${dir}/FIX44.xml"
        )
    })

    doFirst { artioDictionariesDir.get().asFile.mkdirs() }
}

// Ad-hoc conversion of any dictionary:
//   ./gradlew :fix-dictionary:convertDictionary --args="venue.xml build/venue-artio.xml"
tasks.register<JavaExec>("convertDictionary") {
    group = "application"
    description = "Converts one QuickFIX/J dictionary: --args=\"<in.xml> <out.xml>\"."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath + cliRuntime
    mainClass.set("com.demo.artio.dictionary.ConvertDictionary")
}

// Consumable artifact so :fix-codecs can depend on the task output without a path.
val artioDictionaryElements: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, "artio-dictionary"))
    }
}

artifacts {
    add(artioDictionaryElements.name, artioDictionariesDir) {
        builtBy(convertDictionaries)
    }
}

tasks.named("check") {
    dependsOn(convertDictionaries)
}
