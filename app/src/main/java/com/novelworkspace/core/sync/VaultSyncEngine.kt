package com.novelworkspace.core.sync

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.novelworkspace.core.markdown.MarkdownEngine
import com.novelworkspace.core.security.VaultConfigStore
import com.novelworkspace.core.security.VaultConfiguration
import com.novelworkspace.core.vault.SafVaultGateway
import com.novelworkspace.core.vault.VaultFileEntry
import com.novelworkspace.core.vault.VaultGateway
import com.novelworkspace.core.vault.VaultPathValidator
import com.novelworkspace.data.database.AppDatabase
import com.novelworkspace.data.database.NotePropertyEntity
import com.novelworkspace.data.database.TagEntity
import com.novelworkspace.data.database.VaultDao
import com.novelworkspace.data.database.VaultFileEntity
import com.novelworkspace.data.database.WikilinkEntity
import com.novelworkspace.data.models.SyncReport
import com.novelworkspace.data.models.VaultFileKind
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class VaultSyncEngine(
    private val dao: VaultDao
) {
    private val syncMutex = Mutex()

    suspend fun syncIncremental(
        gateway: VaultGateway,
        config: VaultConfiguration,
        forceFullRebuild: Boolean = false
    ): SyncReport = syncMutex.withLock {
        val startMs = System.currentTimeMillis()
        if (forceFullRebuild) {
            dao.clearAll()
        }

        val existingByPath = dao.getAllFiles().associateBy { it.relativePath }
        val entries = collectIncludedFiles(gateway, config)
        val livePaths = entries.map { it.relativePath }.toHashSet()

        var removedCount = 0
        for (oldPath in existingByPath.keys) {
            if (!livePaths.contains(oldPath)) {
                dao.removeFileIndex(oldPath)
                removedCount++
            }
        }

        var updatedCount = 0
        var parsedNotes = 0
        var errorCount = 0

        for (entry in entries) {
            val prev = existingByPath[entry.relativePath]
            val kind = classifyKind(entry.name)

            // Fast skip when timestamp & byte size match and it wasn't an error
            if (!forceFullRebuild && prev != null &&
                prev.lastModifiedMs == entry.lastModifiedMs &&
                prev.sizeBytes == entry.sizeBytes &&
                entry.lastModifiedMs > 0L
            ) {
                continue
            }

            if (kind == VaultFileKind.IMAGE || kind == VaultFileKind.OTHER) {
                val entity = VaultFileEntity(
                    relativePath = entry.relativePath,
                    name = entry.name,
                    title = entry.name.substringBeforeLast('.'),
                    folderPath = VaultPathValidator.parentOf(entry.relativePath),
                    kind = kind.name,
                    sizeBytes = entry.sizeBytes,
                    lastModifiedMs = entry.lastModifiedMs,
                    sha256 = "",
                    frontmatterJson = "{}",
                    bodyText = "",
                    headingsJson = "[]",
                    parseError = null
                )
                if (prev == null || prev.lastModifiedMs != entry.lastModifiedMs || prev.sizeBytes != entry.sizeBytes) {
                    dao.replaceFileIndex(entity, emptyList(), emptyList(), emptyList())
                    updatedCount++
                }
                continue
            }

            val read = gateway.readText(entry.relativePath) ?: continue
            if (!forceFullRebuild && prev != null && prev.sha256 == read.sha256) {
                continue
            }

            val indexed = indexTextEntry(entry, kind, read.text, read.sha256)
            dao.replaceFileIndex(indexed.file, indexed.props, indexed.tags, indexed.links)
            updatedCount++
            if (kind == VaultFileKind.MARKDOWN) parsedNotes++
            if (indexed.file.parseError != null) errorCount++
        }

        SyncReport(
            scannedFiles = entries.size,
            parsedNotes = parsedNotes,
            updatedFiles = updatedCount,
            removedFiles = removedCount,
            errorCount = errorCount,
            durationMs = System.currentTimeMillis() - startMs
        )
    }

    suspend fun reindexSingleFile(gateway: VaultGateway, relativePath: String) {
        val norm = VaultPathValidator.normalize(relativePath)
        val entry = gateway.stat(norm)
        if (entry == null) {
            dao.removeFileIndex(norm)
            return
        }
        val kind = classifyKind(entry.name)
        if (kind == VaultFileKind.IMAGE || kind == VaultFileKind.OTHER) {
            dao.replaceFileIndex(
                VaultFileEntity(
                    relativePath = entry.relativePath,
                    name = entry.name,
                    title = entry.name.substringBeforeLast('.'),
                    folderPath = VaultPathValidator.parentOf(entry.relativePath),
                    kind = kind.name,
                    sizeBytes = entry.sizeBytes,
                    lastModifiedMs = entry.lastModifiedMs,
                    sha256 = "",
                    frontmatterJson = "{}",
                    bodyText = "",
                    headingsJson = "[]",
                    parseError = null
                ),
                emptyList(), emptyList(), emptyList()
            )
            return
        }
        val read = gateway.readText(norm) ?: return
        val indexed = indexTextEntry(entry, kind, read.text, read.sha256)
        dao.replaceFileIndex(indexed.file, indexed.props, indexed.tags, indexed.links)
    }

    private suspend fun collectIncludedFiles(
        gateway: VaultGateway,
        config: VaultConfiguration
    ): List<VaultFileEntry> {
        val all = if (config.includedFolders.isEmpty()) {
            gateway.listRecursive("", config.excludedFolders)
        } else {
            config.includedFolders.flatMap { folder ->
                gateway.listRecursive(folder, config.excludedFolders)
            }.distinctBy { it.relativePath }
        }
        return all.filter { !it.isDirectory }
    }

    private data class IndexedRow(
        val file: VaultFileEntity,
        val props: List<NotePropertyEntity>,
        val tags: List<TagEntity>,
        val links: List<WikilinkEntity>
    )

    private fun indexTextEntry(
        entry: VaultFileEntry,
        kind: VaultFileKind,
        text: String,
        sha256: String
    ): IndexedRow {
        val folderPath = VaultPathValidator.parentOf(entry.relativePath)
        val baseTitle = entry.name.substringBeforeLast('.')

        return when (kind) {
            VaultFileKind.MARKDOWN -> {
                val parsed = MarkdownEngine.parse(text, entry.relativePath)
                val fmJsonObj = JSONObject()
                val props = mutableListOf<NotePropertyEntity>()
                parsed.frontmatter.forEach { (k, v) ->
                    fmJsonObj.put(k, anyToJson(v))
                    flattenProperty(entry.relativePath, k, v, props)
                }
                val noteTitle = parsed.frontmatter["title"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: parsed.frontmatter["name"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: baseTitle
                val headingsArr = JSONArray().apply {
                    parsed.headings.forEach { h ->
                        put(JSONObject().put("level", h.level).put("text", h.text))
                    }
                }
                val tags = parsed.tags.map { TagEntity(filePath = entry.relativePath, tag = it) }
                val links = parsed.wikilinks.map {
                    WikilinkEntity(sourcePath = entry.relativePath, targetName = it.target, alias = it.alias)
                }
                IndexedRow(
                    file = VaultFileEntity(
                        relativePath = entry.relativePath,
                        name = entry.name,
                        title = noteTitle,
                        folderPath = folderPath,
                        kind = kind.name,
                        sizeBytes = entry.sizeBytes,
                        lastModifiedMs = entry.lastModifiedMs,
                        sha256 = sha256,
                        frontmatterJson = fmJsonObj.toString(),
                        bodyText = parsed.body,
                        headingsJson = headingsArr.toString(),
                        parseError = parsed.parseError
                    ),
                    props = props,
                    tags = tags,
                    links = links
                )
            }
            VaultFileKind.JSON -> {
                var parseErr: String? = null
                try {
                    val trimmed = text.trim()
                    if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
                } catch (e: Exception) {
                    parseErr = "JSON error in ${entry.relativePath}: ${e.message}"
                }
                IndexedRow(
                    file = VaultFileEntity(
                        relativePath = entry.relativePath,
                        name = entry.name,
                        title = baseTitle,
                        folderPath = folderPath,
                        kind = kind.name,
                        sizeBytes = entry.sizeBytes,
                        lastModifiedMs = entry.lastModifiedMs,
                        sha256 = sha256,
                        frontmatterJson = "{}",
                        bodyText = text.take(16_000),
                        headingsJson = "[]",
                        parseError = parseErr
                    ),
                    props = emptyList(),
                    tags = emptyList(),
                    links = emptyList()
                )
            }
            else -> {
                IndexedRow(
                    file = VaultFileEntity(
                        relativePath = entry.relativePath,
                        name = entry.name,
                        title = baseTitle,
                        folderPath = folderPath,
                        kind = kind.name,
                        sizeBytes = entry.sizeBytes,
                        lastModifiedMs = entry.lastModifiedMs,
                        sha256 = sha256,
                        frontmatterJson = "{}",
                        bodyText = text.take(16_000),
                        headingsJson = "[]",
                        parseError = null
                    ),
                    props = emptyList(),
                    tags = emptyList(),
                    links = emptyList()
                )
            }
        }
    }

    private fun flattenProperty(
        path: String,
        key: String,
        value: Any?,
        dest: MutableList<NotePropertyEntity>
    ) {
        when (value) {
            null -> Unit
            is List<*> -> value.forEach { item ->
                if (item != null) dest.add(NotePropertyEntity(filePath = path, propKey = key, propValue = item.toString()))
            }
            else -> dest.add(NotePropertyEntity(filePath = path, propKey = key, propValue = value.toString()))
        }
    }

    private fun anyToJson(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { obj ->
            value.forEach { (k, v) -> if (k != null) obj.put(k.toString(), anyToJson(v)) }
        }
        is List<*> -> JSONArray().also { arr ->
            value.forEach { arr.put(anyToJson(it)) }
        }
        is Boolean, is Number, is String -> value
        else -> value.toString()
    }

    companion object {
        fun classifyKind(fileName: String): VaultFileKind {
            return when (fileName.substringAfterLast('.', "").lowercase()) {
                "md", "markdown" -> VaultFileKind.MARKDOWN
                "json" -> VaultFileKind.JSON
                "js" -> VaultFileKind.JS
                "css" -> VaultFileKind.CSS
                "png", "jpg", "jpeg", "webp", "gif", "svg" -> VaultFileKind.IMAGE
                else -> VaultFileKind.OTHER
            }
        }

        fun schedulePeriodicSync(context: Context) {
            val req = PeriodicWorkRequestBuilder<VaultSyncWorker>(30, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "novel_workspace_periodic_sync",
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }
    }
}

class VaultSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val store = VaultConfigStore(applicationContext)
        val config = store.load()
        val uriStr = config.treeUriString ?: return Result.success()
        val gateway = SafVaultGateway(applicationContext, Uri.parse(uriStr))
        val dao = AppDatabase.getInstance(applicationContext).vaultDao()
        val report = VaultSyncEngine(dao).syncIncremental(gateway, config)
        store.update { it.copy(lastSyncTimeMs = System.currentTimeMillis()) }
        return if (report.errorCount >= 0) Result.success() else Result.retry()
    }
}
