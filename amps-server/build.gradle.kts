/**
 * amps-server carries no Java: it is the AMPS instance itself - flow configs,
 * the compose file, the container recipe and the lifecycle script. The tasks
 * here are thin wrappers over scripts/amps.sh, so `./gradlew ampsStart` works
 * for people who never leave the build tool, plus one real check that runs on
 * every build.
 */

plugins {
    base
}

val ampsScript = layout.projectDirectory.file("scripts/amps.sh").asFile

fun ampsTask(name: String, argument: String, describe: String) =
    tasks.register<Exec>(name) {
        group = "amps server"
        description = describe
        commandLine(ampsScript.absolutePath, argument)
        // Lifecycle tasks are commands, not build steps: they must run every
        // time and must not be considered up to date.
        outputs.upToDateWhen { false }
        // `status` on a stopped instance exits non-zero; that is information,
        // not a build failure.
        isIgnoreExitValue = name == "ampsStatus"
    }

ampsTask("ampsStart", "start", "Start the AMPS instance in podman and wait until it is ready.")
ampsTask("ampsStop", "stop", "Stop the container, keeping its data.")
ampsTask("ampsDown", "down", "Stop and remove the container, keeping its data.")
ampsTask("ampsRestart", "restart", "Restart on the same data, exercising SOW and journal recovery.")
ampsTask("ampsStatus", "status", "Report whether AMPS is up and on which ports.")
ampsTask("ampsWait", "wait", "Block until AMPS reports initialization completed.")
ampsTask("ampsPrintEnv", "printenv", "Print the resolved AMPS_* values without touching a container.")

tasks.register<Exec>("ampsLogs") {
    group = "amps server"
    description = "Print the AMPS server log."
    commandLine(ampsScript.absolutePath, "logs", "--tail", "200")
    outputs.upToDateWhen { false }
}

/**
 * Parse every flow config as XML, and reject a double hyphen inside a comment
 * before the XML parser has to.
 *
 * These files are three-quarters prose: long comments explaining each topic,
 * full of ranges, flags and asides. An XML comment may not contain `--`, so a
 * casual "35=D -- always carries 11" makes the whole config invalid. AMPS
 * finds out at startup and refuses to boot, which costs a container start and
 * produces a message about an XML parse error rather than a line number.
 *
 * The double-hyphen scan runs FIRST and separately from the parse for exactly
 * that reason: the parser's own complaint for this mistake is
 * "The string '--' is not permitted within comments", with no indication of
 * which of six hundred lines it means. The scan names the file and the line.
 *
 * This is not a substitute for AMPS's own config validation - it knows
 * nothing about AMPS semantics, only about XML - but it needs no container,
 * so it can run on every build.
 */
val checkConfigXml = tasks.register("checkConfigXml") {
    group = "verification"
    description = "Verify every AMPS flow config is well-formed XML with no '--' inside a comment " +
        "and no mention of the startup-complete log line."

    val flowsDir = layout.projectDirectory.dir("config/flows")
    inputs.dir(flowsDir).withPropertyName("flows")
    // No artifact is produced; the check is the output.
    outputs.upToDateWhen { false }

    doLast {
        val configs = flowsDir.asFile.listFiles { file -> file.isDirectory }
            ?.sorted()
            ?.map { File(it, "amps-config.xml") }
            ?.filter { it.isFile }
            .orEmpty()
        require(configs.isNotEmpty()) {
            "no flow configs found under ${flowsDir.asFile}/*/amps-config.xml"
        }

        val failures = mutableListOf<String>()

        configs.forEach { config ->
            failures += doubleHyphensInComments(config)
            failures += readinessMarkerMentions(config)
        }

        // Only parse what survived the scan, so the readable message wins.
        if (failures.isEmpty()) {
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            // These configs have no DTD and must never fetch one.
            factory.setFeature(
                "http://apache.org/xml/features/nonvalidating/load-external-dtd",
                false,
            )
            factory.isXIncludeAware = false
            val builder = factory.newDocumentBuilder()
            configs.forEach { config ->
                try {
                    builder.parse(config)
                } catch (e: Exception) {
                    failures += "${config.parentFile.name}/${config.name}: ${e.message}"
                }
            }
        }

        if (failures.isNotEmpty()) {
            throw GradleException(
                "invalid AMPS config:\n  " + failures.joinToString("\n  "),
            )
        }
        logger.lifecycle(
            "amps-server: ${configs.size} flow config(s) are well-formed XML: " +
                configs.joinToString(", ") { it.parentFile.name },
        )
    }
}

/**
 * The text of the server's startup-complete log line, which no flow config may
 * contain anywhere - comment, attribute or element.
 *
 * `scripts/amps.sh wait` and the test harness decide AMPS is ready when this
 * line appears in the container log, and AMPS echoes the entire config file
 * into that log before it starts listening. A config that so much as mentions
 * the phrase in a comment is therefore "ready" about a second early, and the
 * first client to trust it is dropped mid-logon. Both waiters also require the
 * numeric message code in front of the phrase, but that is a second line of
 * defence, not a licence: the phrase stays out of the config.
 */
val readinessMarkerText = "AMPS initialization completed"

/** Every line of `config` that contains [readinessMarkerText], as "flow/file:line: text". */
fun readinessMarkerMentions(config: File): List<String> {
    val label = "${config.parentFile.name}/${config.name}"
    return config.readLines().withIndex()
        .filter { (_, text) -> text.contains(readinessMarkerText, ignoreCase = true) }
        .map { (index, text) ->
            "$label:${index + 1}: mentions the startup log line '$readinessMarkerText'; AMPS " +
                "echoes the config into its log before it is listening, so amps.sh wait and " +
                "the test harness would see the server as ready too early: ${text.trim()}"
        }
}

/**
 * Every `--` that falls inside an XML comment, as "flow/file:line: text".
 *
 * Walks the raw characters rather than using a regex, because a comment can
 * span lines and `-->` can appear inside a string elsewhere in the document;
 * tracking the comment state is both simpler to read and correct.
 */
fun doubleHyphensInComments(config: File): List<String> {
    val text = config.readText()
    val label = "${config.parentFile.name}/${config.name}"
    val hits = mutableListOf<String>()
    var index = 0
    var line = 1
    var inComment = false
    var commentStartLine = 1
    while (index < text.length) {
        if (text[index] == '\n') {
            line++
        }
        if (!inComment && text.startsWith("<!--", index)) {
            inComment = true
            commentStartLine = line
            index += 4
            continue
        }
        if (inComment) {
            if (text.startsWith("-->", index)) {
                inComment = false
                index += 3
                continue
            }
            if (text.startsWith("--", index)) {
                val lineText = text.lineSequence().elementAtOrNull(line - 1)?.trim().orEmpty()
                hits += "$label:$line: '--' inside the comment opened on line " +
                    "$commentStartLine (XML forbids it, and AMPS refuses the config): $lineText"
                // Skip the pair so "---" reports once rather than twice.
                index += 2
                continue
            }
        }
        index++
    }
    if (inComment) {
        hits += "$label: unterminated comment opened on line $commentStartLine"
    }
    return hits
}

tasks.named("check") {
    dependsOn(checkConfigXml)
}
