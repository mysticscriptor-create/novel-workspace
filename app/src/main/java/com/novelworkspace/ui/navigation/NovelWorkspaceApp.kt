package com.novelworkspace.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.novelworkspace.MainViewModel
import com.novelworkspace.dashboard.CustomPageStudioScreen
import com.novelworkspace.data.models.CustomPageManifest
import com.novelworkspace.features.characters.CharactersScreen
import com.novelworkspace.features.home.HomeScreen
import com.novelworkspace.features.library.LibraryBrowserScreen
import com.novelworkspace.features.library.NoteEditorScreen
import com.novelworkspace.features.onboarding.OnboardingScreen
import com.novelworkspace.features.search.SearchScreen
import com.novelworkspace.features.settings.SettingsScreen
import com.novelworkspace.features.traits.TraitsScreen
import com.novelworkspace.features.world.TimelineScreen
import com.novelworkspace.features.world.WorldbuildingScreen
import com.novelworkspace.ui.components.ConflictResolutionDialog
import com.novelworkspace.ui.theme.NovelWorkspaceTheme

@Composable
fun NovelWorkspaceApp(
    viewModel: MainViewModel,
    onLaunchVaultPicker: () -> Unit
) {
    val config by viewModel.config.collectAsState()
    val gateway by viewModel.gateway.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val lastReport by viewModel.lastSyncReport.collectAsState()
    val allFiles by viewModel.allFiles.collectAsState()
    val recentAccess by viewModel.recentAccess.collectAsState()
    val recentlyModified by viewModel.recentlyModified.collectAsState()
    val syncErrors by viewModel.syncErrors.collectAsState()
    val characters by viewModel.characters.collectAsState()
    val traits by viewModel.traits.collectAsState()
    val worldRecords by viewModel.worldRecords.collectAsState()
    val chapters by viewModel.chapters.collectAsState()
    val customPages by viewModel.customPages.collectAsState()
    val openNote by viewModel.openNote.collectAsState()
    val pendingConflict by viewModel.pendingConflict.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchScope by viewModel.searchScope.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.notices.collect { msg -> snackbarHostState.showSnackbar(msg) }
    }

    // Tabs: 0=Home, 1=Library, 2=Database, 3=Studio, 4=Settings, 5=Search (modal/tab)
    var selectedTab by remember { mutableIntStateOf(0) }
    var dbSubTab by remember { mutableIntStateOf(0) } // 0=Chars, 1=Traits, 2=World, 3=Timeline
    var showSearchOverlay by remember { mutableStateOf(false) }
    var pendingStudioPage by remember { mutableStateOf<CustomPageManifest?>(null) }

    NovelWorkspaceTheme(themeMode = config.themeMode) {
        if (gateway == null) {
            OnboardingScreen(onSelectVault = onLaunchVaultPicker)
            return@NovelWorkspaceTheme
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (openNote == null) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = !showSearchOverlay && selectedTab == 0,
                            onClick = { showSearchOverlay = false; selectedTab = 0 },
                            icon = { Icon(Icons.Outlined.Home, contentDescription = "Home") },
                            label = { Text("Home") }
                        )
                        NavigationBarItem(
                            selected = !showSearchOverlay && selectedTab == 1,
                            onClick = { showSearchOverlay = false; selectedTab = 1 },
                            icon = { Icon(Icons.Outlined.Folder, contentDescription = "Library") },
                            label = { Text("Library") }
                        )
                        NavigationBarItem(
                            selected = !showSearchOverlay && selectedTab == 2,
                            onClick = { showSearchOverlay = false; selectedTab = 2 },
                            icon = { Icon(Icons.Outlined.AutoStories, contentDescription = "Database") },
                            label = { Text("Database") }
                        )
                        NavigationBarItem(
                            selected = !showSearchOverlay && selectedTab == 3,
                            onClick = { showSearchOverlay = false; selectedTab = 3 },
                            icon = { Icon(Icons.Outlined.Code, contentDescription = "Studio") },
                            label = { Text("Studio") }
                        )
                        NavigationBarItem(
                            selected = !showSearchOverlay && selectedTab == 4,
                            onClick = { showSearchOverlay = false; selectedTab = 4 },
                            icon = { Icon(Icons.Outlined.Settings, contentDescription = "Settings") },
                            label = { Text("Settings") }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                when {
                    openNote != null -> {
                        NoteEditorScreen(
                            state = openNote!!,
                            onContentChange = viewModel::updateEditorContent,
                            onSave = viewModel::saveOpenNote,
                            onUpdateFrontmatter = viewModel::updateOpenNoteFrontmatterProperty,
                            onDelete = viewModel::deleteVaultFile,
                            onFollowWikilink = viewModel::openWikilink,
                            onBack = viewModel::closeNoteEditor
                        )
                    }
                    showSearchOverlay -> {
                        SearchScreen(
                            query = searchQuery,
                            scope = searchScope,
                            results = searchResults,
                            customPages = customPages,
                            onQueryChange = viewModel::setSearchQuery,
                            onScopeChange = viewModel::setSearchScope,
                            onOpenNote = { p -> showSearchOverlay = false; viewModel.openNoteByPath(p) },
                            onOpenPage = { page ->
                                showSearchOverlay = false
                                pendingStudioPage = page
                                selectedTab = 3
                            }
                        )
                    }
                    else -> when (selectedTab) {
                        0 -> HomeScreen(
                            config = config,
                            isSyncing = isSyncing,
                            recentAccess = recentAccess,
                            recentlyModified = recentlyModified,
                            characters = characters,
                            customPages = customPages,
                            chapters = chapters,
                            worldRecords = worldRecords,
                            traitCount = traits.size,
                            onRefreshSync = { viewModel.triggerSync(false) },
                            onOpenSearch = { showSearchOverlay = true },
                            onNavigateTab = { route ->
                                when (route) {
                                    "characters" -> { selectedTab = 2; dbSubTab = 0 }
                                    "traits" -> { selectedTab = 2; dbSubTab = 1 }
                                    "world" -> { selectedTab = 2; dbSubTab = 2 }
                                    "timeline" -> { selectedTab = 2; dbSubTab = 3 }
                                    "studio" -> selectedTab = 3
                                    "library" -> selectedTab = 1
                                }
                            },
                            onOpenNote = viewModel::openNoteByPath,
                            onOpenCustomPage = { page ->
                                pendingStudioPage = page
                                selectedTab = 3
                            },
                            onUpdateHomePrefs = viewModel::updateHomePrefs
                        )
                        1 -> LibraryBrowserScreen(
                            allFiles = allFiles,
                            config = config,
                            gateway = gateway,
                            onOpenNote = viewModel::openNoteByPath,
                            onCreateNote = viewModel::createMarkdownNote,
                            onCreateFolder = viewModel::createFolder,
                            onToggleFavFolder = viewModel::toggleFavouriteFolder
                        )
                        2 -> Column(modifier = Modifier.fillMaxSize()) {
                            TabRow(selectedTabIndex = dbSubTab, modifier = Modifier.fillMaxWidth()) {
                                Tab(selected = dbSubTab == 0, onClick = { dbSubTab = 0 }, text = { Text("Characters") })
                                Tab(selected = dbSubTab == 1, onClick = { dbSubTab = 1 }, text = { Text("Traits") })
                                Tab(selected = dbSubTab == 2, onClick = { dbSubTab = 2 }, text = { Text("World") })
                                Tab(selected = dbSubTab == 3, onClick = { dbSubTab = 3 }, text = { Text("Timeline") })
                            }
                            when (dbSubTab) {
                                0 -> CharactersScreen(
                                    characters = characters,
                                    config = config,
                                    gateway = gateway,
                                    onToggleFavourite = viewModel::toggleFavouriteCharacter,
                                    onOpenNote = viewModel::openNoteByPath,
                                    onFollowWikilink = viewModel::openWikilink
                                )
                                1 -> TraitsScreen(
                                    traits = traits,
                                    onOpenNote = viewModel::openNoteByPath,
                                    onFollowWikilink = viewModel::openWikilink
                                )
                                2 -> WorldbuildingScreen(
                                    records = worldRecords,
                                    onOpenNote = viewModel::openNoteByPath,
                                    onFollowWikilink = viewModel::openWikilink
                                )
                                3 -> TimelineScreen(
                                    chapters = chapters,
                                    onOpenNote = viewModel::openNoteByPath,
                                    onFollowWikilink = viewModel::openWikilink
                                )
                            }
                        }
                        3 -> CustomPageStudioScreen(
                            pages = customPages,
                            allFiles = allFiles,
                            gateway = gateway,
                            pageManager = viewModel.pageManagerOrNull(),
                            queryEngine = viewModel.queryEngine,
                            syncEngine = viewModel.syncEngine,
                            networkProxy = viewModel.networkProxy,
                            configStore = viewModel.configStore,
                            config = config,
                            initialOpenPage = pendingStudioPage,
                            onClearInitialPage = { pendingStudioPage = null },
                            onRefreshPages = viewModel::refreshCustomPages,
                            onToggleFavPage = viewModel::toggleFavouritePage,
                            onNotice = viewModel::emitNotice,
                            onOpenVaultFile = viewModel::openNoteByPath
                        )
                        4 -> SettingsScreen(
                            config = config,
                            configStore = viewModel.configStore,
                            isSyncing = isSyncing,
                            lastReport = lastReport,
                            indexedFileCount = allFiles.size,
                            syncErrors = syncErrors,
                            onReconnectVault = onLaunchVaultPicker,
                            onManualSync = { viewModel.triggerSync(false) },
                            onRebuildIndex = viewModel::clearIndexCache,
                            onUpdateConfig = viewModel::updateConfig,
                            onThemeChange = viewModel::setThemeMode,
                            onNotice = viewModel::emitNotice
                        )
                    }
                }
            }
        }

        pendingConflict?.let { conflict ->
            ConflictResolutionDialog(
                conflict = conflict,
                onKeepExternal = viewModel::resolveConflictKeepExternal,
                onOverwriteMine = viewModel::resolveConflictOverwriteMine,
                onDismiss = viewModel::dismissConflict
            )
        }
    }
}
