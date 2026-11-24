package com.novelworkspace.compatibility

import com.novelworkspace.core.security.VaultConfiguration
import com.novelworkspace.core.vault.VaultGateway
import com.novelworkspace.core.vault.VaultPathValidator
import com.novelworkspace.data.database.VaultDao
import com.novelworkspace.data.database.VaultFileEntity
import com.novelworkspace.data.models.CustomPageManifest
import com.novelworkspace.data.models.PathAliasResolver
import com.novelworkspace.data.models.VaultFileKind
import org.json.JSONArray
import org.json.JSONObject

class DataviewQueryEngine(
    private val dao: VaultDao
) {
    suspend fun buildHydrationPayload(
        manifest: CustomPageManifest,
        config: VaultConfiguration,
        pageLocalStorage: Map<String, String>
    ): JSONObject {
        val allFiles = dao.getAllFiles()
        val tagMap = buildTagMap(allFiles)

        val scopedFiles = allFiles.filter { file ->
            isInPageScope(file.relativePath, manifest)
        }

        val pagesArr = JSONArray()
        scopedFiles.filter { it.kind == VaultFileKind.MARKDOWN.name }.forEach { f ->
            val obj = runCatching { JSONObject(f.frontmatterJson) }.getOrElse { JSONObject() }
            val fileTags = tagMap[f.relativePath].orEmpty()
            val fileMeta = JSONObject().apply {
                put("name", f.name.substringBeforeLast('.'))
                put("path", f.relativePath)
                put("folder", f.folderPath)
                put("ext", f.name.substringAfterLast('.', "md"))
                put("mtime", f.lastModifiedMs)
                put("size", f.sizeBytes)
                put("tags", JSONArray(fileTags.map { "#$it" }))
                put("etags", JSONArray(fileTags.map { "#$it" }))
                put("link", JSONObject().put("path", f.relativePath).put("type", "file"))
            }
            obj.put("file", fileMeta)
            pagesArr.put(obj)
        }

        val folderSet = linkedSetOf<String>()
        allFiles.forEach { f ->
            var p = f.folderPath
            while (p.isNotEmpty()) {
                folderSet.add(p)
                p = VaultPathValidator.parentOf(p)
            }
        }

        val abstractArr = JSONArray()
        folderSet.sorted().forEach { folder ->
            abstractArr.put(
                JSONObject().apply {
                    put("path", folder)
                    put("name", VaultPathValidator.nameOf(folder))
                    put("parent", VaultPathValidator.parentOf(folder))
                    put("isFolder", true)
                }
            )
        }
        allFiles.forEach { f ->
            abstractArr.put(
                JSONObject().apply {
                    put("path", f.relativePath)
                    put("name", f.name)
                    put("parent", f.folderPath)
                    put("isFolder", false)
                    put("mtime", f.lastModifiedMs)
                    put("size", f.sizeBytes)
                }
            )
        }

        val storageObj = JSONObject()
        pageLocalStorage.forEach { (k, v) -> storageObj.put(k, v) }

        return JSONObject().apply {
            put("pages", pagesArr)
            put("abstractFiles", abstractArr)
            put("localStorage", storageObj)
            put("aliases", JSONObject(config.pathAliases))
        }
    }

    private suspend fun buildTagMap(files: List<VaultFileEntity>): Map<String, List<String>> {
        val result = HashMap<String, List<String>>(files.size)
        for (f in files) {
            if (f.kind == VaultFileKind.MARKDOWN.name) {
                result[f.relativePath] = dao.getTagsFor(f.relativePath).map { it.tag }
            }
        }
        return result
    }

    private fun isInPageScope(relativePath: String, manifest: CustomPageManifest): Boolean {
        val norm = VaultPathValidator.normalize(relativePath)
        val excluded = manifest.excludedFolders.any { ex ->
            val e = VaultPathValidator.normalize(ex)
            e.isNotEmpty() && (norm == e || norm.startsWith("$e/"))
        }
        if (excluded) return false
        if (manifest.sourceFolders.isEmpty() && manifest.explicitFiles.isEmpty()) return true
        if (manifest.explicitFiles.any { VaultPathValidator.normalize(it) == norm }) return true
        return manifest.sourceFolders.any { src ->
            val s = VaultPathValidator.normalize(src)
            s.isNotEmpty() && (norm == s || norm.startsWith("$s/"))
        }
    }

    suspend fun resolveAliasedPath(
        gateway: VaultGateway,
        requestedPath: String,
        config: VaultConfiguration,
        pageFolderPath: String? = null
    ): String? {
        val candidates = mutableListOf<String>()
        if (!pageFolderPath.isNullOrBlank()) {
            val normReq = VaultPathValidator.normalize(requestedPath)
            candidates.add("$pageFolderPath/modules/$normReq")
            if (!normReq.endsWith(".js")) candidates.add("$pageFolderPath/modules/$normReq.js")
        }
        candidates.addAll(PathAliasResolver.resolveCandidates(requestedPath, config.pathAliases))
        for (candidate in candidates.distinct()) {
            if (gateway.exists(candidate) && !gateway.isDirectory(candidate)) {
                return candidate
            }
        }
        return null
    }
}
