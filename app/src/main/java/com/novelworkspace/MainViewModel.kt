package com.novelworkspace

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.novelworkspace.compatibility.DataviewQueryEngine
import com.novelworkspace.compatibility.NativeNetworkProxy
import com.novelworkspace.core.markdown.MarkdownEngine
import com.novelworkspace.core.security.AppThemeMode
import com.novelworkspace.core.security.HomeSectionPrefs
import com.novelworkspace.core.security.VaultConfigStore
import com.novelworkspace.core.security.VaultConfiguration
import com.novelworkspace.core.sync.VaultSyncEngine
import com.novelworkspace.core.vault.SafVaultGateway
import com.novelworkspace.core.vault.VaultGateway
import com.novelworkspace.core.vault.WriteResult
import com.novelworkspace.dashboard.ConsoleEntry
import com.novelworkspace.dashboard.CustomPageManager
import com.novelworkspace.dashboard.DataviewImporter
import com.novelworkspace.dashboard.DependencyReport
import com.novelworkspace.data.database.AppDatabase
import com.novelworkspace.data.database.RecentAccessEntity
import com.novelworkspace.data.database.SyncErrorEntity
import com.novelworkspace.data.database.VaultFileEntity
import com.novelworkspace.data.models.ChapterRecord
import com.novelworkspace.data.models.CharacterRecord
import com.novelworkspace.data.models.CustomPageManifest
import com.novelworkspace.data.models.SearchFilterScope
import com.novelworkspace.data.models.SearchHit
import com.novelworkspace.data.models.SyncReport
import com.novelworkspace.data.models.TraitRecord
import com.novelworkspace.data.models.WorldRecord
import com.novelworkspace.data.repositories.NovelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NoteEditorState(
    val relativePath: String,
    val content: String,
    val baseSha256: String?,
    val isDirty: Boolean = false
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    val configStore = VaultConfigStore(application)
    private val db = AppDatabase.getInstance(application)
    val dao = db.vaultDao()
    val syncEngine = VaultSyncEngine(dao)
    val repository = NovelRepository(dao, syncEngine)
    val queryEngine = DataviewQueryEngine(dao)
    val networkProxy = NativeNetworkProxy(configStore)

    private val _config = MutableStateFlow(configStore.load())
    val config: StateFlow<VaultConfiguration> = _config.asStateFlow()

    private val _gateway = MutableStateFlow<VaultGateway?>(createGateway(_config.value.treeUriString))
    val gateway: StateFlow<VaultGateway?> = _gateway.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _lastSyncReport = MutableStateFlow<SyncReport?>(null)
    val lastSyncReport: StateFlow<SyncReport?> = _lastSyncReport.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices = _notices.asSharedFlow()

    private val _openNote = MutableStateFlow<NoteEditorState?>(null)
    val openNote: StateFlow<NoteEditorState?> = _openNote.asStateFlow()

    private val _pendingConflict = MutableStateFlow<WriteResult.Conflict?>(null)
    val pendingConflict: StateFlow<WriteResult.Conflict?> = _pendingConflict.asStateFlow()

    private val _customPages = MutableStateFlow<List<CustomPageManifest>>(emptyList())
    val customPages: StateFlow<List<CustomPageManifest>> = _customPages.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchScope = MutableStateFlow(SearchFilterScope.ALL)
    val searchScope: StateFlow<SearchFilterScope> = _searchScope.asStateFlow()

    val searchResults: StateFlow<List<SearchHit>> =
        _searchQuery.debounce(180)
            .flatMapLatest { q ->
                _searchScope.flatMapLatest { scope ->
                    kotlinx.coroutines.flow.flow {
                        emit(repository.search(q, scope))
                    }
                }
            }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allFiles: StateFlow<List<VaultFileEntity>> =
        repository.observeAllFiles().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentAccess: StateFlow<List<RecentAccessEntity>> =
        repository.observeRecentAccess().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentlyModified: StateFlow<List<VaultFileEntity>> =
        repository.observeRecentlyModified().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val syncErrors: StateFlow<List<SyncErrorEntity>> =
        repository.observeErrors().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val characters: StateFlow<List<CharacterRecord>> =
        repository.observeCharacters(_config).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val traits: StateFlow<List<TraitRecord>> =
        repository.observeTraits(_config).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val worldRecords: StateFlow<List<WorldRecord>> =
        repository.observeWorldRecords(_config).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val chapters: StateFlow<List<ChapterRecord>> =
        repository.observeChapters(_config).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        if (_gateway.value != null) {
            VaultSyncEngine.schedulePeriodicSync(application)
            refreshCustomPages()
        }
    }

    private fun createGateway(uriString: String?): VaultGateway? {
        if (uriString.isNullOrBlank()) return null
        return SafVaultGateway(getApplication(), Uri.parse(uriString))
    }

    fun onVaultTreeSelected(uri: Uri) {
        val ctx = getApplication<Application>()
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, flags) }
        val updated = configStore.update { it.copy(treeUriString = uri.toString()) }
        _config.value = updated
        _gateway.value = createGateway(updated.treeUriString)
        VaultSyncEngine.schedulePeriodicSync(ctx)
        triggerSync(forceRebuild = false)
    }

    fun onAppForegrounded() {
        if (_gateway.value != null && !_isSyncing.value) {
            triggerSync(forceRebuild = false)
        }
    }

    fun triggerSync(forceRebuild: Boolean = false) {
        val gw = _gateway.value ?: return
        if (_isSyncing.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isSyncing.value = true
            try {
                val report = syncEngine.syncIncremental(gw, _config.value, forceFullRebuild = forceRebuild)
                _lastSyncReport.value = report
                val updated = configStore.update { it.copy(lastSyncTimeMs = System.currentTimeMillis()) }
                _config.value = updated
                refreshCustomPagesInternal(gw)
            } catch (e: Exception) {
                emitNotice("Sync failed: ${e.message}")
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun clearIndexCache() {
        viewModelScope.launch(Dispatchers.IO) {
            dao.clearAll()
            emitNotice("Index cache cleared. Re-indexing...")
            triggerSync(forceRebuild = true)
        }
    }

    fun emitNotice(msg: String) {
        _notices.tryEmit(msg)
    }

    fun openNoteByPath(relativePath: String) {
        val gw = _gateway.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val read = repository.readLiveNote(gw, relativePath)
            if (read == null) {
                emitNotice("File not found: $relativePath")
                return@launch
            }
            val title = relativePath.substringAfterLast('/').substringBeforeLast('.')
            repository.recordAccess(relativePath, title)
            _openNote.value = NoteEditorState(
                relativePath = relativePath,
                content = read.text,
                baseSha256 = read.sha256,
                isDirty = false
            )
        }
    }

    fun openWikilink(targetName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val match = repository.resolveWikilink(targetName)
            if (match != null) {
                openNoteByPath(match.relativePath)
            } else {
                emitNotice("Note not found for wikilink [[$targetName]]")
            }
        }
    }

    fun updateEditorContent(text: String) {
        _openNote.value = _openNote.value?.copy(content = text, isDirty = true)
    }

    fun closeNoteEditor() {
        _openNote.value = null
    }

    fun saveOpenNote() {
        val gw = _gateway.value ?: return
        val editor = _openNote.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            when (val res = repository.saveNoteSafe(gw, editor.relativePath, editor.content, editor.baseSha256)) {
                is WriteResult.Success -> {
                    _openNote.value = editor.copy(baseSha256 = res.newSha256, isDirty = false)
                    emitNotice("Saved ${editor.relativePath.substringAfterLast('/')}")
                }
                is WriteResult.Conflict -> _pendingConflict.value = res
                is WriteResult.Error -> emitNotice("Save error: ${res.message}")
            }
        }
    }

    fun updateOpenNoteFrontmatterProperty(key: String, value: String) {
        val gw = _gateway.value ?: return
        val editor = _openNote.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val res = repository.updateFrontmatterSafe(gw, editor.relativePath, editor.baseSha256) { map ->
                if (value.isBlank()) map.remove(key) else map[key] = value
            }
            when (res) {
                is WriteResult.Success -> {
                    val fresh = repository.readLiveNote(gw, editor.relativePath)
                    if (fresh != null) {
                        _openNote.value = NoteEditorState(fresh.relativePath, fresh.text, fresh.sha256, false)
                    }
                    emitNotice("Updated property '$key'")
                }
                is WriteResult.Conflict -> _pendingConflict.value = res
                is WriteResult.Error -> emitNotice(res.message)
            }
        }
    }

    fun resolveConflictKeepExternal() {
        val c = _pendingConflict.value ?: return
        _openNote.value = NoteEditorState(c.relativePath, c.externalContent, c.externalSha256, false)
        _pendingConflict.value = null
        emitNotice("Loaded external vault version")
    }

    fun resolveConflictOverwriteMine() {
        val gw = _gateway.value ?: return
        val c = _pendingConflict.value ?: return
        _pendingConflict.value = null
        viewModelScope.launch(Dispatchers.IO) {
            when (val res = repository.saveNoteForce(gw, c.relativePath, c.pendingContent)) {
                is WriteResult.Success -> {
                    _openNote.value = NoteEditorState(c.relativePath, c.pendingContent, res.newSha256, false)
                    emitNotice("Saved your changes")
                }
                is WriteResult.Error -> emitNotice(res.message)
                else -> Unit
            }
        }
    }

    fun dismissConflict() {
        _pendingConflict.value = null
    }

    fun createMarkdownNote(relativePath: String, initialContent: String) {
        val gw = _gateway.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            when (val res = gw.writeTextForce(relativePath, initialContent)) {
                is WriteResult.Success -> {
                    syncEngine.reindexSingleFile(gw, relativePath)
                    openNoteByPath(relativePath)
                }
                is WriteResult.Error -> emitNotice(res.message)
                else -> Unit
            }
        }
    }

    fun createFolder(relativePath: String) {
        val gw = _gateway.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (gw.createFolder(relativePath)) {
                emitNotice("Created folder $relativePath")
            } else {
                emitNotice("Could not create folder $relativePath")
            }
        }
    }

    fun deleteVaultFile(relativePath: String) {
        val gw = _gateway.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (gw.deleteFile(relativePath)) {
                dao.removeFileIndex(relativePath)
                if (_openNote.value?.relativePath == relativePath) _openNote.value = null
                emitNotice("Deleted $relativePath")
            } else {
                emitNotice("Failed deleting $relativePath")
            }
        }
    }

    fun refreshCustomPages() {
        val gw = _gateway.value ?: return
        viewModelScope.launch(Dispatchers.IO) { refreshCustomPagesInternal(gw) }
    }

    private suspend fun refreshCustomPagesInternal(gw: VaultGateway) {
        val mgr = CustomPageManager(gw, _config.value.customPagesRoot)
        _customPages.value = mgr.listPages()
    }

    fun pageManagerOrNull(): CustomPageManager? {
        val gw = _gateway.value ?: return null
        return CustomPageManager(gw, _config.value.customPagesRoot)
    }

    suspend fun scanPageDependencies(js: String): DependencyReport {
        val raw = DataviewImporter.scanDependencies(js)
        val gw = _gateway.value ?: return raw
        return DataviewImporter.resolveWithVault(raw, gw, _config.value)
    }

    fun setSearchQuery(q: String) { _searchQuery.value = q }
    fun setSearchScope(s: SearchFilterScope) { _searchScope.value = s }

    fun updateConfig(transform: (VaultConfiguration) -> VaultConfiguration) {
        _config.value = configStore.update(transform)
    }

    fun toggleFavouriteFolder(path: String) {
        updateConfig { cfg ->
            val next = cfg.favouriteFolders.toMutableSet()
            if (!next.add(path)) next.remove(path)
            cfg.copy(favouriteFolders = next)
        }
    }

    fun toggleFavouriteCharacter(path: String) {
        updateConfig { cfg ->
            val next = cfg.favouriteNotePaths.toMutableSet()
            if (!next.add(path)) next.remove(path)
            cfg.copy(favouriteNotePaths = next)
        }
    }

    fun toggleFavouritePage(pageId: String) {
        updateConfig { cfg ->
            val next = cfg.favouritePageIds.toMutableSet()
            if (!next.add(pageId)) next.remove(pageId)
            cfg.copy(favouritePageIds = next)
        }
    }

    fun updateHomePrefs(prefs: HomeSectionPrefs) {
        updateConfig { it.copy(homePrefs = prefs) }
    }

    fun setThemeMode(mode: AppThemeMode) {
        updateConfig { it.copy(themeMode = mode) }
    }
}
