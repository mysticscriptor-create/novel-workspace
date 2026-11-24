package com.novelworkspace.core.markdown

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

data class MarkdownHeading(
    val level: Int,
    val text: String
)

data class WikilinkRef(
    val target: String,
    val alias: String? = null,
    val section: String? = null
)

data class ParsedMarkdown(
    val frontmatter: Map<String, Any?>,
    val rawFrontmatter: String?,
    val body: String,
    val headings: List<MarkdownHeading>,
    val tags: List<String>,
    val wikilinks: List<WikilinkRef>,
    val parseError: String? = null
)

object MarkdownEngine {
    private val fmRegex = Regex("^\\uFEFF?---\\r?\\n([\\s\\S]*?)\\r?\\n---(?:\\r?\\n|$)")
    private val headingRegex = Regex("^(#{1,6})\\s+(.+?)\\s*$", RegexOption.MULTILINE)
    private val inlineTagRegex = Regex("(?<![\\w/])#([A-Za-z][A-Za-z0-9_/-]*)")
    private val wikilinkRegex = Regex("\\[\\[([^\\]|#]+)(?:#([^\\]|]+))?(?:\\|([^\\]]+))?]]")
    private val codeFenceRegex = Regex("```[\\s\\S]*?```")

    private fun newYaml(): Yaml {
        val loader = LoaderOptions().apply {
            isAllowDuplicateKeys = true
        }
        val dumper = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
            indent = 2
            indicatorIndent = 0
        }
        return Yaml(SafeConstructor(loader), org.yaml.snakeyaml.representer.Representer(dumper), dumper)
    }

    fun parse(content: String, filePath: String = ""): ParsedMarkdown {
        val match = fmRegex.find(content)
        var fmMap: LinkedHashMap<String, Any?> = linkedMapOf()
        var rawFm: String? = null
        var body = content
        var error: String? = null

        if (match != null) {
            rawFm = match.groupValues[1]
            body = content.substring(match.range.last + 1)
            if (rawFm.isNotBlank()) {
                try {
                    val loaded = newYaml().load<Any?>(rawFm)
                    if (loaded is Map<*, *>) {
                        val map = linkedMapOf<String, Any?>()
                        loaded.forEach { (k, v) ->
                            if (k != null) map[k.toString()] = normalizeYamlValue(v)
                        }
                        fmMap = map
                    } else if (loaded != null) {
                        error = "Frontmatter is not a YAML mapping in $filePath"
                    }
                } catch (e: Exception) {
                    error = "YAML error in $filePath: ${e.message}"
                }
            }
        }

        val bodyWithoutCode = codeFenceRegex.replace(body, "")
        val headings = headingRegex.findAll(bodyWithoutCode).map {
            MarkdownHeading(it.groupValues[1].length, it.groupValues[2].trim())
        }.toList()

        val tagSet = linkedSetOf<String>()
        extractTagsFromAny(fmMap["tags"], tagSet)
        extractTagsFromAny(fmMap["tag"], tagSet)
        inlineTagRegex.findAll(bodyWithoutCode).forEach { m ->
            tagSet.add(m.groupValues[1].trimStart('#'))
        }

        val links = linkedSetOf<WikilinkRef>()
        wikilinkRegex.findAll(content).forEach { m ->
            val target = m.groupValues[1].trim()
            val section = m.groupValues[2].takeIf { it.isNotBlank() }?.trim()
            val alias = m.groupValues[3].takeIf { it.isNotBlank() }?.trim()
            if (target.isNotEmpty()) {
                links.add(WikilinkRef(target, alias, section))
            }
        }

        return ParsedMarkdown(
            frontmatter = fmMap,
            rawFrontmatter = rawFm,
            body = body,
            headings = headings,
            tags = tagSet.toList(),
            wikilinks = links.toList(),
            parseError = error
        )
    }

    private fun normalizeYamlValue(v: Any?): Any? = when (v) {
        is Map<*, *> -> v.entries.associate { (k, valItem) -> k.toString() to normalizeYamlValue(valItem) }
        is List<*> -> v.map { normalizeYamlValue(it) }
        else -> v
    }

    private fun extractTagsFromAny(value: Any?, dest: MutableSet<String>) {
        when (value) {
            is String -> value.split(',', ' ').map { it.trim().trimStart('#') }.filter { it.isNotEmpty() }.forEach(dest::add)
            is List<*> -> value.forEach { extractTagsFromAny(it, dest) }
        }
    }

    /**
     * Updates YAML frontmatter fields while preserving unknown YAML properties and the exact Markdown body.
     */
    fun updateFrontmatterPreservingBody(
        originalContent: String,
        mutator: (MutableMap<String, Any?>) -> Unit
    ): String {
        val parsed = parse(originalContent)
        if (parsed.parseError != null) {
            throw IllegalStateException(parsed.parseError)
        }
        val updated = LinkedHashMap<String, Any?>(parsed.frontmatter)
        mutator(updated)

        if (updated.isEmpty()) {
            return parsed.body
        }
        val serialized = newYaml().dump(updated).trimEnd()
        val lineBreak = if (originalContent.contains("\r\n")) "\r\n" else "\n"
        val cleanBody = parsed.body.let { b ->
            if (b.isEmpty() || b.startsWith("\n") || b.startsWith("\r\n")) b else "$lineBreak$b"
        }
        return "---$lineBreak$serialized$lineBreak---$lineBreak${cleanBody.trimStart('\r', '\n')}"
    }
}
