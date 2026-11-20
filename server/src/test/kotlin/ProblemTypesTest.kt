package ch.nokillswit

import ch.nokillswit.infra.ProblemTypes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The typed problem URNs (`urn:hammurabi:problem:*`, `ProblemDetail.type`) are one registry ([ProblemTypes]): no main source declares a
 * quoted URN outside it, the registry's [ProblemTypes.ALL] is exactly the set its file declares, the spec's `ProblemDetail.type`
 * description lists exactly that set (and no other operation text names a URN outside it), and the SPA's production code names no URN
 * the server does not send. A plain source scan: no Docker, no application boot.
 */
class ProblemTypesTest {
    private val repoRoot = File(System.getProperty("repo.root") ?: "..")
    private val urn = Regex("""urn:hammurabi:problem:[a-z][a-z-]*""")
    private val quotedUrn = Regex(""""urn:hammurabi:problem:[a-z][a-z-]*"""")

    private fun mainSources(): List<File> {
        val root = File(repoRoot, "server/src/main/kotlin")
        assertTrue(root.isDirectory, "${root.absolutePath} not found")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun quoted(file: File): Set<String> = quotedUrn.findAll(file.readText()).map { it.value.trim('"') }.toSet()

    /** The code part of [line]: empty for a comment line, and cut at a trailing `//` that sits outside a string. */
    private fun code(line: String): String {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return ""
        val cut = Regex("//").findAll(line).map { it.range.first }.firstOrNull { line.substring(0, it).count { c -> c == '"' } % 2 == 0 }
        return if (cut == null) line else line.substring(0, cut)
    }

    @Test
    fun `no main source names a problem URN outside the registry`() {
        val sources = mainSources()
        val registry = sources.single { it.name == "ProblemTypes.kt" }
        // Any occurrence in code, not just a whole quoted literal: an interpolated, concatenated or raw-string URN is a stray too.
        val strays = sources.filter { it != registry }.flatMap { file ->
            file.readLines().withIndex().filter { (_, line) -> code(line).contains("urn:hammurabi:problem") }
                .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
        }
        assertEquals(emptyList(), strays, "a typed problem takes its URI from infra/ProblemTypes.kt, never a spelling of its own")
    }

    @Test
    fun `the registry set is exactly the URNs its file declares, and every one is used`() {
        val sources = mainSources()
        val registry = sources.single { it.name == "ProblemTypes.kt" }
        assertEquals(quoted(registry), ProblemTypes.ALL, "ProblemTypes.ALL must list every constant of the registry")
        val others = sources.filter { it != registry }.joinToString("\n") { it.readText() }
        val unused = ProblemTypes::class.java.declaredFields
            .filter { it.type == String::class.java && java.lang.reflect.Modifier.isStatic(it.modifiers) && it.name != "INSTANCE" }
            .map { it.name }
            .filterNot { others.contains("ProblemTypes.$it") }
        assertEquals(emptyList(), unused, "a registered URN no throw site uses is dead")
    }

    @Test
    fun `the spec lists exactly the registry set on ProblemDetail type and names no other URN`() {
        val description = checkNotNull(OpenApiSpec.parsed.components.schemas["ProblemDetail"]?.properties?.get("type")?.description) {
            "ProblemDetail.type has no description"
        }
        val listed = urn.findAll(description).map { it.value }.toSet()
        assertEquals(ProblemTypes.ALL, listed, "ProblemDetail.type must list every registered URN")
        val mentioned = urn.findAll(OpenApiSpec.rawYaml).map { it.value }.toSet()
        assertEquals(emptySet(), mentioned - ProblemTypes.ALL, "the spec names a problem URN the server does not register")
    }

    @Test
    fun `the SPA's production code names only registered URNs`() {
        val root = File(repoRoot, "web/src")
        assertTrue(root.isDirectory, "${root.absolutePath} not found")
        val named = root.walkTopDown()
            .filter { it.isFile && it.extension in setOf("ts", "tsx") && !it.name.contains(".test.") && it.name != "schema.ts" }
            .flatMap { file -> urn.findAll(file.readText()).map { it.value } }
            .toSet()
        assertEquals(emptySet(), named - ProblemTypes.ALL, "web/src names a problem URN the server does not register")
    }
}
