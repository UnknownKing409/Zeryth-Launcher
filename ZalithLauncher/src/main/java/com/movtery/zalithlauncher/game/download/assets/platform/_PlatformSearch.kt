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
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.game.download.assets.platform

import android.util.Log
import com.movtery.zalithlauncher.BuildKeys
import com.movtery.zalithlauncher.game.download.assets.mapExceptionToMessage
import com.movtery.zalithlauncher.game.download.assets.platform.curseforge.CurseForgeSearcher
import com.movtery.zalithlauncher.game.download.assets.platform.curseforge.MCIM_CURSEFORGE_API
import com.movtery.zalithlauncher.game.download.assets.platform.modrinth.MCIM_MODRINTH_API
import com.movtery.zalithlauncher.game.download.assets.platform.modrinth.ModrinthSearcher
import com.movtery.zalithlauncher.game.download.assets.utils.localizedModSearchKeywords
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.setting.enums.MirrorSourceType
import com.movtery.zalithlauncher.ui.screens.content.download.assets.elements.DownloadAssetsState
import com.movtery.zalithlauncher.ui.screens.content.download.assets.elements.SearchAssetsState
import com.movtery.zalithlauncher.utils.isChinaMainland
import com.movtery.zalithlauncher.utils.logging.Logger
import com.movtery.zalithlauncher.utils.network.isInterruptedIOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Collections

private const val TAG = "PlatformSearch"

/**
 * Per-attempt timeout used when a fallback source is still available.
 *
 * The shared HTTP client's own timeout is 30s, which is appropriate as a last-resort
 * ceiling, but is far too long to wait on a source that turns out to be unreachable or
 * heavily throttled on the user's network before falling back to the mirror — that made
 * every search/browse action feel "stuck" for up to 30 seconds. Bounding non-final
 * attempts to this much shorter window lets the fallback kick in quickly while still
 * giving a normally-responding source plenty of headroom.
 */
private const val FALLBACK_ATTEMPT_TIMEOUT_MS = 10_000L

private val modrinthSearcher = ModrinthSearcher()
private val mirrorModrinthSearcher = ModrinthSearcher(
    api = MCIM_MODRINTH_API,
    source = "MCIM Modrinth"
)

private val curseForgeSearcher = CurseForgeSearcher()
private val mirrorCurseForgeSearcher = CurseForgeSearcher(
    api = MCIM_CURSEFORGE_API,
    source = "MCIM CurseForge"
)

/**
 * Session-scoped LRU cache for project metadata (mod info page data).
 * Holds up to 50 entries; least-recently-used entries are evicted first.
 * Avoids redundant network calls when the user navigates back to a mod they already opened.
 */
private val projectCache: MutableMap<String, PlatformProject> = Collections.synchronizedMap(
    object : LinkedHashMap<String, PlatformProject>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PlatformProject>?): Boolean =
            size > 50
    }
)

private fun projectCacheKey(platform: Platform, projectID: String) = "${platform.name}:$projectID"

/**
 * 对资源平台搜索启用镜像源机制进行操作
 */
suspend fun <E: AbstractPlatformSearcher, T> mirroredPlatformSearcher(
    searchers: List<E>,
    printLog: Boolean = true,
    block: suspend (E) -> T
): T {
    require(searchers.isNotEmpty()) { "Searcher list must not be empty." }

    // The common case is exactly two sources: a primary plus one mirror fallback. Racing
    // them concurrently avoids a "wasted wait": trying sources strictly one at a time means
    // a primary source that is merely *slow* (rather than erroring outright) forces the
    // mirror to wait its full turn before it is even allowed to start, so the total delay
    // becomes the *sum* of both sources' latencies instead of whichever finishes first.
    // Measured against the real MCIM mirror, a single CurseForge text search can easily
    // take 10+ seconds on its own — sequentially stacking that behind a timed-out primary
    // attempt is exactly what reproduced the "ridiculously slow" search/browse symptom.
    if (searchers.size == 2) {
        return racedPlatformSearcher(searchers[0], searchers[1], printLog, block)
    }

    val errors = mutableListOf<Exception>()
    var lastException: Exception? = null

    for ((index, searcher) in searchers.withIndex()) {
        // Only the last searcher in the list has no fallback to fall through to — give it
        // the full client timeout. Every earlier attempt is capped much shorter, so a
        // source that is unreachable/throttled on the user's network fails over quickly
        // instead of stalling the whole search for up to 30 seconds.
        val hasFallback = index < searchers.lastIndex
        try {
            if (printLog) {
                Logger.debug(TAG, "Starting to attempt to perform the operation on source: {${searcher.source}}")
            }
            return if (hasFallback) {
                withTimeout(FALLBACK_ATTEMPT_TIMEOUT_MS) { block(searcher) }
            } else {
                block(searcher)
            }
        } catch (e: Exception) {
            Log.w("PlatformSearcher", "Failed to perform the operation on source: {${searcher.source}}", e)

            // A per-attempt timeout just means "this source is too slow, try the fallback" —
            // it is not a genuine cancellation of the caller's coroutine, so it must not be
            // rethrown like a real CancellationException would be.
            if (e is TimeoutCancellationException) {
                val timeoutError = IOException("Source {${searcher.source}} timed out after ${FALLBACK_ATTEMPT_TIMEOUT_MS}ms", e)
                lastException = timeoutError
                errors.add(timeoutError)
                continue
            }

            lastException = e

            if (e.isInterruptedIOException()) {
                throw e
            } else if (e is FileNotFoundException) {
                errors.add(e)
                break
            } else {
                errors.add(e)
            }
        }
    }

    if (printLog) {
        Logger.warning(TAG, 
            msg = "An error occurred during this search.",
            t = IOException("All sources have failed to attempt", lastException).apply {
                errors.forEachIndexed { i, e ->
                    addSuppressed(Exception("Mirror error #${i + 1}: ${e.message}"))
                }
            }
        )
    }
    throw lastException ?: IllegalStateException("Should not have executed to this stage.")
}

