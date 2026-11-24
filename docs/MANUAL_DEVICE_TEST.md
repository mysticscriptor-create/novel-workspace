# Manual Device Verification Checklist (Android 9+ / API 28+)

1. **Install APK**: Download `novel-workspace-debug-apk` from GitHub Actions artifacts and install on Android 9+ device.
2. **Connect Vault**: Launch app, grant SAF access to your Obsidian vault folder, confirm included/excluded folders, and verify initial index completion.
3. **External Obsidian Sync**: Edit a Trait or Character note in Obsidian, return to Novel Workspace, verify foreground auto-sync updates the record without rebuilding the whole vault.
4. **Conflict Protection**: Load a note in Novel Workspace Editor, modify the same note externally, then tap Save in Novel Workspace; verify the Conflict Resolution dialog displays both versions and preserves unknown YAML keys.
5. **Native Workspace**: Browse Characters, filter Traits by rank/layer/form/synergy, follow wikilinks between Worldbuilding entities, and search globally.
6. **Custom Page Studio & Devour HUD**: Import `Untitled 11.md` + `Css/theme-5-apex.css`, configure folder aliases (`Meta/Scripts/` -> `Sub-js/`, `config/` -> `World_Json/`), preview live, test Human/Hybrid switch, trait modal, copy button, and frontmatter write-back.
