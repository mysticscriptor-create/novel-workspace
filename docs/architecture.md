# Novel Workspace — Architecture

## Core Principles
1. **Obsidian Vault as Single Source of Truth**: All novel notes, traits, characters, JSON databases, and custom pages reside in the user-selected Storage Access Framework (SAF) vault folder. Room/SQLite is strictly a derived, rebuildable index and search cache.
2. **Android 9 (API 28) Compatibility**: `minSdk = 28`, `targetSdk = 35`. Uses `DocumentFile`, `WebViewAssetLoader`, `WebMessagePortCompat`, `WorkManager`, and `EncryptedSharedPreferences` (`MasterKey`) with full API 28 support.
3. **Hybrid Native + Isolated WebView Separation**:
   - Native Jetpack Compose UI handles navigation, library browsing, character/trait/worldbuilding managers, timeline, search, settings, and code editing.
   - Android `WebView` is mounted strictly per active Custom Page inside an isolated `app://vault/` origin with explicit `WebMessagePort` RPC, path validation, and per-page network/write permissions.
4. **Conflict-Safe Write-Back**: Before overwriting any `.md` or `.json` file via native editors or `app.fileManager.processFrontMatter`, the SAF gateway verifies the live SHA-256 content hash against the loaded baseline hash and surfaces a side-by-side Conflict Resolution dialog on mismatch.
