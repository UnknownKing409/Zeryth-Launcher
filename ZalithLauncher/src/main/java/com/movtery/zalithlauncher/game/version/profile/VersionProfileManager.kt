/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option 3) any later version.
 */

package com.movtery.zalithlauncher.game.version.profile

import com.movtery.zalithlauncher.game.account.AccountsManager
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.installed.VersionFolders
import com.movtery.zalithlauncher.game.version.installed.VersionsManager
import com.movtery.zalithlauncher.game.version.mod.isEnabled
import com.movtery.zalithlauncher.utils.GSON
import com.movtery.zalithlauncher.utils.logging.Logger
import java.io.File
import java.io.FileWriter
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "VersionProfileManager"
private const val PROFILE_FILE_NAME = "version.profiles"
private const val DISABLED_SUFFIX = ".disabled"
private const val SHADER_OPTION = "shaderPack"
private const val RESOURCE_PACK_OPTION = "resourcePacks"

/**
 * Stores profiles inside the installed version's launcher-owned `.zalith`
 * directory. All file operations are synchronized because profile switching
 * can be requested from both the dashboard and the version list.
 */
object VersionProfileManager {
    private val cache = ConcurrentHashMap<String, VersionProfileFile>()
    private val profileFileType: Type = object : TypeToken<VersionProfileFile>() {}.type
    private var profileChangeRevision = 0L
    private val _profileChanges = MutableStateFlow<VersionProfileChange?>(null)

    /** Emits after a profile selection or activation has applied new files. */
    val profileChanges = _profileChanges.asStateFlow()

    init {
        // Auto-capture the active profile whenever the selected account changes,
        // so that account switches are persisted into the current profile without
        // any manual "save" step from the user.
        AccountsManager.addOnAccountChangedListener { _ ->
            VersionsManager.currentVersion.value?.let { captureCurrentState(it) }
        }
    }

    fun listProfiles(version: Version): List<VersionProfile> =
        read(version).profiles

    fun activeProfile(version: Version): VersionProfile =
        read(version).let { file ->
            file.profiles.firstOrNull { it.name == file.activeProfile }
                ?: file.profiles.first()
        }

    fun activeProfileName(version: Version): String = activeProfile(version).name

    /**
     * Captures the current files and account into the active profile.
     * This is called before changing profiles and before changing instances.
     */
    @Synchronized
    fun captureCurrentState(version: Version) {
        val file = read(version)
        val active = file.profiles.firstOrNull { it.name == file.activeProfile }
            ?: return
        val updated = active.copyStateFrom(capture(version))
        write(version, file.copy(profiles = file.profiles.replaceProfile(updated)))
    }

    @Synchronized
    fun selectProfile(version: Version, name: String): Boolean {
        var file = read(version)
        val target = file.profiles.firstOrNull { it.name == name } ?: return false
        val current = VersionsManager.currentVersion.value
        if (current != null && current.getVersionName() == version.getVersionName()) {
            captureCurrentState(version)
            file = read(version)
            apply(target, version)
        }
        write(version, file.copy(activeProfile = target.name))
        notifyProfileChanged(version)
        return true
    }

    @Synchronized
    fun createProfile(version: Version, requestedName: String): VersionProfile? {
        var file = read(version)
        if (VersionsManager.currentVersion.value?.getVersionName() == version.getVersionName()) {
            captureCurrentState(version)
            file = read(version)
        }
        val name = uniqueName(file.profiles, requestedName)
        val profile = capture(version).copy(name = name)
        write(version, file.copy(activeProfile = name, profiles = file.profiles + profile))
        notifyProfileChanged(version)
        return profile
    }

    @Synchronized
    fun duplicateProfile(version: Version, sourceName: String): VersionProfile? {
        var file = read(version)
        val source = file.profiles.firstOrNull { it.name == sourceName } ?: return null
        val current = VersionsManager.currentVersion.value
        if (current?.getVersionName() == version.getVersionName()) {
            captureCurrentState(version)
            file = read(version)
        }
        val refreshedSource = file.profiles.firstOrNull { it.name == sourceName } ?: return null
        val profile = refreshedSource.copy(name = uniqueName(file.profiles, "${refreshedSource.name} Copy"))
        if (current?.getVersionName() == version.getVersionName()) {
            apply(profile, version)
        }
        write(version, file.copy(activeProfile = profile.name, profiles = file.profiles + profile))
        notifyProfileChanged(version)
        return profile
    }

