/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.ui.screens.content.elements

import com.movtery.zalithlauncher.R

/**
 * All shortcuts that can appear in the Quick Access panel.
 * Persisted as ordered string IDs in [com.movtery.zalithlauncher.setting.AllSettings.quickAccessShortcuts].
 */
enum class QuickAccessShortcut(
    val id: String,
    val iconRes: Int,
    val labelRes: Int
) {
    FPS("fps", R.drawable.ic_video_settings, R.string.quick_access_shortcut_fps),
    FILE_MANAGER("file_manager", R.drawable.ic_folder_outlined, R.string.quick_access_shortcut_file_manager),
    VERSIONS("versions", R.drawable.ic_assignment_filled, R.string.quick_access_shortcut_versions),
    CONTROLS("controls", R.drawable.ic_videogame_asset_outlined, R.string.quick_access_shortcut_controls),
    ABOUT("about", R.drawable.ic_info_outlined, R.string.quick_access_shortcut_about),
    SETTINGS("settings", R.drawable.ic_setting_launcher, R.string.quick_access_shortcut_settings),
    JAVA("java", R.drawable.ic_java, R.string.quick_access_shortcut_java),
    ACCOUNTS("accounts", R.drawable.ic_person_outlined, R.string.quick_access_shortcut_accounts);

    companion object {
        /** IDs used when no user configuration has been saved. */
        val DEFAULT_IDS: List<String> = listOf(FPS.id, FILE_MANAGER.id, VERSIONS.id, CONTROLS.id)

        fun fromId(id: String): QuickAccessShortcut? = entries.find { it.id == id }
    }
}
