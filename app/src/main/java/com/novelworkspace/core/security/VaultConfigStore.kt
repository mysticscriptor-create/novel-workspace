package com.novelworkspace.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

enum class AppThemeMode { SYSTEM, LIGHT, DARK }

data class HomeSectionPrefs(
    val showRecentNotes: Boolean = true,
    val showRecentChanges: Boolean = true,
    val showFavouriteCharacters: Boolean = true,
    val showFavouritePages: Boolean = true,
    val showChapterProgress: Boolean = true,
    val showWorldHighlights: Boolean = true
)

data class VaultConfiguration(
    val treeUriString: String? = null,
    val includedFolders: Set<String> = emptySet(),
    val excludedFolders: Set<String> = setOf(".obsidian", ".trash", "Templates"),
    val characterFolders: Set<String> = setOf("Characters", "character"),
    val traitFolders: Set<String> = setOf("000 A System/Traits", "Traits"),
    val worldFolders: Set<String> = setOf("World", "Lore", "Locations", "Factions"),
    val chapterFolders: Set<String> = setOf("Chapters", "Timeline"),
    val customPagesRoot: String = "Novel Workspace/Pages",
    val pathAliases: Map<String, String> = mapOf(
        "Meta/Scripts/" to "Sub-js/",
        "System/modules/" to "Sub-js/",
        "config/" to "World_Json/"
    ),
    val favouriteFolders: Set<String> = emptySet(),
    val favouriteNotePaths: Set<String> = emptySet(),
    val favouritePageIds: Set<String> = emptySet(),
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val lastSyncTimeMs: Long = 0L,
    val homePrefs: HomeSectionPrefs = HomeSectionPrefs()
)

class VaultConfigStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("novel_workspace_config", Context.MODE_PRIVATE)

    private val secretPrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "novel_workspace_secrets",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            context.getSharedPreferences("novel_workspace_secrets_fallback", Context.MODE_PRIVATE)
        }
    }

    fun load(): VaultConfiguration {
        val raw = prefs.getString("config_json", null) ?: return VaultConfiguration()
        return fromJson(raw)
    }

    fun save(config: VaultConfiguration) {
        prefs.edit().putString("config_json", toJson(config)).apply()
    }

    fun update(transform: (VaultConfiguration) -> VaultConfiguration): VaultConfiguration {
        val next = transform(load())
        save(next)
        return next
    }

    fun setApiKey(provider: String, key: String) {
        secretPrefs.edit().putString("api_key_$provider", key.trim()).apply()
    }

    fun getApiKey(provider: String): String? =
        secretPrefs.getString("api_key_$provider", null)?.takeIf { it.isNotBlank() }

    fun getConfiguredProviders(): List<String> =
        listOf("gemini", "groq", "openrouter").filter { getApiKey(it) != null }

    fun setPageNetworkAllowed(pageId: String, allowed: Boolean) {
        prefs.edit().putBoolean("page_net_$pageId", allowed).apply()
    }

    fun isPageNetworkAllowed(pageId: String): Boolean =
        prefs.getBoolean("page_net_$pageId", false)

    fun setPageWriteAllowed(pageId: String, allowed: Boolean) {
        prefs.edit().putBoolean("page_write_$pageId", allowed).apply()
    }

    fun isPageWriteAllowed(pageId: String): Boolean =
        prefs.getBoolean("page_write_$pageId", true)

    fun exportConfigJson(includeTreeUri: Boolean = false): String {
        val current = load()
        return toJson(if (includeTreeUri) current else current.copy(treeUriString = null))
    }

    fun importConfigJson(rawJson: String): VaultConfiguration {
        val current = load()
        val parsed = fromJson(rawJson)
        val merged = parsed.copy(treeUriString = parsed.treeUriString ?: current.treeUriString)
        save(merged)
        return merged
    }

    companion object {
        fun toJson(config: VaultConfiguration): String {
            val aliasesObj = JSONObject()
            config.pathAliases.forEach { (k, v) -> aliasesObj.put(k, v) }
            val homeObj = JSONObject().apply {
                put("showRecentNotes", config.homePrefs.showRecentNotes)
                put("showRecentChanges", config.homePrefs.showRecentChanges)
                put("showFavouriteCharacters", config.homePrefs.showFavouriteCharacters)
                put("showFavouritePages", config.homePrefs.showFavouritePages)
                put("showChapterProgress", config.homePrefs.showChapterProgress)
                put("showWorldHighlights", config.homePrefs.showWorldHighlights)
            }
            return JSONObject().apply {
                put("treeUriString", config.treeUriString ?: JSONObject.NULL)
                put("includedFolders", JSONArray(config.includedFolders.toList()))
                put("excludedFolders", JSONArray(config.excludedFolders.toList()))
                put("characterFolders", JSONArray(config.characterFolders.toList()))
                put("traitFolders", JSONArray(config.traitFolders.toList()))
                put("worldFolders", JSONArray(config.worldFolders.toList()))
                put("chapterFolders", JSONArray(config.chapterFolders.toList()))
                put("customPagesRoot", config.customPagesRoot)
                put("pathAliases", aliasesObj)
                put("favouriteFolders", JSONArray(config.favouriteFolders.toList()))
                put("favouriteNotePaths", JSONArray(config.favouriteNotePaths.toList()))
                put("favouritePageIds", JSONArray(config.favouritePageIds.toList()))
                put("themeMode", config.themeMode.name)
                put("lastSyncTimeMs", config.lastSyncTimeMs)
                put("homePrefs", homeObj)
            }.toString(2)
        }

        fun fromJson(raw: String): VaultConfiguration {
            val obj = JSONObject(raw)
            fun arrSet(key: String, default: Set<String>): Set<String> {
                if (!obj.has(key) || obj.isNull(key)) return default
                val arr = obj.getJSONArray(key)
                return (0 until arr.length()).map { arr.getString(it) }.toSet()
            }
            val aliases = linkedMapOf<String, String>()
            obj.optJSONObject("pathAliases")?.let { a ->
                a.keys().forEach { k -> aliases[k] = a.getString(k) }
            }
            val homeObj = obj.optJSONObject("homePrefs")
            val home = HomeSectionPrefs(
                showRecentNotes = homeObj?.optBoolean("showRecentNotes", true) ?: true,
                showRecentChanges = homeObj?.optBoolean("showRecentChanges", true) ?: true,
                showFavouriteCharacters = homeObj?.optBoolean("showFavouriteCharacters", true) ?: true,
                showFavouritePages = homeObj?.optBoolean("showFavouritePages", true) ?: true,
                showChapterProgress = homeObj?.optBoolean("showChapterProgress", true) ?: true,
                showWorldHighlights = homeObj?.optBoolean("showWorldHighlights", true) ?: true
            )
            val defaults = VaultConfiguration()
            return VaultConfiguration(
                treeUriString = if (obj.isNull("treeUriString")) null else obj.optString("treeUriString", "").takeIf { it.isNotBlank() },
                includedFolders = arrSet("includedFolders", defaults.includedFolders),
                excludedFolders = arrSet("excludedFolders", defaults.excludedFolders),
                characterFolders = arrSet("characterFolders", defaults.characterFolders),
                traitFolders = arrSet("traitFolders", defaults.traitFolders),
                worldFolders = arrSet("worldFolders", defaults.worldFolders),
                chapterFolders = arrSet("chapterFolders", defaults.chapterFolders),
                customPagesRoot = obj.optString("customPagesRoot", defaults.customPagesRoot),
                pathAliases = if (aliases.isEmpty()) defaults.pathAliases else aliases,
                favouriteFolders = arrSet("favouriteFolders", emptySet()),
                favouriteNotePaths = arrSet("favouriteNotePaths", emptySet()),
                favouritePageIds = arrSet("favouritePageIds", emptySet()),
                themeMode = runCatching { AppThemeMode.valueOf(obj.optString("themeMode", "SYSTEM")) }.getOrDefault(AppThemeMode.SYSTEM),
                lastSyncTimeMs = obj.optLong("lastSyncTimeMs", 0L),
                homePrefs = home
            )
        }
    }
}
