package com.novelworkspace.dashboard

import com.novelworkspace.core.security.VaultConfiguration
import com.novelworkspace.core.vault.VaultGateway
import com.novelworkspace.core.vault.VaultPathValidator
import com.novelworkspace.data.models.CustomPageManifest
import com.novelworkspace.data.models.PathAliasResolver
import org.json.JSONObject

data class DependencyReport(
    val sourceFolders: List<String>,
    val explicitNotes: List<String>,
    val jsModules: List<String>,
    val jsonConfigs: List<String>,
    val cssFiles: List<String>,
    val unresolvedDependencies: List<String>,
    val requiresExternalNetwork: Boolean,
    val detectedExternalHosts: List<String>
)

data class ImportedDataviewPage(
    val suggestedTitle: String,
    val javascript: String,
    val extractedCss: String,
    val dependencyReport: DependencyReport
)

object DataviewImporter {
    private val dvJsBlockRegex = Regex("```dataviewjs\\r?\\n([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
    private val styleBlockRegex = Regex("<style[^>]*>([\\s\\S]*?)</style>", RegexOption.IGNORE_CASE)
    private val dvPagesRegex = Regex("""dv\.pages\s*\(\s*['"]['"]?([^'"]+)['"]?['"]\s*\)""")
    private val dvPageRegex = Regex("""dv\.page\s*\(\s*['"]([^'"]+)['"]\s*\)""")
    private val dvViewRegex = Regex("""dv\.view\s*\(\s*['"]([^'"]+)['"]""")
    private val jsModuleLiteralRegex = Regex("""['"]([^'"]+\.js)['"]""")
    private val jsonLiteralRegex = Regex("""['"]([^'"]+\.json)['"]""")
    private val cssLiteralRegex = Regex("""['"]([^'"]+\.css)['"]""")
    private val urlRegex = Regex("""https?://([A-Za-z0-9.\-:]+)""")

    fun extractFromMarkdown(markdown: String, fallbackName: String): ImportedDataviewPage {
        val jsBlocks = dvJsBlockRegex.findAll(markdown).map { it.groupValues[1].trim() }.toList()
        val combinedJs = if (jsBlocks.isNotEmpty()) jsBlocks.joinToString("\n\n") else ""
        val cssBlocks = styleBlockRegex.findAll(markdown).map { it.groupValues[1].trim() }.toList()
        val combinedCss = cssBlocks.joinToString("\n\n")

        val report = scanDependencies(combinedJs + "\n" + markdown)
        return ImportedDataviewPage(
            suggestedTitle = fallbackName.substringBeforeLast('.'),
            javascript = combinedJs,
            extractedCss = combinedCss,
            dependencyReport = report
        )
    }

    fun scanDependencies(code: String): DependencyReport {
        val folders = linkedSetOf<String>()
        dvPagesRegex.findAll(code).forEach { m ->
            val arg = m.groupValues[1].trim().trim('"', '\'')
            if (arg.isNotEmpty() && !arg.startsWith("#")) folders.add(arg)
        }
        val notes = dvPageRegex.findAll(code).map { it.groupValues[1].trim() }.toCollection(linkedSetOf())
        val modules = linkedSetOf<String>()
        dvViewRegex.findAll(code).forEach { modules.add(it.groupValues[1].trim()) }
        jsModuleLiteralRegex.findAll(code).forEach { modules.add(it.groupValues[1].trim()) }
        val jsons = jsonLiteralRegex.findAll(code).map { it.groupValues[1].trim() }.toCollection(linkedSetOf())
        val css = cssLiteralRegex.findAll(code).map { it.groupValues[1].trim() }.toCollection(linkedSetOf())
        val hosts = urlRegex.findAll(code).map { it.groupValues[1] }
            .filterNot { it == "www.w3.org" || it == "fonts.googleapis.com" }
            .toCollection(linkedSetOf())

        return DependencyReport(
            sourceFolders = folders.toList(),
            explicitNotes = notes.toList(),
            jsModules = modules.toList(),
            jsonConfigs = jsons.toList(),
            cssFiles = css.toList(),
            unresolvedDependencies = emptyList(),
            requiresExternalNetwork = hosts.isNotEmpty() || code.contains("requestUrl"),
            detectedExternalHosts = hosts.toList()
        )
    }

    suspend fun resolveWithVault(
        report: DependencyReport,
        gateway: VaultGateway,
        config: VaultConfiguration
    ): DependencyReport {
        val unresolved = mutableListOf<String>()
        val candidatesToCheck = report.jsModules + report.jsonConfigs + report.explicitNotes + report.cssFiles
        for (dep in candidatesToCheck) {
            if (dep.contains("\${")) continue // dynamic template literal
            val candidates = PathAliasResolver.resolveCandidates(dep, config.pathAliases)
            val found = candidates.any { gateway.exists(it) }
            if (!found) unresolved.add(dep)
        }
        return report.copy(unresolvedDependencies = unresolved.distinct())
    }
}

class CustomPageManager(
    private val gateway: VaultGateway,
    private val pagesRoot: String
) {
    suspend fun listPages(): List<CustomPageManifest> {
        if (!gateway.exists(pagesRoot)) return emptyList()
        val children = gateway.listChildren(pagesRoot).filter { it.isDirectory }
        return children.mapNotNull { dir ->
            val manifestPath = "${dir.relativePath}/manifest.json"
            val raw = gateway.readText(manifestPath)?.text ?: return@mapNotNull null
            runCatching { CustomPageManifest.fromJson(raw, dir.relativePath) }.getOrNull()
        }.sortedBy { it.title.lowercase() }
    }

    suspend fun loadPageFiles(manifest: CustomPageManifest): Pair<String, String> {
        val js = gateway.readText("${manifest.folderPath}/page.js")?.text ?: ""
        val css = gateway.readText("${manifest.folderPath}/style.css")?.text ?: ""
        return js to css
    }

    suspend fun savePage(
        manifest: CustomPageManifest,
        jsCode: String,
        cssCode: String,
        saveSnapshot: Boolean = true
    ): CustomPageManifest {
        val sanitizedFolder = manifest.folderPath.ifBlank {
            val safeName = manifest.title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "Custom Page" }
            "${VaultPathValidator.normalize(pagesRoot)}/$safeName"
        }
        val updated = manifest.copy(folderPath = sanitizedFolder, updatedAtMs = System.currentTimeMillis())
        gateway.createFolder(sanitizedFolder)
        if (saveSnapshot && gateway.exists("$sanitizedFolder/page.js")) {
            val prevJs = gateway.readText("$sanitizedFolder/page.js")?.text ?: ""
            val prevCss = gateway.readText("$sanitizedFolder/style.css")?.text ?: ""
            val snap = JSONObject().apply {
                put("savedAtMs", System.currentTimeMillis())
                put("js", prevJs)
                put("css", prevCss)
            }.toString()
            gateway.writeTextForce("$sanitizedFolder/snapshots/${System.currentTimeMillis()}.json", snap)
        }
        gateway.writeTextForce("$sanitizedFolder/manifest.json", updated.toJson())
        gateway.writeTextForce("$sanitizedFolder/page.js", jsCode)
        gateway.writeTextForce("$sanitizedFolder/style.css", cssCode)
        return updated
    }