/**
 * Runs [block] against [primary] and [secondary] concurrently and returns whichever
 * succeeds first, falling back to whichever source is still in flight only if the first
 * one to finish failed. This mirrors the sequential try-then-fallback semantics of
 * [mirroredPlatformSearcher] (a genuine ambient cancellation still propagates immediately,
 * a "not found" result is still treated as authoritative rather than trying the other
 * source) — the only difference is that both sources start at the same time, so a slow
 * primary no longer delays the mirror from even beginning its own attempt.
 */
private suspend fun <E: AbstractPlatformSearcher, T> racedPlatformSearcher(
    primary: E,
    secondary: E,
    printLog: Boolean,
    block: suspend (E) -> T
): T = coroutineScope {
    if (printLog) {
        Logger.debug(TAG, "Starting to attempt to perform the operation on source: {${primary.source}}")
        Logger.debug(TAG, "Starting to attempt to perform the operation on source: {${secondary.source}}")
    }

    // Unconditional timing diagnostics (not gated by [printLog]): this is the only way to
    // see, on a real device/network, which of the two sources actually wins the race and
    // how long each one really takes. Curl-based testing from a dev machine cannot capture
    // a user's real mobile-network route to api.curseforge.com, so this is left in place
    // until the "still slow" reports are fully explained.
    val raceStart = System.currentTimeMillis()

    val primaryDeferred = async { runCatching { block(primary) } }
    val secondaryDeferred = async { runCatching { block(secondary) } }

    val (winner, winnerResult) = select<Pair<E, Result<T>>> {
        primaryDeferred.onAwait { primary to it }
        secondaryDeferred.onAwait { secondary to it }
    }
    val winnerElapsedMs = System.currentTimeMillis() - raceStart
    val loser = if (winner === primary) secondary else primary
    val loserDeferred = if (winner === primary) secondaryDeferred else primaryDeferred

    val winnerError = winnerResult.exceptionOrNull()
    Logger.debug(
        TAG,
        "Race: source {${winner.source}} finished first after ${winnerElapsedMs}ms " +
            "with result=${if (winnerError == null) "SUCCESS" else "FAILURE(${winnerError::class.simpleName}: ${winnerError.message})"}"
    )
    if (winnerError == null) {
        // Winner succeeded outright — no need to wait on the other source at all.
        loserDeferred.cancel()
        return@coroutineScope winnerResult.getOrThrow()
    }

    Log.w("PlatformSearcher", "Failed to perform the operation on source: {${winner.source}}", winnerError)

    // A real ambient cancellation must propagate immediately, not trigger a fallback.
    if (winnerError is CancellationException || winnerError.isInterruptedIOException()) {
        loserDeferred.cancel()
        throw winnerError
    }
    // A definitive "not found" is treated as authoritative — same as the sequential
    // implementation's `break`, it does not fall through to the other source.
    if (winnerError is FileNotFoundException) {
        loserDeferred.cancel()
        throw winnerError
    }

    // Wait for whichever source is still in flight instead of giving up immediately.
    val loserResult = loserDeferred.await()
    val loserElapsedMs = System.currentTimeMillis() - raceStart
    val loserError = loserResult.exceptionOrNull()
    Logger.debug(
        TAG,
        "Race: source {${loser.source}} finished after ${loserElapsedMs}ms " +
            "with result=${if (loserError == null) "SUCCESS" else "FAILURE(${loserError::class.simpleName}: ${loserError.message})"}"
    )
    if (loserError == null) {
        return@coroutineScope loserResult.getOrThrow()
    }

    Log.w("PlatformSearcher", "Failed to perform the operation on source: {${loser.source}}", loserError)

    if (printLog) {
        val combined = IOException("All sources have failed to attempt", loserError).apply {
            addSuppressed(Exception("Mirror error #1: ${winnerError.message}"))
            addSuppressed(Exception("Mirror error #2: ${loserError.message}"))
        }
        Logger.warning(TAG, msg = "An error occurred during this search.", t = combined)
    }
    throw loserError
}

