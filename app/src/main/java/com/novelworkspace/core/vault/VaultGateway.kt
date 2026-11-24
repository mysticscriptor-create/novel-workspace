package com.novelworkspace.core.vault

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

object VaultPathValidator {
    fun normalize(raw: String): String {
        val cleaned = raw.replace('\\', '/').trim().trimStart('/')
        require(!cleaned.split('/').any { it == ".." }) {
            "Invalid vault path traversal: $raw"
        }
        return cleaned.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")
    }

    fun parentOf(path: String): String {
        val norm = normalize(path)
        val idx = norm.lastIndexOf('/')
        return if (idx <= 0) "" else norm.substring(0, idx)
    }

    fun nameOf(path: String): String {
        val norm = normalize(path)
        val idx = norm.lastIndexOf('/')
        return if (idx < 0) norm else norm.substring(idx + 1)
    }

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))
}

data class VaultFileEntry(
    val relativePath: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val mimeType: String
)

data class VaultReadResult(
    val relativePath: String,
    val text: String,
    val sha256: String,
    val lastModifiedMs: Long
)

sealed class WriteResult {
    data class Success(val newSha256: String, val newLastModifiedMs: Long) : WriteResult()
    data class Conflict(
        val relativePath: String,
        val expectedSha256: String,
        val externalSha256: String,
        val externalContent: String,
        val pendingContent: String
    ) : WriteResult()
    data class Error(val message: String) : WriteResult()
}

interface VaultGateway {
    suspend fun exists(relativePath: String): Boolean
    suspend fun isDirectory(relativePath: String): Boolean
    suspend fun stat(relativePath: String): VaultFileEntry?
    suspend fun readText(relativePath: String): VaultReadResult?
    suspend fun readBytes(relativePath: String): ByteArray?
    suspend fun writeTextSafe(
        relativePath: String,
        content: String,
        expectedBaseSha256: String?
    ): WriteResult
    suspend fun writeTextForce(relativePath: String, content: String): WriteResult
    suspend fun writeBytes(relativePath: String, bytes: ByteArray, mimeType: String = "application/octet-stream"): Boolean
    suspend fun createFolder(relativePath: String): Boolean
    suspend fun deleteFile(relativePath: String): Boolean
    suspend fun listChildren(relativePath: String = ""): List<VaultFileEntry>
    suspend fun listRecursive(
        rootRelativePath: String = "",
        excludedPrefixes: Set<String> = emptySet()
    ): List<VaultFileEntry>
    fun resolveContentUri(relativePath: String): Uri?
}

