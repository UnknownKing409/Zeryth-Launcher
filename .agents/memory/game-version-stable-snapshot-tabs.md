---
name: Game Version selector Stable/Snapshot tabs
description: Where the Stable/Snapshot split lives in the download Game Version filter, and why earlier attempts visually failed before the final layout fixed it.
---

The Download screens' shared Game Version filter (`GameVersionFilterLayout` in
`_Search.Filter.kt`, used by Mods/Modpacks/Resource Packs/Shader Packs via `SearchFilter`)
already separates **Stable** (`MinecraftVersion.Type.Release`) from **Snapshots** (everything
else) inside the single existing selector, reusing `MinecraftVersions.allVersions` /
`refreshVersions(force=false)` (no duplicate manifest downloads — that function already
short-circuits if versions are loaded) and the existing `FilterListItem` adapter.

**Why earlier attempts visually failed:** several iterations tried a `SegmentedButtonRow`,
then `FilterChip`, then a plain `Surface+Text` tab row, each placed as a **sibling** of the
inner `LazyColumn` (inside an outer `Column`/`AnimatedVisibility`). Compose's height
measurement for a `Column` containing an `AnimatedVisibility { LazyColumn(heightIn(max=...)) }`
plus a sibling tab row above it kept producing a tab row that never actually rendered/expanded
correctly, even though every version compiled and passed CI (CI only proves it builds, not that
the layout renders as intended — there's no local Android SDK/emulator here to screenshot it).

**How to apply:** the fix that finally stuck was making the tab row the **first `item {}` of
the same inner `LazyColumn`** that renders the version rows, instead of a sibling composable
outside it — same rendering context that was already known to work for the version list. If a
future task needs to add another switcher/header row above a `LazyColumn` filter list in this
file, put it inside the list as a leading item rather than as a sibling in an outer `Column`.