/**
 * 镜像源始终作为回退可用，中国大陆地区（或用户设置为镜像优先）时优先使用镜像源。
 *
 * The MCIM mirror is always kept in the searcher list as a fallback — regardless of
 * region or whether [BuildKeys.CURSEFORGE_API] is configured — so that any failure of the
 * official API (missing/invalid key, 403, rate limiting, an outage, transient network
 * errors, etc.) transparently falls back to the mirror instead of surfacing a hard error
 * to the user. Only the *ordering* (which source is tried first) depends on region/settings.
 */
fun mirroredCurseForgeSource(): List<CurseForgeSearcher> {
    val source = AllSettings.assetSearchSource.getValue()
    return when (source) {
        MirrorSourceType.OFFICIAL_FIRST ->
            listOf(curseForgeSearcher, mirrorCurseForgeSearcher)
        MirrorSourceType.MIRROR_FIRST ->
            listOf(mirrorCurseForgeSearcher, curseForgeSearcher)
    }
}

/**
 * 镜像源只能在中国地区使用
 */
fun mirroredModrinthSource(
    enabledMirror: Boolean = isChinaMainland()
): List<ModrinthSearcher> {
    val source = AllSettings.assetSearchSource.getValue()
    val mirrorSource = mirrorModrinthSearcher.takeIf { enabledMirror }
    return when (source) {
        MirrorSourceType.OFFICIAL_FIRST ->
            listOfNotNull(modrinthSearcher, mirrorSource)
        MirrorSourceType.MIRROR_FIRST ->
            listOfNotNull(mirrorSource, modrinthSearcher)
    }
}

suspend fun searchAssets(
    searchPlatform: Platform,
    searchFilter: PlatformSearchFilter,
    platformClasses: PlatformClasses,
    onSuccess: suspend (PlatformSearchResult) -> Unit,
    onError: (SearchAssetsState.Error) -> Unit
) {
    runCatching {
        val (containsChinese, englishKeywords) = searchFilter.searchName.localizedModSearchKeywords(platformClasses)
        //参考源代码：[HMCL Github](https://github.com/HMCL-dev/HMCL/blob/d295e60/HMCL/src/main/java/org/jackhuang/hmcl/game/LocalizedRemoteModRepository.java#L56-L68)
        //逐个英文短语尝试搜索，取第一个有非空结果的
        val queries = if (!englishKeywords.isNullOrEmpty()) {
            englishKeywords.toList()
        } else {
            listOf(searchFilter.searchName)
        }

        var lastResult: PlatformSearchResult? = null
        for (query in queries) {
            try {
                val r = when (searchPlatform) {
                    Platform.CURSEFORGE -> mirroredPlatformSearcher(
                        searchers = mirroredCurseForgeSource(),
                        printLog = false
                    ) { searcher ->
                        searcher.searchAssets(
                            query = query,
                            searchFilter = searchFilter,
                            platformClasses = platformClasses
                        )
                    }
                    Platform.MODRINTH -> mirroredPlatformSearcher(
                        searchers = mirroredModrinthSource(),
                        printLog = false
                    ) { searcher ->
                        searcher.searchAssets(
                            query = query,
                            searchFilter = searchFilter,
                            platformClasses = platformClasses
                        )
                    }
                }
                lastResult = r
                if (r.getAssetsPage(platformClasses).data.isNotEmpty()) break
            } catch (_: Exception) {
                //当前关键词搜索失败，继续尝试下一个
            }
        }

        val result = lastResult ?: throw IOException("Failed to search for all queries")

        onSuccess(
            if (containsChinese) result.processChineseSearchResults(searchFilter.searchName, platformClasses)
            else result
        )
    }.onFailure { e ->
        if (e !is CancellationException) {
            Logger.error(TAG, "An exception occurred while searching for assets.", e)
            val pair = mapExceptionToMessage(e)
            val state = SearchAssetsState.Error(pair.first, pair.second)
            onError(state)
        } else {
            Logger.debug(TAG, "The search task has been cancelled.")
        }
    }
}