    @Synchronized
    fun renameProfile(version: Version, oldName: String, requestedName: String): VersionProfile? {
        val file = read(version)
        val old = file.profiles.firstOrNull { it.name == oldName } ?: return null
        val newName = uniqueName(file.profiles.filterNot { it.name == oldName }, requestedName)
        val renamed = old.copy(name = newName)
        write(
            version,
            file.copy(
                activeProfile = if (file.activeProfile == oldName) newName else file.activeProfile,
                profiles = file.profiles.map { if (it.name == oldName) renamed else it }
            )
        )
        notifyProfileChanged(version)
        return renamed
    }

    @Synchronized
    fun deleteProfile(version: Version, name: String): Boolean {
        var file = read(version)
        if (file.profiles.size <= 1) return false
        if (VersionsManager.currentVersion.value?.getVersionName() == version.getVersionName()) {
            captureCurrentState(version)
            file = read(version)
        }
        val remaining = file.profiles.filterNot { it.name == name }
        if (remaining.size == file.profiles.size) return false
        val nextActive = if (file.activeProfile == name) remaining.first().name else file.activeProfile
        write(version, file.copy(activeProfile = nextActive, profiles = remaining))
        if (file.activeProfile == name && VersionsManager.currentVersion.value?.getVersionName() == version.getVersionName()) {
            apply(remaining.first(), version)
        }
        notifyProfileChanged(version)
        return true
    }

    /**
     * Called by VersionsManager after the current instance changes.
     */
    @Synchronized
    fun activate(version: Version?) {
        version ?: return
        ensure(version)
        apply(activeProfile(version), version)
        notifyProfileChanged(version)
    }

    private fun notifyProfileChanged(version: Version) {
        profileChangeRevision++
        _profileChanges.value = VersionProfileChange(
            versionPath = version.getVersionPath().absolutePath,
            revision = profileChangeRevision
        )
    }

    private fun capture(version: Version): VersionProfile {
        return VersionProfile(
            name = "",
            modStates = snapshotStates(VersionFolders.MOD.getDir(version.getGameDir())),
            resourcePackStates = snapshotStates(VersionFolders.RESOURCE_PACK.getDir(version.getGameDir())),
            resourcePackOrder = optionList(version, RESOURCE_PACK_OPTION),
            shaderStates = snapshotStates(VersionFolders.SHADERS.getDir(version.getGameDir())),
            selectedShader = optionValue(version, SHADER_OPTION)?.takeUnless { it.isEmpty() },
            shaderEnabled = optionValue(version, SHADER_OPTION)?.isNotEmpty() == true,
            accountId = AccountsManager.currentAccountFlow.value?.uniqueUUID
        )
    }

    private fun VersionProfile.copyStateFrom(state: VersionProfile): VersionProfile =
        copy(
            modStates = state.modStates,
            resourcePackStates = state.resourcePackStates,
            resourcePackOrder = state.resourcePackOrder,
            shaderStates = state.shaderStates,
            selectedShader = state.selectedShader,
            shaderEnabled = state.shaderEnabled,
            accountId = state.accountId
        )

    private fun apply(profile: VersionProfile, version: Version) {
        applyStates(VersionFolders.MOD.getDir(version.getGameDir()), profile.modStates)
        applyStates(VersionFolders.RESOURCE_PACK.getDir(version.getGameDir()), profile.resourcePackStates)
        applyStates(VersionFolders.SHADERS.getDir(version.getGameDir()), profile.shaderStates)
        writeOptionList(version, RESOURCE_PACK_OPTION, profile.resourcePackOrder)
        writeOptionValue(version, SHADER_OPTION, if (profile.shaderEnabled) profile.selectedShader.orEmpty() else "")
        profile.accountId?.let { id ->
            AccountsManager.accountsFlow.value.firstOrNull { it.uniqueUUID == id }?.let {
                AccountsManager.setCurrentAccount(it)
            }
        }
    }

    private fun snapshotStates(directory: File): Map<String, Boolean> =
        directory.listFiles()?.filter { it.exists() }?.associate { file ->
            file.profileKey() to file.isEnabled()
        } ?: emptyMap()

