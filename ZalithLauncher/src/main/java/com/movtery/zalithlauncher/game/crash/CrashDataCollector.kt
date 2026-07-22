/*
 * Zalith Launcher 2 — Zeryth Fork
 * Crash Analyzer: Data Collector — gathers all diagnostic artifacts after a crash
 */

package com.movtery.zalithlauncher.game.crash

import android.content.Context
import android.os.Build
import com.movtery.zalithlauncher.game.crash.model.CrashSession
import com.movtery.zalithlauncher.game.launch.LogName
import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.utils.logging.Logger
import java.io.File

private const val TAG = "CrashDataCollector"

/**
 * Collects all available crash artifacts immediately after Minecraft exits unexpectedly.
 *
 * The collection never fails because one artifact is missing — it records missing artifacts
 * and continues. All analysis is performed on normalized copies; original log files are
 * never modified.
 */
object CrashDataCollector {

    /**
     * Build a [CrashSession] from the raw parameters supplied when [ErrorActivity] launches.
     *
     * @param context     Android context for device queries
     * @param exitCode    JVM exit code
     * @param isSignal    Whether the exit code is a Unix signal
     * @param logPath     Path to the primary log file (latest_jvm.log or latest_game.log)
     * @param gameHome    Minecraft instance home directory
     * @param allocatedRamMb  RAM allocation in MB
     * @param renderer    Active renderer identifier string
     * @param javaVersion Java version string
     */
    fun collect(
        context: Context,
        exitCode: Int,
        isSignal: Boolean,
        logPath: String,
        gameHome: String,
        allocatedRamMb: Int,
        renderer: String,
        javaVersion: String
    ): CrashSession {
        val missing = mutableListOf<String>()

        // ── Primary log ───────────────────────────────────────────────────────
        val primaryLogFile = File(logPath)
        val jvmLog = safeReadFile(primaryLogFile, missing, "primary log ($logPath)")

        // ── Latest game log ───────────────────────────────────────────────────
        val gameLogFile = File(PathManager.DIR_LAUNCHER_LOGS, LogName.GAME.fileName)
        val gameLog = safeReadFile(gameLogFile, missing, "game log")

        // ── Crash reports directory ───────────────────────────────────────────
        val crashReportContent = readNewestCrashReport(gameHome, missing)

        // ── hs_err_pid log ────────────────────────────────────────────────────
        val hsErrLog = findAndReadHsErr(PathManager.DIR_LAUNCHER_LOGS, missing)

        // ── Minecraft version & loader from game home ─────────────────────────
        val (mcVersion, loader, loaderVersion) = parseMcVersionAndLoader(gameHome)

        // ── Mods / resource packs / shader packs ──────────────────────────────
        val modsDir = File(gameHome, "mods")
        val installedMods = listFilenames(modsDir, "jar", missing)

        val resourcePacksDir = File(gameHome, "resourcepacks")
        val installedResourcePacks = listFilenames(resourcePacksDir, null, missing)

        val shadersDir = File(gameHome, "shaderpacks")
        val installedShaderPacks = listFilenames(shadersDir, null, missing)

        // ── Device information ────────────────────────────────────────────────
        val totalRamMb = getTotalRamMb(context)

        return CrashSession(
            timestamp         = System.currentTimeMillis(),
            exitCode          = exitCode,
            isSignal          = isSignal,
            gameLog           = gameLog,
            jvmLog            = jvmLog,
            crashReportContent = crashReportContent,
            hsErrLog          = hsErrLog,
            mcVersion         = mcVersion,
            loader            = loader,
            loaderVersion     = loaderVersion,
            javaVersion       = javaVersion.ifBlank { System.getProperty("java.version") },
            jvmArgs           = "",   // populated later if available
            allocatedRamMb    = allocatedRamMb,
            renderer          = renderer.ifBlank { null },
            androidVersion    = Build.VERSION.RELEASE,
            androidApiLevel   = Build.VERSION.SDK_INT,
            deviceManufacturer = Build.MANUFACTURER,
            deviceModel       = Build.MODEL,
            cpuAbi            = Build.SUPPORTED_ABIS.firstOrNull(),
            totalRamMb        = totalRamMb,
            installedMods     = installedMods,
            installedResourcePacks = installedResourcePacks,
            installedShaderPacks   = installedShaderPacks,
            missingArtifacts  = missing,
            gameHome          = gameHome,
            primaryLogFile    = primaryLogFile.takeIf { it.exists() }
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun safeReadFile(file: File, missing: MutableList<String>, label: String): String {
        if (!file.exists() || !file.isFile) {
            missing.add(label)
            return ""
        }
        return try {
            // Limit to 1 MB to avoid OOM on very large logs
            file.readText(Charsets.UTF_8).takeLast(1_048_576)
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to read $label", e)
            missing.add("$label (read error)")
            ""
        }
    }

    private fun readNewestCrashReport(gameHome: String, missing: MutableList<String>): String {
        if (gameHome.isBlank()) return ""
        val crashDir = File(gameHome, "crash-reports")
        if (!crashDir.exists()) return ""
        val newest = crashDir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.maxByOrNull { it.lastModified() } ?: return ""
        return safeReadFile(newest, missing, "crash report (${newest.name})")
    }

    private fun findAndReadHsErr(logsDir: File, missing: MutableList<String>): String {
        val hsErr = logsDir.listFiles { f ->
            f.isFile && f.name.startsWith("hs_err_pid") && f.name.endsWith(".log")
        }?.maxByOrNull { it.lastModified() } ?: return ""
        return safeReadFile(hsErr, missing, "hs_err log (${hsErr.name})")
    }

    private fun listFilenames(dir: File, extension: String?, missing: MutableList<String>): List<String> {
        if (!dir.exists()) return emptyList()
        return try {
            dir.listFiles { f ->
                f.isFile && (extension == null || f.name.endsWith(".$extension"))
            }?.map { it.name } ?: emptyList()
        } catch (e: Exception) {
            missing.add("listing ${dir.name}")
            emptyList()
        }
    }

    /**
     * Attempt to parse the Minecraft version and loader from the version JSON inside gameHome.
     * Returns a triple: (mcVersion, loaderName, loaderVersion).
     * Falls back gracefully to nulls if parsing fails.
     */
    private fun parseMcVersionAndLoader(gameHome: String): Triple<String?, String?, String?> {
        if (gameHome.isBlank()) return Triple(null, null, null)
        return try {
            // Standard path: <gameHome>/versions/<name>/<name>.json → "id" field
            val versionsDir = File(gameHome, "versions")
            val versionDirs = versionsDir.listFiles { f -> f.isDirectory } ?: return Triple(null, null, null)
            val newest = versionDirs.maxByOrNull { it.lastModified() } ?: return Triple(null, null, null)
            val versionJson = File(newest, "${newest.name}.json")
            if (!versionJson.exists()) return Triple(null, null, null)
            val content = versionJson.readText()
            val mcVersion = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(content)?.groupValues?.get(1)
            val loader = when {
                content.contains("\"fabricLoader\"") || content.contains("fabric-loader") -> "fabric"
                content.contains("\"neoForge\"") || content.contains("neoforge") -> "neoforge"
                content.contains("\"forge\"") -> "forge"
                content.contains("\"quilt\"") -> "quilt"
                else -> "vanilla"
            }
            Triple(mcVersion, loader, null)
        } catch (e: Exception) {
            Triple(null, null, null)
        }
    }

    private fun getTotalRamMb(context: Context): Long {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE)
                    as android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            memInfo.totalMem / (1024L * 1024L)
        } catch (e: Exception) {
            0L
        }
    }
}