suspend fun getVersions(
    projectID: String,
    platform: Platform,
    pageCallback: (chunk: Int, page: Int) -> Unit = { _, _ -> },
) = when (platform) {
    Platform.CURSEFORGE -> mirroredPlatformSearcher(
        searchers = mirroredCurseForgeSource()
    ) { searcher ->
        searcher.getVersions(
            projectID = projectID,
            pageCallback = pageCallback
        )
    }
    Platform.MODRINTH -> mirroredPlatformSearcher(
        searchers = mirroredModrinthSource()
    ) { searcher ->
        searcher.getVersions(
            projectID = projectID,
            pageCallback = pageCallback
        )
    }
}

suspend fun <E> getVersions(
    projectID: String,
    platform: Platform,
    pageCallback: (chunk: Int, page: Int) -> Unit = { _, _ -> },
    onSuccess: suspend (List<PlatformVersion>) -> Unit,
    onError: (DownloadAssetsState<List<E>>) -> Unit
) {
    runCatching {
        val result = getVersions(projectID, platform, pageCallback)
        onSuccess(result)
    }.onFailure { e ->
        if (e !is CancellationException) {
            Logger.error(TAG, "An exception occurred while retrieving the project version.", e)
            val pair = mapExceptionToMessage(e)
            val state = DownloadAssetsState.Error<List<E>>(pair.first, pair.second)
            onError(state)
        } else {
            Logger.debug(TAG, "The version retrieval task has been cancelled.")
        }
    }
}

suspend fun <E> getProject(
    projectID: String,
    platform: Platform,
    onSuccess: (PlatformProject) -> Unit,
    onError: (DownloadAssetsState<E>, Throwable) -> Unit
) {
    // Return cached result immediately — avoids a network round-trip when the user
    // navigates back to a mod page they already opened in this session.
    projectCache[projectCacheKey(platform, projectID)]?.let { cached ->
        onSuccess(cached)
        return
    }

    runCatching {
        when (platform) {
            Platform.CURSEFORGE -> mirroredPlatformSearcher(
                searchers = mirroredCurseForgeSource()
            ) { searcher ->
                searcher.getProject(projectID)
            }
            Platform.MODRINTH -> mirroredPlatformSearcher(
                searchers = mirroredModrinthSource()
            ) { searcher ->
                searcher.getProject(projectID)
            }
        }
    }.fold(
        onSuccess = { result ->
            projectCache[projectCacheKey(platform, projectID)] = result
            onSuccess(result)
        },
        onFailure = { e ->
            if (e !is CancellationException) {
                Logger.error(TAG, "An exception occurred while retrieving project information.", e)
                val pair = mapExceptionToMessage(e)
                val state = DownloadAssetsState.Error<E>(pair.first, pair.second)
                onError(state, e)
            } else {
                Logger.debug(TAG, "The project retrieval task has been cancelled.")
            }
        }
    )
}

suspend fun getProjectByVersion(
    projectId: String,
    platform: Platform,
    printLog: Boolean = true
): PlatformProject = withContext(Dispatchers.IO) {
    projectCache[projectCacheKey(platform, projectId)]?.let { return@withContext it }

    val result = when (platform) {
        Platform.MODRINTH -> mirroredPlatformSearcher(
            searchers = mirroredModrinthSource(),
            printLog = printLog
        ) { searcher ->
            searcher.getProject(projectId)
        }
        Platform.CURSEFORGE -> mirroredPlatformSearcher(
            searchers = mirroredCurseForgeSource(),
            printLog = printLog
        ) { searcher ->
            searcher.getProject(projectId)
        }
    }
    projectCache[projectCacheKey(platform, projectId)] = result
    result
}

suspend fun getVersionByLocalFile(file: File, sha1: String): PlatformVersion? = coroutineScope {
    val modrinthDeferred = async(Dispatchers.IO) {
        runCatching {
            mirroredPlatformSearcher(
                searchers = mirroredModrinthSource(),
                printLog = false
            ) { searcher ->
                searcher.getVersionByLocalFile(file, sha1)
            }
        }.getOrNull()
    }

    val curseForgeDeferred = async(Dispatchers.IO) {
        runCatching {
            mirroredPlatformSearcher(
                searchers = mirroredCurseForgeSource(),
                printLog = false
            ) { searcher ->
                searcher.getVersionByLocalFile(file, sha1)
            }
        }.getOrNull()
    }

    val result = select {
        modrinthDeferred.onAwait { result ->
            if (result != null) {
                curseForgeDeferred.cancel()
                result
            } else {
                null
            }
        }
        curseForgeDeferred.onAwait { result ->
            if (result != null) {
                modrinthDeferred.cancel()
                result
            } else {
                null
            }
        }
    }

    result ?: run {
        if (!modrinthDeferred.isCompleted) modrinthDeferred.await()
        else if (!curseForgeDeferred.isCompleted) curseForgeDeferred.await()
        else null
    }
}