    private fun applyStates(directory: File, states: Map<String, Boolean>) {
        directory.listFiles()?.filter { it.exists() }?.forEach { file ->
            val key = file.profileKey()
            // Files not in this profile's snapshot default to disabled (newly installed content).
            val enabled = states[key] ?: false
            if (enabled == file.isEnabled()) return@forEach
            val targetName = if (enabled) key else "$key$DISABLED_SUFFIX"
            file.renameTo(File(directory, targetName))
        }
    }

    private fun File.profileKey(): String =
        name.removeSuffix(DISABLED_SUFFIX)

    private fun optionValue(version: Version, key: String): String? =
        optionsFile(version).takeIf { it.exists() }?.readLines()
            ?.firstOrNull { it.startsWith("$key:") }
            ?.substringAfter(':')

    private fun optionList(version: Version, key: String): List<String> {
        val raw = optionValue(version, key) ?: return emptyList()
        return raw.removeSurrounding("[", "]")
            .takeIf { it.isNotBlank() }
            ?.split(',')
            ?.map { it.trim().removeSurrounding("\"") }
            ?: emptyList()
    }

    private fun writeOptionValue(version: Version, key: String, value: String) {
        updateOption(version, key, value)
    }

    private fun writeOptionList(version: Version, key: String, values: List<String>) {
        updateOption(version, key, values.joinToString(prefix = "[", postfix = "]") { "\"$it\"" })
    }

    private fun updateOption(version: Version, key: String, value: String) {
        val file = optionsFile(version)
        if (!file.exists()) return
        val lines = file.readLines().toMutableList()
        val index = lines.indexOfFirst { it.startsWith("$key:") }
        if (index >= 0) lines[index] = "$key:$value" else lines.add("$key:$value")
        file.writeText(lines.joinToString("\n"))
    }

    private fun optionsFile(version: Version): File = File(version.getGameDir(), "options.txt")

    private fun read(version: Version): VersionProfileFile {
        val key = version.getVersionPath().absolutePath
        cache[key]?.let { return it }
        val file = File(VersionsManager.getZalithVersionPath(version), PROFILE_FILE_NAME)
        val loaded = runCatching {
            if (file.exists()) GSON.fromJson<VersionProfileFile>(file.readText(), profileFileType)
            else null
        }.onFailure { Logger.warning(TAG, "Failed to load version profiles.", it) }.getOrNull()
        val result = loaded?.takeIf { it.profiles.isNotEmpty() } ?: run {
            val default = VersionProfile(
                name = DEFAULT_VERSION_PROFILE_NAME,
                modStates = snapshotStates(VersionFolders.MOD.getDir(version.getGameDir())),
                resourcePackStates = snapshotStates(VersionFolders.RESOURCE_PACK.getDir(version.getGameDir())),
                resourcePackOrder = optionList(version, RESOURCE_PACK_OPTION),
                shaderStates = snapshotStates(VersionFolders.SHADERS.getDir(version.getGameDir())),
                selectedShader = optionValue(version, SHADER_OPTION)?.takeUnless { it.isEmpty() },
                shaderEnabled = optionValue(version, SHADER_OPTION)?.isNotEmpty() == true,
                accountId = AccountsManager.currentAccountFlow.value?.uniqueUUID
            )
            VersionProfileFile(DEFAULT_VERSION_PROFILE_NAME, listOf(default))
        }
        cache[key] = result
        if (!file.exists()) write(version, result)
        return result
    }

    private fun ensure(version: Version) = read(version)

    private fun write(version: Version, value: VersionProfileFile) {
        val key = version.getVersionPath().absolutePath
        cache[key] = value
        val directory = VersionsManager.getZalithVersionPath(version)
        if (!directory.exists()) directory.mkdirs()
        runCatching {
            FileWriter(File(directory, PROFILE_FILE_NAME), false).use { it.write(GSON.toJson(value)) }
        }.onFailure { Logger.error(TAG, "Failed to save version profiles.", it) }
    }

    private fun uniqueName(existing: List<VersionProfile>, requested: String): String {
        val base = requested.trim().ifEmpty { "Profile" }
        if (existing.none { it.name.equals(base, ignoreCase = true) }) return base
        var index = 2
        while (existing.any { it.name.equals("$base $index", ignoreCase = true) }) index++
        return "$base $index"
    }

    private fun List<VersionProfile>.replaceProfile(updated: VersionProfile): List<VersionProfile> =
        map { if (it.name == updated.name) updated else it }
}