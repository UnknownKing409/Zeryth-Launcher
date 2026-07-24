/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.ui.screens.content.elements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.profile.VersionProfile
import com.movtery.zalithlauncher.game.version.profile.VersionProfileManager
import com.movtery.zalithlauncher.ui.components.SimpleEditDialog

private sealed interface ProfileEditor {
    data object Create : ProfileEditor
    data class Rename(val profile: VersionProfile) : ProfileEditor
}

/**
 * Shared profile dropdown used by instance cards and the dashboard.
 * The manager is intentionally synchronous: profile changes rename files
 * immediately, then this composable refreshes its small menu state.
 */
@Composable
fun VersionProfileMenu(
    version: Version,
    modifier: Modifier = Modifier,
    onExpandedChanged: (Boolean) -> Unit = {}
) {
    var expanded by remember(version) { mutableStateOf(false) }
    var managementOpen by remember(version) { mutableStateOf(false) }
    var refreshKey by remember(version) { mutableIntStateOf(0) }
    var editor by remember { mutableStateOf<ProfileEditor?>(null) }
    var deleteTarget by remember { mutableStateOf<VersionProfile?>(null) }
    val profiles = remember(version, refreshKey) {
        VersionProfileManager.listProfiles(version)
    }
    val activeName = remember(version, refreshKey) {
        VersionProfileManager.activeProfileName(version)
    }

    fun refresh() {
        refreshKey++
    }

    BoxWithProfileMenu(
        version = version,
        modifier = modifier,
        expanded = expanded,
        onExpandedChanged = {
            expanded = it
            onExpandedChanged(it)
        },
        profiles = profiles,
        activeName = activeName,
        onSelect = { name ->
            VersionProfileManager.selectProfile(version, name)
            refresh()
            expanded = false
            onExpandedChanged(false)
        },
        onManage = {
            expanded = false
            onExpandedChanged(false)
            managementOpen = true
        },
        onCreate = {
            expanded = false
            managementOpen = false
            editor = ProfileEditor.Create
        },
    )

    ProfileManagementDialog(
        open = managementOpen,
        profiles = profiles,
        onCreate = {
            managementOpen = false
            editor = ProfileEditor.Create
        },
        onDuplicate = { profile ->
            VersionProfileManager.duplicateProfile(version, profile.name)
            refresh()
        },
        onRename = {
            managementOpen = false
            editor = ProfileEditor.Rename(it)
        },
        onDelete = {
            managementOpen = false
            deleteTarget = it
        },
        onDismiss = { managementOpen = false }
    )

    editor?.let { action ->
        var value by remember(action) {
            mutableStateOf(
                when (action) {
                    ProfileEditor.Create -> ""
                    is ProfileEditor.Rename -> action.profile.name
                }
            )
        }
        SimpleEditDialog(
            title = stringResource(
                if (action is ProfileEditor.Create) R.string.version_profile_create
                else R.string.version_profile_rename
            ),
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            onDismissRequest = { editor = null },
            onConfirm = {
                when (action) {
                    ProfileEditor.Create -> VersionProfileManager.createProfile(version, value)
                    is ProfileEditor.Rename -> VersionProfileManager.renameProfile(
                        version,
                        action.profile.name,
                        value
                    )
                }
                editor = null
                refresh()
            }
        )
    }

    deleteTarget?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.version_profile_delete)) },
            text = { Text(stringResource(R.string.version_profile_delete_warning, profile.name)) },
            confirmButton = {
                Button(onClick = {
                    VersionProfileManager.deleteProfile(version, profile.name)
                    deleteTarget = null
                    refresh()
                }) { Text(stringResource(R.string.generic_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.generic_cancel))
                }
            }
        )
    }
}

@Composable
private fun BoxWithProfileMenu(
    version: Version,
    modifier: Modifier,
    expanded: Boolean,
    onExpandedChanged: (Boolean) -> Unit,
    profiles: List<VersionProfile>,
    activeName: String,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
    onCreate: () -> Unit
) {
    androidx.compose.foundation.layout.Box(modifier) {
        IconButton(
            onClick = { onExpandedChanged(!expanded) },
            enabled = version.isValid()
        ) {
            Icon(
                modifier = Modifier.size(20.dp),
                painter = painterResource(R.drawable.ic_style_outlined),
                contentDescription = stringResource(R.string.version_profile)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChanged(false) },
            modifier = Modifier.width(280.dp),
            shape = MaterialTheme.shapes.large
        ) {
            profiles.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile.name, maxLines = 1) },
                    leadingIcon = {
                        RadioButton(
                            selected = profile.name == activeName,
                            onClick = { onSelect(profile.name) }
                        )
                    },
                    onClick = { onSelect(profile.name) }
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.version_profile_create)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_add_box_outlined), null) },
                onClick = onCreate
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.version_profile_manage)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_settings_filled), null) },
                onClick = onManage
            )
        }
    }

}

@Composable
private fun ProfileManagementDialog(
    open: Boolean,
    profiles: List<VersionProfile>,
    onCreate: () -> Unit,
    onDuplicate: (VersionProfile) -> Unit,
    onRename: (VersionProfile) -> Unit,
    onDelete: (VersionProfile) -> Unit,
    onDismiss: () -> Unit
) {
    if (!open) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.version_profile_manage)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                profiles.forEach { profile ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(profile.name, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onDuplicate(profile) }) { Text(stringResource(R.string.generic_copy)) }
                        TextButton(onClick = { onRename(profile) }) { Text(stringResource(R.string.generic_rename)) }
                        TextButton(onClick = { onDelete(profile) }) { Text(stringResource(R.string.generic_delete)) }
                    }
                }
                TextButton(onClick = onCreate) {
                    Text(stringResource(R.string.version_profile_create))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.generic_close))
            }
        }
    )
}

/**
 * Expanded replacement for the dashboard account block. It deliberately
 * shows the same profile list instead of the player head/name.
 */
@Composable
fun VersionProfilePanel(
    version: Version,
    modifier: Modifier = Modifier,
    onSelected: () -> Unit = {}
) {
    val profiles = VersionProfileManager.listProfiles(version)
    val active = VersionProfileManager.activeProfileName(version)
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(R.string.version_profile),
                style = MaterialTheme.typography.titleMedium
            )
            profiles.forEach { profile ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = profile.name == active,
                        onClick = {
                            VersionProfileManager.selectProfile(version, profile.name)
                            onSelected()
                        }
                    )
                    Text(profile.name, maxLines = 1)
                }
            }
        }
    }
}