    suspend fun listSnapshots(manifest: CustomPageManifest): List<Pair<Long, String>> {
        val dir = "${manifest.folderPath}/snapshots"
        if (!gateway.exists(dir)) return emptyList()
        return gateway.listChildren(dir)
            .filter { !it.isDirectory && it.name.endsWith(".json") }
            .sortedByDescending { it.name }
            .mapNotNull { entry ->
                val ts = entry.name.removeSuffix(".json").toLongOrNull() ?: return@mapNotNull null
                ts to entry.relativePath
            }
    }

    suspend fun restoreSnapshot(manifest: CustomPageManifest, snapshotPath: String): Pair<String, String>? {
        val raw = gateway.readText(snapshotPath)?.text ?: return null
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val js = obj.optString("js", "")
        val css = obj.optString("css", "")
        savePage(manifest, js, css, saveSnapshot = false)
        return js to css
    }

    suspend fun duplicatePage(manifest: CustomPageManifest, newTitle: String): CustomPageManifest {
        val (js, css) = loadPageFiles(manifest)
        val safeName = newTitle.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "${manifest.title} Copy" }
        val newFolder = "${VaultPathValidator.normalize(pagesRoot)}/$safeName"
        val copyManifest = manifest.copy(
            id = safeName.lowercase().replace(' ', '_') + "_" + System.currentTimeMillis(),
            title = newTitle,
            folderPath = newFolder
        )
        return savePage(copyManifest, js, css, saveSnapshot = false)
    }

    suspend fun deletePage(manifest: CustomPageManifest): Boolean =
        gateway.deleteFile(manifest.folderPath)
}
