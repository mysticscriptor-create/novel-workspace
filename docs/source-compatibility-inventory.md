# Source Compatibility Inventory (`mysticscriptor-create/Devour-System-`)

Audit derived by inspecting `https://github.com/mysticscriptor-create/Devour-System-` (branch `main`).

## 1. Confirmed Repository Contents vs. Runtime Vault Dependencies

### Confirmed in Example Repository
- **Main Dashboards (7)**:
  - `Untitled 11.md` (Devour System Character HUD, 1,007 lines)
  - `_WORLD 1.md` (Modular World Dashboard)
  - `_Overseer_Database.md` (Dimensional Overseer Character Database)
  - `_INHERITANCE.md` (Zenith Inheritance Interface)
  - `_Corebearer Cal.md` (Corebearer Progression Calculator)
  - `prowess-tracker.md` (Chapter Analysis & Prowess Tracker, 3,735 lines)
  - `Prowess editor.md` (Prowess Editor Launcher)
- **JavaScript Modules (14 in `Sub-js/`)**: `calendar.js`, `commissions.js`, `dungeons.js`, `economy.js`, `factions.js`, `generate.js`, `guilds.js`, `LMCA.js`, `marc_prowess.js`, `market.js`, `prowess_editor_v2.js`, `prowess_v4.js`, `regions.js`, `shop.js`
- **CSS Files (24 in `Css/`)**: `theme-5-apex.css`, `overseer-structure.css`, `overseer-theme-4-forge.css`, `prowess-grimoire.css`, `LMCA.css`, `_MKT_*.css`, `full_page.css`, `A dataviewjs.css`, etc.
- **JSON Files (14)**: `World_Json/{currency-reference,economy-reference,points,region-config,region-lore,shops-config,world-config,devour-state}.json` plus root caches (`prowess_base.json`, `prowess_cache.json`, `prowess_icon_modes.json`, `lmca_data.json`, `overseer_sigil_cache.json`, `devour-prompts.json`).

### Obtained from Authoritative User Vault at Runtime
- Trait notes queried via `dv.pages('"000 A System/Traits"')`
- Character notes queried via `dv.pages('"character"')`
- System state notes: `00 System Human`, `00 System Hybrid`, `000 A System/00 System Hybrid.md`, `000 A System/MARC/00 Zenith File.md`, `000 A System/0 MASTER TABLE`
- Inventory/Commissions notes: `Inventory`, `Inventory/Services/Commission`
- Script/config path aliases expected by dashboards: `Meta/Scripts/*` (mapped to `Sub-js/*` or user path), `System/modules/*`, `config/*.json` (mapped to `World_Json/*`), `Prowess/*`

## 2. API Usage Inventory & Compatibility Matrix

| API / Feature | Status | Notes & Adapters |
|---|---|---|
| `dv.container`, `dv.el(tag, text, opts)` | **Supported** | Scoped DOM mount inside isolated page container |
| `dv.page(path)` | **Supported** | Resolves note frontmatter + `file.{name,path,folder,tags,etags,links}` |
| `dv.pages(query)` | **Supported** | Supports folder (`"folder"`), `#tag`, boolean/comma clauses, and `DataArray` |
| `DataArray` (`.array`, `.filter`, `.map`, `.sort`, `.where`, `.groupBy`, `.slice`, `.find`, `.forEach`, `.some`, `.every`, `.reduce`, `.length`) | **Supported** | Implemented in `obsidian_dataview_shim.js` with array indexing proxy |
| `dv.view(path, input)` | **Supported** | Resolves `.js` via configured path aliases and passes `input` + async result |
| `app.vault.getAbstractFileByPath(p)` | **Supported** | Returns `TFile` or `TFolder` (including `.children`) via SAF index |
| `app.vault.read` / `cachedRead` / `modify` / `create` / `createFolder` / `createBinary` / `delete` | **Supported** | Executed through validated `VaultGateway` with conflict protection |
| `app.vault.getResourcePath(file)` | **Supported** | Maps to `https://appassets.androidplatform.net/vault/...` via `WebViewAssetLoader` |
| `app.vault.adapter.{exists,read,write,remove}` | **Supported** | Bridged to `VaultGateway` using path alias resolution |
| `app.fileManager.processFrontMatter(file, fn)` | **Supported** | Surgical YAML mutation preserving unknown keys and Markdown body |
| `app.workspace.getLeaf().openFile(file)` | **Supported** | Opens native Note Viewer/Editor inside Novel Workspace |
| `Notice(msg, timeout)` | **Supported** | Native Compose snackbar/toast bridge |
| `navigator.clipboard.writeText` | **Supported** | Native `ClipboardManager` bridge |
| `localStorage` | **Supported** | Page-scoped isolated storage persisted per Custom Page |
| `moment` | **Partial** | Lightweight formatting/date helper shim for common dashboard calls |
| `requestUrl(opts)` | **Supported (Permitted)** | Proxied via Kotlin `NativeNetworkProxy`; injects Keystore AI keys when configured |
| External AI APIs (`generativelanguage.googleapis.com`, `api.groq.com`, `openrouter.ai`) | **Supported (Opt-In)** | Requires per-page network permission + user-configured Keystore credentials |
| Unsupported Obsidian Plugin APIs | **Diagnostic** | Proxy emits explicit `[Compatibility Diagnostic]` error in Studio console |