class SafVaultGateway(
    private val context: Context,
    private val treeUri: Uri
) : VaultGateway {

    private fun rootDoc(): DocumentFile? = DocumentFile.fromTreeUri(context, treeUri)

    private fun findDocument(relativePath: String): DocumentFile? {
        val norm = VaultPathValidator.normalize(relativePath)
        var current = rootDoc() ?: return null
        if (norm.isEmpty()) return current
        for (segment in norm.split('/')) {
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    private fun ensureFolderDoc(relativePath: String): DocumentFile? {
        val norm = VaultPathValidator.normalize(relativePath)
        var current = rootDoc() ?: return null
        if (norm.isEmpty()) return current
        for (segment in norm.split('/')) {
            val next = current.findFile(segment)
            current = if (next != null && next.isDirectory) {
                next
            } else if (next == null) {
                current.createDirectory(segment) ?: return null
            } else {
                return null
            }
        }
        return current
    }

    private fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "md", "markdown" -> "text/markdown"
            "json" -> "application/json"
            "js" -> "application/javascript"
            "css" -> "text/css"
            "html", "htm" -> "text/html"
            "svg" -> "image/svg+xml"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    override suspend fun exists(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        findDocument(relativePath) != null
    }

    override suspend fun isDirectory(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        findDocument(relativePath)?.isDirectory == true
    }

    override suspend fun stat(relativePath: String): VaultFileEntry? = withContext(Dispatchers.IO) {
        val norm = VaultPathValidator.normalize(relativePath)
        val doc = findDocument(norm) ?: return@withContext null
        val name = doc.name ?: VaultPathValidator.nameOf(norm)
        VaultFileEntry(
            relativePath = norm,
            name = name,
            isDirectory = doc.isDirectory,
            sizeBytes = doc.length(),
            lastModifiedMs = doc.lastModified(),
            mimeType = doc.type ?: mimeFor(name)
        )
    }

    override suspend fun readText(relativePath: String): VaultReadResult? = withContext(Dispatchers.IO) {
        val norm = VaultPathValidator.normalize(relativePath)
        val doc = findDocument(norm) ?: return@withContext null
        if (doc.isDirectory) return@withContext null
        val bytes = context.contentResolver.openInputStream(doc.uri)?.use { input ->
            val out = ByteArrayOutputStream()
            input.copyTo(out)
            out.toByteArray()
        } ?: return@withContext null
        VaultReadResult(
            relativePath = norm,
            text = bytes.toString(Charsets.UTF_8),
            sha256 = VaultPathValidator.sha256(bytes),
            lastModifiedMs = doc.lastModified()
        )
    }

    override suspend fun readBytes(relativePath: String): ByteArray? = withContext(Dispatchers.IO) {
        val norm = VaultPathValidator.normalize(relativePath)
        val doc = findDocument(norm) ?: return@withContext null
        if (doc.isDirectory) return@withContext null
        context.contentResolver.openInputStream(doc.uri)?.use { input ->
            val out = ByteArrayOutputStream()
            input.copyTo(out)
            out.toByteArray()
        }
    }

    override suspend fun writeTextSafe(
        relativePath: String,
        content: String,
        expectedBaseSha256: String?
    ): WriteResult = withContext(Dispatchers.IO) {
        val norm = VaultPathValidator.normalize(relativePath)
        val existing = findDocument(norm)
        if (existing != null && expectedBaseSha256 != null) {
            val currentRead = readText(norm)
            if (currentRead != null && currentRead.sha256 != expectedBaseSha256) {
                return@withContext WriteResult.Conflict(
                    relativePath = norm,
                    expectedSha256 = expectedBaseSha256,
                    externalSha256 = currentRead.sha256,
                    externalContent = currentRead.text,
                    pendingContent = content
                )
            }
        }
        writeTextForce(norm, content)
    }

    override suspend fun writeTextForce(relativePath: String, content: String): WriteResult = withContext(Dispatchers.IO) {
        val norm = VaultPathValidator.normalize(relativePath)
        val parentPath = VaultPathValidator.parentOf(norm)
        val fileName = VaultPathValidator.nameOf(norm)
        val parent = ensureFolderDoc(parentPath)
            ?: return@withContext WriteResult.Error("Could not create parent folder: $parentPath")
        val target = parent.findFile(fileName)
            ?: parent.createFile(mimeFor(fileName), fileName)
            ?: return@withContext WriteResult.Error("Could not create file: $norm")
        val bytes = content.toByteArray(Charsets.UTF_8)
        try {
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
                out.write(bytes)
                out.flush()
            } ?: return@withContext WriteResult.Error("Failed opening output stream: $norm")
            WriteResult.Success(
                newSha256 = VaultPathValidator.sha256(bytes),
                newLastModifiedMs = target.lastModified()
            )
        } catch (e: Exception) {
            WriteResult.Error(e.message ?: "Write error for $norm")
        }
    }

    override suspend fun writeBytes(relativePath: String, bytes: ByteArray, mimeType: String): Boolean = withContext(Dispatchers.IO) {
        val norm = VaultPathValidator.normalize(relativePath)
        val parentPath = VaultPathValidator.parentOf(norm)
        val fileName = VaultPathValidator.nameOf(norm)
        val parent = ensureFolderDoc(parentPath) ?: return@withContext false
        val target = parent.findFile(fileName) ?: parent.createFile(mimeType, fileName) ?: return@withContext false
        try {
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
                out.write(bytes)
                out.flush()
            } ?: return@withContext false
            true
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun createFolder(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        ensureFolderDoc(relativePath) != null
    }

    override suspend fun deleteFile(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        val doc = findDocument(relativePath) ?: return@withContext false
        doc.delete()
    }

    override suspend fun listChildren(relativePath: String): List<VaultFileEntry> = withContext(Dispatchers.IO) {
        val norm = VaultPathValidator.normalize(relativePath)
        val dir = findDocument(norm) ?: return@withContext emptyList()
        if (!dir.isDirectory) return@withContext emptyList()
        dir.listFiles().mapNotNull { child ->
            val name = child.name ?: return@mapNotNull null
            val childPath = if (norm.isEmpty()) name else "$norm/$name"
            VaultFileEntry(
                relativePath = childPath,
                name = name,
                isDirectory = child.isDirectory,
                sizeBytes = child.length(),
                lastModifiedMs = child.lastModified(),
                mimeType = child.type ?: mimeFor(name)
            )
        }.sortedWith(compareBy<VaultFileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
    }

    override suspend fun listRecursive(
        rootRelativePath: String,
        excludedPrefixes: Set<String>
    ): List<VaultFileEntry> = withContext(Dispatchers.IO) {
        val normRoot = VaultPathValidator.normalize(rootRelativePath)
        val normExcludes = excludedPrefixes.map { VaultPathValidator.normalize(it) }.filter { it.isNotEmpty() }.toSet()
        val result = mutableListOf<VaultFileEntry>()
        fun walk(dir: DocumentFile, currentRel: String) {
            for (child in dir.listFiles()) {
                val name = child.name ?: continue
                if (name.startsWith(".obsidian") || name == ".git" || name == ".trash") continue
                val childRel = if (currentRel.isEmpty()) name else "$currentRel/$name"
                if (normExcludes.any { childRel == it || childRel.startsWith("$it/") }) continue
                val entry = VaultFileEntry(
                    relativePath = childRel,
                    name = name,
                    isDirectory = child.isDirectory,
                    sizeBytes = child.length(),
                    lastModifiedMs = child.lastModified(),
                    mimeType = child.type ?: mimeFor(name)
                )
                result.add(entry)
                if (child.isDirectory) {
                    walk(child, childRel)
                }
            }
        }
        val start = findDocument(normRoot) ?: return@withContext emptyList()
        if (start.isDirectory) walk(start, normRoot)
        result
    }

    override fun resolveContentUri(relativePath: String): Uri? = findDocument(relativePath)?.uri
}

class InMemoryVaultGateway(
    initialFiles: Map<String, ByteArray> = emptyMap()
) : VaultGateway {
    private data class Stored(val bytes: ByteArray, val modifiedMs: Long)

    private val files = ConcurrentHashMap<String, Stored>()
    private val folders = ConcurrentHashMap.newKeySet<String>()
    private var clock = 1_700_000_000_000L

    init {
        initialFiles.forEach { (path, bytes) ->
            val norm = VaultPathValidator.normalize(path)
            ensureParents(norm)
            files[norm] = Stored(bytes, ++clock)
        }
    }

    private fun ensureParents(normFile: String) {
        var parent = VaultPathValidator.parentOf(normFile)
        while (parent.isNotEmpty()) {
            folders.add(parent)
            parent = VaultPathValidator.parentOf(parent)
        }
    }

    private fun mimeFor(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "md" -> "text/markdown"
            "json" -> "application/json"
            "js" -> "application/javascript"
            "css" -> "text/css"
            "png" -> "image/png"
            else -> "application/octet-stream"
        }
    }

    override suspend fun exists(relativePath: String): Boolean {
        val norm = VaultPathValidator.normalize(relativePath)
        return norm.isEmpty() || files.containsKey(norm) || folders.contains(norm)
    }

    override suspend fun isDirectory(relativePath: String): Boolean {
        val norm = VaultPathValidator.normalize(relativePath)
        return norm.isEmpty() || folders.contains(norm)
    }

    override suspend fun stat(relativePath: String): VaultFileEntry? {
        val norm = VaultPathValidator.normalize(relativePath)
        if (folders.contains(norm) || norm.isEmpty()) {
            return VaultFileEntry(norm, VaultPathValidator.nameOf(norm), true, 0L, clock, "vnd.android.document/directory")
        }
        val stored = files[norm] ?: return null
        val name = VaultPathValidator.nameOf(norm)
        return VaultFileEntry(norm, name, false, stored.bytes.size.toLong(), stored.modifiedMs, mimeFor(name))
    }

    override suspend fun readText(relativePath: String): VaultReadResult? {
        val norm = VaultPathValidator.normalize(relativePath)
        val stored = files[norm] ?: return null
        return VaultReadResult(
            relativePath = norm,
            text = stored.bytes.toString(Charsets.UTF_8),
            sha256 = VaultPathValidator.sha256(stored.bytes),
            lastModifiedMs = stored.modifiedMs
        )
    }

    override suspend fun readBytes(relativePath: String): ByteArray? =
        files[VaultPathValidator.normalize(relativePath)]?.bytes

    override suspend fun writeTextSafe(
        relativePath: String,
        content: String,
        expectedBaseSha256: String?
    ): WriteResult {
        val norm = VaultPathValidator.normalize(relativePath)
        val existing = files[norm]
        if (existing != null && expectedBaseSha256 != null) {
            val currentHash = VaultPathValidator.sha256(existing.bytes)
            if (currentHash != expectedBaseSha256) {
                return WriteResult.Conflict(
                    relativePath = norm,
                    expectedSha256 = expectedBaseSha256,
                    externalSha256 = currentHash,
                    externalContent = existing.bytes.toString(Charsets.UTF_8),
                    pendingContent = content
                )
            }
        }
        return writeTextForce(norm, content)
    }

    override suspend fun writeTextForce(relativePath: String, content: String): WriteResult {
        val norm = VaultPathValidator.normalize(relativePath)
        ensureParents(norm)
        val bytes = content.toByteArray(Charsets.UTF_8)
        val ts = ++clock
        files[norm] = Stored(bytes, ts)
        return WriteResult.Success(VaultPathValidator.sha256(bytes), ts)
    }

    override suspend fun writeBytes(relativePath: String, bytes: ByteArray, mimeType: String): Boolean {
        val norm = VaultPathValidator.normalize(relativePath)
        ensureParents(norm)
        files[norm] = Stored(bytes, ++clock)
        return true
    }

    override suspend fun createFolder(relativePath: String): Boolean {
        val norm = VaultPathValidator.normalize(relativePath)
        if (norm.isNotEmpty()) {
            ensureParents("$norm/placeholder")
            folders.add(norm)
        }
        return true
    }

    override suspend fun deleteFile(relativePath: String): Boolean {
        val norm = VaultPathValidator.normalize(relativePath)
        val removed = files.remove(norm) != null
        if (removed) return true
        if (folders.remove(norm)) {
            files.keys.filter { it.startsWith("$norm/") }.forEach { files.remove(it) }
            folders.filter { it.startsWith("$norm/") }.forEach { folders.remove(it) }
            return true
        }
        return false
    }

    override suspend fun listChildren(relativePath: String): List<VaultFileEntry> {
        val norm = VaultPathValidator.normalize(relativePath)
        val prefix = if (norm.isEmpty()) "" else "$norm/"
        val seen = linkedMapOf<String, VaultFileEntry>()
        folders.filter { it.startsWith(prefix) && it.length > prefix.length }.forEach { f ->
            val rest = f.removePrefix(prefix)
            val seg = rest.substringBefore('/')
            val path = "$prefix$seg"
            seen.putIfAbsent(path, VaultFileEntry(path, seg, true, 0L, clock, "vnd.android.document/directory"))
        }
        files.entries.filter { it.key.startsWith(prefix) }.forEach { (k, stored) ->
            val rest = k.removePrefix(prefix)
            if (!rest.contains('/')) {
                seen[k] = VaultFileEntry(k, rest, false, stored.bytes.size.toLong(), stored.modifiedMs, mimeFor(rest))
            }
        }
        return seen.values.sortedWith(compareBy<VaultFileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
    }

    override suspend fun listRecursive(rootRelativePath: String, excludedPrefixes: Set<String>): List<VaultFileEntry> {
        val normRoot = VaultPathValidator.normalize(rootRelativePath)
        val prefix = if (normRoot.isEmpty()) "" else "$normRoot/"
        val excludes = excludedPrefixes.map { VaultPathValidator.normalize(it) }.filter { it.isNotEmpty() }.toSet()
        fun excluded(p: String) = excludes.any { p == it || p.startsWith("$it/") }

        val out = mutableListOf<VaultFileEntry>()
        folders.filter { it.startsWith(prefix) && !excluded(it) }.forEach { f ->
            out.add(VaultFileEntry(f, VaultPathValidator.nameOf(f), true, 0L, clock, "vnd.android.document/directory"))
        }
        files.entries.filter { it.key.startsWith(prefix) && !excluded(it.key) }.forEach { (k, stored) ->
            val name = VaultPathValidator.nameOf(k)
            out.add(VaultFileEntry(k, name, false, stored.bytes.size.toLong(), stored.modifiedMs, mimeFor(name)))
        }
        return out.sortedBy { it.relativePath }
    }

    override fun resolveContentUri(relativePath: String): Uri? = null
}
