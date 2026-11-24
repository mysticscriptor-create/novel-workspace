package com.novelworkspace.data.models

import com.novelworkspace.core.vault.VaultPathValidator
import org.json.JSONArray
import org.json.JSONObject

enum class VaultFileKind { MARKDOWN, JSON, JS, CSS, IMAGE, OTHER }

enum class SearchFilterScope { ALL, CHARACTERS, TRAITS, WORLD, CHAPTERS, NOTES, JSON, PAGES }

data class SearchHit(
    val relativePath: String,
    val title: String,
    val kind: VaultFileKind,
    val snippet: String,
    val matchedIn: String
)

data class CharacterRecord(
    val relativePath: String,
    val name: String,
    val rank: String,
    val rankValue: Double,
    val species: String,
    val gender: String,
    val status: String,
    val factions: List<String>,
    val traits: List<String>,
    val relationships: List<String>,
    val chapters: List<String>,
    val profileImage: String?,
    val properties: Map<String, String>,
    val bodyPreview: String
)

data class TraitRecord(
    val relativePath: String,
    val name: String,
    val rank: String,
    val rankValue: Double,
    val layer: String,
    val subcategory: String,
    val type: String,
    val status: String,
    val form: String,
    val devouredIn: String,
    val chapter: String,
    val sourceCreature: String,
    val species: String,
    val load: Double,
    val activation: String,
    val scaling: String,
    val description: String,
    val statPower: Double,
    val statSpeed: Double,
    val statVitality: Double,
    val statPerception: Double,
    val statEnergy: Double,
    val boostPower: Double,
    val boostSpeed: Double,
    val boostVitality: Double,
    val boostPerception: Double,
    val boostEnergy: Double,
    val synergy: List<String>,
    val conflicts: List<String>,
    val tags: List<String>,
    val extraProperties: Map<String, String>,
    val body: String
)

data class WorldRecord(
    val relativePath: String,
    val name: String,
    val category: String,
    val summary: String,
    val properties: Map<String, String>,
    val outgoingLinks: List<String>,
    val incomingLinks: List<String>,
    val tags: List<String>
)

data class ChapterRecord(
    val relativePath: String,
    val chapterNumber: Int,
    val title: String,
    val timelineDate: String,
    val characters: List<String>,
    val locations: List<String>,
    val factions: List<String>,
    val events: List<String>,
    val summary: String
)

data class CustomPageManifest(
    val id: String,
    val title: String,
    val category: String = "Dashboard",
    val iconName: String = "Code",
    val folderPath: String,
    val sourceFolders: List<String> = emptyList(),
    val excludedFolders: List<String> = emptyList(),
    val explicitFiles: List<String> = emptyList(),
    val moduleDependencies: List<String> = emptyList(),
    val allowNetwork: Boolean = false,
    val allowVaultWrite: Boolean = true,
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("category", category)
        put("iconName", iconName)
        put("folderPath", folderPath)
        put("sourceFolders", JSONArray(sourceFolders))
        put("excludedFolders", JSONArray(excludedFolders))
        put("explicitFiles", JSONArray(explicitFiles))
        put("moduleDependencies", JSONArray(moduleDependencies))
        put("allowNetwork", allowNetwork)
        put("allowVaultWrite", allowVaultWrite)
        put("updatedAtMs", updatedAtMs)
    }.toString(2)

    companion object {
        fun fromJson(raw: String, fallbackFolder: String): CustomPageManifest {
            val obj = JSONObject(raw)
            fun strList(k: String): List<String> {
                val arr = obj.optJSONArray(k) ?: return emptyList()
                return (0 until arr.length()).map { arr.getString(it) }
            }
            val folder = obj.optString("folderPath", fallbackFolder)
            return CustomPageManifest(
                id = obj.optString("id", VaultPathValidator.nameOf(folder)),
                title = obj.optString("title", VaultPathValidator.nameOf(folder)),
                category = obj.optString("category", "Dashboard"),
                iconName = obj.optString("iconName", "Code"),
                folderPath = folder,
                sourceFolders = strList("sourceFolders"),
                excludedFolders = strList("excludedFolders"),
                explicitFiles = strList("explicitFiles"),
                moduleDependencies = strList("moduleDependencies"),
                allowNetwork = obj.optBoolean("allowNetwork", false),
                allowVaultWrite = obj.optBoolean("allowVaultWrite", true),
                updatedAtMs = obj.optLong("updatedAtMs", System.currentTimeMillis())
            )
        }
    }
}

data class SyncReport(
    val scannedFiles: Int,
    val parsedNotes: Int,
    val updatedFiles: Int,
    val removedFiles: Int,
    val errorCount: Int,
    val durationMs: Long
)

object PathAliasResolver {
    fun resolveCandidates(rawPath: String, aliases: Map<String, String>): List<String> {
        val norm = VaultPathValidator.normalize(rawPath)
        val out = linkedSetOf<String>()
        out.add(norm)
        for ((fromPrefix, toPrefix) in aliases) {
            val normFrom = VaultPathValidator.normalize(fromPrefix)
            val normTo = VaultPathValidator.normalize(toPrefix)
            if (normFrom.isNotEmpty() && norm.startsWith("$normFrom/")) {
                val suffix = norm.removePrefix("$normFrom/")
                out.add(if (normTo.isEmpty()) suffix else "$normTo/$suffix")
            } else if (norm == normFrom && normTo.isNotEmpty()) {
                out.add(normTo)
            }
        }
        // Also support .js / .md auto-extensions when resolving scripts or notes
        val withExtensions = linkedSetOf<String>()
        for (c in out) {
            withExtensions.add(c)
            if (!c.substringAfterLast('/').contains('.')) {
                withExtensions.add("$c.js")
                withExtensions.add("$c.md")
            }
        }
        return withExtensions.toList()
    }
}
