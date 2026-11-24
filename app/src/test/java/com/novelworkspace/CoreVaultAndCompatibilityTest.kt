package com.novelworkspace

import com.novelworkspace.core.markdown.MarkdownEngine
import com.novelworkspace.core.security.VaultConfigStore
import com.novelworkspace.core.security.VaultConfiguration
import com.novelworkspace.core.vault.InMemoryVaultGateway
import com.novelworkspace.core.vault.VaultPathValidator
import com.novelworkspace.core.vault.WriteResult
import com.novelworkspace.dashboard.CustomPageManager
import com.novelworkspace.dashboard.DataviewImporter
import com.novelworkspace.data.models.CustomPageManifest
import com.novelworkspace.data.models.PathAliasResolver
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CoreVaultAndCompatibilityTest {

    private fun loadSyntheticVault(): InMemoryVaultGateway {
        val url = javaClass.classLoader!!.getResource("synthetic-vault")!!
        val root = File(url.toURI())
        val map = linkedMapOf<String, ByteArray>()
        root.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(root).path.replace('\\', '/')
            map[rel] = f.readBytes()
        }
        return InMemoryVaultGateway(map)
    }

    @Test
    fun pathValidatorBlocksTraversal() {
        assertEquals("Sub-js/marc_prowess.js", VaultPathValidator.normalize("/Sub-js\\marc_prowess.js"))
        var threw = false
        try {
            VaultPathValidator.normalize("../../etc/passwd")
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun markdownEngineParsesFrontmatterTagsAndWikilinks() = runTest {
        val gw = loadSyntheticVault()
        val aria = gw.readText("Characters/Aria Vance.md")!!
        val parsed = MarkdownEngine.parse(aria.text, aria.relativePath)

        assertEquals("Aria Vance", parsed.frontmatter["name"])
        assertEquals("preserved_field", parsed.frontmatter["custom_author_note"])
        assertTrue(parsed.tags.contains("character"))
        assertTrue(parsed.wikilinks.any { it.target == "Ironhaven" })
        assertTrue(parsed.wikilinks.any { it.target == "Iron Carapace" })
        assertTrue(parsed.headings.any { it.text == "Abilities" })
    }

    @Test
    fun surgicalFrontmatterUpdatePreservesUnknownFieldsAndBody() = runTest {
        val gw = loadSyntheticVault()
        val aria = gw.readText("Characters/Aria Vance.md")!!
        val updated = MarkdownEngine.updateFrontmatterPreservingBody(aria.text) { map ->
            map["rank"] = "D-"
            map["rank_value"] = 51
        }
        val reparsed = MarkdownEngine.parse(updated, aria.relativePath)
        assertEquals("D-", reparsed.frontmatter["rank"])
        assertEquals(51, reparsed.frontmatter["rank_value"])
        assertEquals("preserved_field", reparsed.frontmatter["custom_author_note"])
        assertTrue(reparsed.body.contains("A vanguard scout operating out of [[Ironhaven]]"))
    }

    @Test
    fun malformedYamlIsReportedGracefully() {
        val bad = "---\nrank: [unclosed\n---\n# Body still readable"
        val parsed = MarkdownEngine.parse(bad, "Traits/Broken.md")
        assertNotNull(parsed.parseError)
        assertTrue(parsed.parseError!!.contains("Traits/Broken.md"))
        assertTrue(parsed.body.contains("Body still readable"))
    }

    @Test
    fun conflictDetectionPreventsSilentOverwrite() = runTest {
        val gw = loadSyntheticVault()
        val original = gw.readText("00 System Hybrid.md")!!
        // External Obsidian edit happens
        gw.writeTextForce("00 System Hybrid.md", original.text.replace("Synchronized", "Overcharged"))
        // App tries to save using stale base hash
        val writeRes = gw.writeTextSafe(
            "00 System Hybrid.md",
            original.text.replace("Synchronized", "AppEdit"),
            expectedBaseSha256 = original.sha256
        )
        assertTrue(writeRes is WriteResult.Conflict)
        val conflict = writeRes as WriteResult.Conflict
        assertTrue(conflict.externalContent.contains("Overcharged"))
        assertTrue(conflict.pendingContent.contains("AppEdit"))
    }

    @Test
    fun folderMappingAndPathAliasesResolveScriptsAndJson() = runTest {
        val gw = loadSyntheticVault()
        val aliases = VaultConfiguration().pathAliases
        val scriptCandidates = PathAliasResolver.resolveCandidates("Meta/Scripts/sample_helper", aliases)
        assertTrue(scriptCandidates.any { gw.exists(it) })

        val jsonCandidates = PathAliasResolver.resolveCandidates("config/world-config.json", aliases)
        assertTrue(jsonCandidates.any { gw.exists(it) })
    }

    @Test
    fun dataviewImporterExtractsCodeAndDetectsDependencies() = runTest {
        val gw = loadSyntheticVault()
        val cfg = VaultConfiguration()
        val sampleMd = gw.readText("Dashboards/Sample Hud.md")!!.text
        val imported = DataviewImporter.extractFromMarkdown(sampleMd, "Sample Hud.md")
        val resolved = DataviewImporter.resolveWithVault(imported.dependencyReport, gw, cfg)

        assertTrue(imported.javascript.contains("dv.pages"))
        assertTrue(resolved.sourceFolders.contains("000 A System/Traits"))
        assertTrue(resolved.jsModules.contains("Meta/Scripts/sample_helper"))
        assertTrue(resolved.unresolvedDependencies.isEmpty())

        val netScan = DataviewImporter.scanDependencies("await requestUrl({ url: 'https://api.groq.com/openai/v1/chat/completions' });")
        assertTrue(netScan.requiresExternalNetwork)
        assertTrue(netScan.detectedExternalHosts.contains("api.groq.com"))
    }

    @Test
    fun customPageLifecycleSnapshotAndRestore() = runTest {
        val gw = loadSyntheticVault()
        val mgr = CustomPageManager(gw, "Novel Workspace/Pages")
        val manifest = CustomPageManifest(
            id = "sample_hud",
            title = "Sample HUD",
            folderPath = "",
            sourceFolders = listOf("000 A System/Traits")
        )
        val saved = mgr.savePage(manifest, "dv.el('p', 'v1');", ".v1 {}", saveSnapshot = false)
        assertTrue(mgr.listPages().any { it.id == "sample_hud" })

        mgr.savePage(saved, "dv.el('p', 'v2');", ".v2 {}", saveSnapshot = true)
        val snaps = mgr.listSnapshots(saved)
        assertEquals(1, snaps.size)

        val restored = mgr.restoreSnapshot(saved, snaps.first().second)!!
        assertEquals("dv.el('p', 'v1');", restored.first)

        val dup = mgr.duplicatePage(saved, "Sample HUD Copy")
        assertEquals(2, mgr.listPages().size)
        assertTrue(mgr.deletePage(dup))
        assertEquals(1, mgr.listPages().size)
    }

    @Test
    fun configBackupSerializationRoundTrips() {
        val cfg = VaultConfiguration(
            includedFolders = setOf("Characters", "000 A System/Traits"),
            pathAliases = mapOf("Meta/Scripts/" to "Sub-js/"),
            favouriteFolders = setOf("World")
        )
        val json = VaultConfigStore.toJson(cfg)
        val back = VaultConfigStore.fromJson(json)
        assertEquals(cfg.includedFolders, back.includedFolders)
        assertEquals(cfg.pathAliases, back.pathAliases)
        assertEquals(cfg.favouriteFolders, back.favouriteFolders)
        assertFalse(json.contains("api_key"))
    }
}
