package com.novelworkspace.data.repositories

import com.novelworkspace.core.markdown.MarkdownEngine
import com.novelworkspace.core.security.VaultConfiguration
import com.novelworkspace.core.sync.VaultSyncEngine
import com.novelworkspace.core.vault.VaultGateway
import com.novelworkspace.core.vault.VaultPathValidator
import com.novelworkspace.core.vault.VaultReadResult
import com.novelworkspace.core.vault.WriteResult
import com.novelworkspace.data.database.RecentAccessEntity
import com.novelworkspace.data.database.SyncErrorEntity
import com.novelworkspace.data.database.VaultDao
import com.novelworkspace.data.database.VaultFileEntity
import com.novelworkspace.data.models.ChapterRecord
import com.novelworkspace.data.models.CharacterRecord
import com.novelworkspace.data.models.SearchFilterScope
import com.novelworkspace.data.models.SearchHit
import com.novelworkspace.data.models.TraitRecord
import com.novelworkspace.data.models.VaultFileKind
import com.novelworkspace.data.models.WorldRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

class NovelRepository(
    private val dao: VaultDao,
    private val syncEngine: VaultSyncEngine
) {
    fun observeAllFiles(): Flow<List<VaultFileEntity>> = dao.observeAllFiles()
    fun observeRecentAccess(): Flow<List<RecentAccessEntity>> = dao.observeRecentAccess()
    fun observeRecentlyModified(): Flow<List<VaultFileEntity>> = dao.observeRecentlyModified()
    fun observeErrors(): Flow<List<SyncErrorEntity>> = dao.observeErrors()

    suspend fun recordAccess(path: String, title: String) {
        dao.recordAccess(RecentAccessEntity(path, title, System.currentTimeMillis()))
    }

    fun observeCharacters(configFlow: Flow<VaultConfiguration>): Flow<List<CharacterRecord>> =
        combine(dao.observeByKind(VaultFileKind.MARKDOWN.name), dao.observeAllTags(), configFlow) { files, tags, cfg ->
            val tagsByFile = tags.groupBy({ it.filePath }, { it.tag.lowercase() })
            files.filter { f ->
                val fm = safeJson(f.frontmatterJson)
                val fTags = tagsByFile[f.relativePath].orEmpty()
                val inFolder = cfg.characterFolders.any { folder ->
                    val norm = VaultPathValidator.normalize(folder)
                    norm.isNotEmpty() && (f.folderPath == norm || f.folderPath.startsWith("$norm/"))
                }
                val typeStr = fm.optString("type", "").lowercase()
                inFolder || typeStr == "character" || fTags.contains("character")
            }.map { toCharacterRecord(it) }.sortedBy { it.name.lowercase() }
        }

    fun observeTraits(configFlow: Flow<VaultConfiguration>): Flow<List<TraitRecord>> =
        combine(dao.observeByKind(VaultFileKind.MARKDOWN.name), dao.observeAllTags(), configFlow) { files, tags, cfg ->
            val tagsByFile = tags.groupBy({ it.filePath }, { it.tag.lowercase() })
            files.filter { f ->
                val fm = safeJson(f.frontmatterJson)
                val fTags = tagsByFile[f.relativePath].orEmpty()
                val inFolder = cfg.traitFolders.any { folder ->
                    val norm = VaultPathValidator.normalize(folder)
                    norm.isNotEmpty() && (f.folderPath == norm || f.folderPath.startsWith("$norm/"))
                }
                val hasTraitProps = fm.has("rank_value") || fm.has("layer") || fm.has("stat_power") || fm.has("boost_power")
                inFolder || (fTags.contains("trait") && hasTraitProps) || (fm.optString("type").lowercase() == "trait")
            }.map { toTraitRecord(it) }.sortedByDescending { it.rankValue }
        }

    fun observeWorldRecords(configFlow: Flow<VaultConfiguration>): Flow<List<WorldRecord>> =
        combine(dao.observeByKind(VaultFileKind.MARKDOWN.name), dao.observeAllTags(), configFlow) { files, tags, cfg ->
            val tagsByFile = tags.groupBy({ it.filePath }, { it.tag.lowercase() })
            val allLinks = dao.getAllLinks()
            val outByPath = allLinks.groupBy({ it.sourcePath }, { it.targetName })
            val inByName = allLinks.groupBy({ it.targetName.lowercase() }, { it.sourcePath })
            files.filter { f ->
                val fm = safeJson(f.frontmatterJson)
                val fTags = tagsByFile[f.relativePath].orEmpty()
                val inFolder = cfg.worldFolders.any { folder ->
                    val norm = VaultPathValidator.normalize(folder)
                    norm.isNotEmpty() && (f.folderPath == norm || f.folderPath.startsWith("$norm/"))
                }
                val hasWorldCategory = fm.has("category") || fTags.any { it.startsWith("world/") }
                inFolder || hasWorldCategory
            }.map { f ->
                val fm = safeJson(f.frontmatterJson)
                val name = f.title
                val noteBase = f.name.substringBeforeLast('.').lowercase()
                WorldRecord(
                    relativePath = f.relativePath,
                    name = name,
                    category = fm.optString("category", "").ifBlank {
                        tagsByFile[f.relativePath]?.firstOrNull { it.startsWith("world/") }?.removePrefix("world/")?.replaceFirstChar { c -> c.uppercase() } ?: "Lore"
                    },
                    summary = f.bodyText.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.take(180) ?: "",
                    properties = jsonToStringMap(fm),
                    outgoingLinks = outByPath[f.relativePath].orEmpty().distinct(),
                    incomingLinks = inByName[noteBase].orEmpty().distinct(),
                    tags = tagsByFile[f.relativePath].orEmpty()
                )
            }.sortedBy { it.name.lowercase() }
        }

    fun observeChapters(configFlow: Flow<VaultConfiguration>): Flow<List<ChapterRecord>> =
        combine(dao.observeByKind(VaultFileKind.MARKDOWN.name), dao.observeAllTags(), configFlow) { files, tags, cfg ->
            val tagsByFile = tags.groupBy({ it.filePath }, { it.tag.lowercase() })
            files.filter { f ->
                val fm = safeJson(f.frontmatterJson)
                val fTags = tagsByFile[f.relativePath].orEmpty()
                val inFolder = cfg.chapterFolders.any { folder ->
                    val norm = VaultPathValidator.normalize(folder)
                    norm.isNotEmpty() && (f.folderPath == norm || f.folderPath.startsWith("$norm/"))
                }
                inFolder || fm.optString("type").lowercase() == "chapter" || fTags.contains("chapter")
            }.map { f ->
                val fm = safeJson(f.frontmatterJson)
                val num = fm.optInt("chapter", Regex("(\\d+)").find(f.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0)
                ChapterRecord(
                    relativePath = f.relativePath,
                    chapterNumber = num,
                    title = fm.optString("title", f.title),
                    timelineDate = fm.optString("timeline_date", fm.optString("date", "")),
                    characters = jsonList(fm, "characters"),
                    locations = jsonList(fm, "locations"),
                    factions = jsonList(fm, "factions"),
                    events = jsonList(fm, "events"),
                    summary = f.bodyText.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.take(200) ?: ""
                )
            }.sortedBy { it.chapterNumber }
        }

    suspend fun search(query: String, scope: SearchFilterScope = SearchFilterScope.ALL): List<SearchHit> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val rows = dao.searchIndexed(q, 120)
        return rows.mapNotNull { row ->
            val kind = runCatching { VaultFileKind.valueOf(row.kind) }.getOrDefault(VaultFileKind.OTHER)
            val fm = safeJson(row.frontmatterJson)
            val matchesScope = when (scope) {
                SearchFilterScope.ALL -> true
                SearchFilterScope.CHARACTERS -> fm.optString("type").equals("character", true) || row.relativePath.contains("character", true)
                SearchFilterScope.TRAITS -> fm.has("rank_value") || row.relativePath.contains("trait", true)
                SearchFilterScope.WORLD -> fm.has("category") || row.relativePath.contains("world", true)
                SearchFilterScope.CHAPTERS -> fm.optString("type").equals("chapter", true) || row.relativePath.contains("chapter", true)
                SearchFilterScope.NOTES -> kind == VaultFileKind.MARKDOWN
                SearchFilterScope.JSON -> kind == VaultFileKind.JSON
                SearchFilterScope.PAGES -> row.relativePath.contains("/Pages/", true)
            }
            if (!matchesScope) return@mapNotNull null
            val matchedIn = when {
                row.title.contains(q, true) || row.name.contains(q, true) -> "Title"
                row.frontmatterJson.contains(q, true) -> "Properties"
                else -> "Content"
            }
            val snippet = row.bodyText.lineSequence()
                .firstOrNull { it.contains(q, true) }?.take(140)
                ?: row.bodyText.take(120)
            SearchHit(row.relativePath, row.title, kind, snippet, matchedIn)
        }
    }

    suspend fun resolveWikilink(targetName: String): VaultFileEntity? {
        val clean = targetName.removePrefix("[[").removeSuffix("]]").substringBefore('|').substringBefore('#').trim()
        val all = dao.getAllFiles()
        return all.firstOrNull { it.relativePath.equals(clean, true) }
            ?: all.firstOrNull { it.relativePath.equals("$clean.md", true) }
            ?: all.firstOrNull { it.name.substringBeforeLast('.').equals(clean, true) }
            ?: all.firstOrNull { it.title.equals(clean, true) }
    }

    suspend fun readLiveNote(gateway: VaultGateway, relativePath: String): VaultReadResult? =
        gateway.readText(relativePath)

    suspend fun saveNoteSafe(
        gateway: VaultGateway,
        relativePath: String,
        newContent: String,
        expectedSha256: String?
    ): WriteResult {
        val result = gateway.writeTextSafe(relativePath, newContent, expectedSha256)
        if (result is WriteResult.Success) {
            syncEngine.reindexSingleFile(gateway, relativePath)
        }
        return result
    }

    suspend fun saveNoteForce(
        gateway: VaultGateway,
        relativePath: String,
        newContent: String
    ): WriteResult {
        val result = gateway.writeTextForce(relativePath, newContent)
        if (result is WriteResult.Success) {
            syncEngine.reindexSingleFile(gateway, relativePath)
        }
        return result
    }

    suspend fun updateFrontmatterSafe(
        gateway: VaultGateway,
        relativePath: String,
        expectedSha256: String?,
        mutator: (MutableMap<String, Any?>) -> Unit
    ): WriteResult {
        val live = gateway.readText(relativePath)
            ?: return WriteResult.Error("File not found: $relativePath")
        if (expectedSha256 != null && live.sha256 != expectedSha256) {
            val pendingPreview = runCatching {
                MarkdownEngine.updateFrontmatterPreservingBody(live.text, mutator)
            }.getOrElse { live.text }
            return WriteResult.Conflict(
                relativePath = relativePath,
                expectedSha256 = expectedSha256,
                externalSha256 = live.sha256,
                externalContent = live.text,
                pendingContent = pendingPreview
            )
        }
        val updatedText = try {
            MarkdownEngine.updateFrontmatterPreservingBody(live.text, mutator)
        } catch (e: Exception) {
            return WriteResult.Error(e.message ?: "Frontmatter update failed")
        }
        return saveNoteForce(gateway, relativePath, updatedText)
    }

    private fun toCharacterRecord(f: VaultFileEntity): CharacterRecord {
        val fm = safeJson(f.frontmatterJson)
        return CharacterRecord(
            relativePath = f.relativePath,
            name = fm.optString("name", f.title),
            rank = fm.optString("rank", ""),
            rankValue = fm.optDouble("rank_value", 0.0).let { if (it.isNaN()) 0.0 else it },
            species = fm.optString("species", ""),
            gender = fm.optString("gender", ""),
            status = fm.optString("status", ""),
            factions = jsonList(fm, "faction") + jsonList(fm, "factions"),
            traits = jsonList(fm, "traits"),
            relationships = jsonList(fm, "relationships"),
            chapters = jsonList(fm, "chapters"),
            profileImage = fm.optString("image", fm.optString("avatar", "")).takeIf { it.isNotBlank() },
            properties = jsonToStringMap(fm),
            bodyPreview = f.bodyText.lineSequence().filter { it.isNotBlank() }.take(4).joinToString("\n")
        )
    }

    private fun toTraitRecord(f: VaultFileEntity): TraitRecord {
        val fm = safeJson(f.frontmatterJson)
        fun dbl(k: String) = fm.optDouble(k, 0.0).let { if (it.isNaN()) 0.0 else it }
        return TraitRecord(
            relativePath = f.relativePath,
            name = fm.optString("name", f.title),
            rank = fm.optString("rank", ""),
            rankValue = dbl("rank_value"),
            layer = fm.optString("layer", ""),
            subcategory = fm.optString("subcategory", ""),
            type = fm.optString("type", ""),
            status = fm.optString("status", ""),
            form = fm.optString("form", ""),
            devouredIn = stripWikilink(fm.optString("devoured_in", "")),
            chapter = fm.optString("chapter", ""),
            sourceCreature = stripWikilink(fm.optString("source_creature", "")),
            species = fm.optString("species", ""),
            load = dbl("load"),
            activation = fm.optString("activation", ""),
            scaling = fm.optString("scaling", ""),
            description = fm.optString("description", ""),
            statPower = dbl("stat_power"),
            statSpeed = dbl("stat_speed"),
            statVitality = dbl("stat_vitality"),
            statPerception = dbl("stat_perception"),
            statEnergy = dbl("stat_energy"),
            boostPower = dbl("boost_power"),
            boostSpeed = dbl("boost_speed"),
            boostVitality = dbl("boost_vitality"),
            boostPerception = dbl("boost_perception"),
            boostEnergy = dbl("boost_energy"),
            synergy = jsonList(fm, "synergy").map(::stripWikilink),
            conflicts = jsonList(fm, "conflicts").map(::stripWikilink),
            tags = jsonList(fm, "tags"),
            extraProperties = jsonToStringMap(fm),
            body = f.bodyText
        )
    }

    private fun stripWikilink(raw: String): String =
        raw.removePrefix("[[").removeSuffix("]]").substringBefore('|').trim()

    private fun safeJson(raw: String): JSONObject =
        runCatching { JSONObject(raw) }.getOrElse { JSONObject() }

    private fun jsonList(obj: JSONObject, key: String): List<String> {
        if (!obj.has(key) || obj.isNull(key)) return emptyList()
        return when (val v = obj.get(key)) {
            is JSONArray -> (0 until v.length()).map { v.get(it).toString() }.filter { it.isNotBlank() }
            is String -> if (v.isBlank()) emptyList() else listOf(v)
            else -> listOf(v.toString())
        }
    }

    private fun jsonToStringMap(obj: JSONObject): Map<String, String> {
        val map = linkedMapOf<String, String>()
        obj.keys().forEach { k ->
            val v = obj.opt(k)
            if (v != null && v != JSONObject.NULL) {
                map[k] = when (v) {
                    is JSONArray -> (0 until v.length()).joinToString(", ") { v.get(it).toString() }
                    else -> v.toString()
                }
            }
        }
        return map
    }
}
