/*
 * Zalith Launcher 2 — Zeryth Fork
 * Crash Analyzer: Rule-Based Diagnostic Engine — primary source of truth
 */

package com.movtery.zalithlauncher.game.crash

import android.content.Context
import com.movtery.zalithlauncher.game.crash.analyzers.JavaAnalyzer
import com.movtery.zalithlauncher.game.crash.analyzers.ModAnalyzer
import com.movtery.zalithlauncher.game.crash.analyzers.NativeAnalyzer
import com.movtery.zalithlauncher.game.crash.analyzers.RendererAnalyzer
import com.movtery.zalithlauncher.game.crash.model.CrashCategory
import com.movtery.zalithlauncher.game.crash.model.CrashDiagnosis
import com.movtery.zalithlauncher.game.crash.model.CrashEvidenceItem
import com.movtery.zalithlauncher.game.crash.model.CrashSession
import com.movtery.zalithlauncher.game.crash.model.CrashSeverity
import com.movtery.zalithlauncher.game.crash.model.CrashSignature
import com.movtery.zalithlauncher.game.crash.model.RepairAction
import com.movtery.zalithlauncher.game.crash.model.SignaturePattern

/**
 * Rule-Based Diagnostic Engine.
 *
 * Execution order:
 *  1. Signature database matching
 *  2. Specialist analyzers (Renderer, Java, Mod, Native)
 *  3. Merge all evidence and compute final confidence
 *  4. Produce a [CrashDiagnosis]
 *
 * AI must never be invoked before this engine completes.
 */
object CrashDiagnosticEngine {

    /**
     * Run the full deterministic diagnostic pipeline on a collected [CrashSession].
     * This is the only entry point for rule-based analysis.
     *
     * @param context Android context (needed for signature database)
     * @param session The normalized crash session
     * @return A [CrashDiagnosis] with all evidence and recommended repairs
     */
    fun diagnose(context: Context, session: CrashSession): CrashDiagnosis {
        CrashSignatureDatabase.load(context)

        // 1. Signature matching
        val signatureResults = matchSignatures(session)

        // 2. Specialist analyzers
        val rendererResult = RendererAnalyzer.analyze(session)
        val javaResult = JavaAnalyzer.analyze(session)
        val modResult = ModAnalyzer.analyze(session)
        val nativeResult = NativeAnalyzer.analyze(session)

        // 3. Merge all evidence
        val allEvidence = mutableListOf<CrashEvidenceItem>()
        allEvidence.addAll(signatureResults.flatMap { it.evidence })
        allEvidence.addAll(rendererResult.evidence)
        allEvidence.addAll(javaResult.evidence)
        allEvidence.addAll(modResult.evidence)
        allEvidence.addAll(nativeResult.evidence)
        allEvidence.sortByDescending { it.weight }

        // 4. Determine category and confidence
        val (category, confidence, rootCause, rootCauseDetail, technicalDetail, matchedIds) =
            computeCategory(session, signatureResults, rendererResult, javaResult, modResult, nativeResult)

        // 5. Collect repairs (de-duped by type, safest first)
        val repairs = mergeRepairs(signatureResults.flatMap { it.repairs } +
                rendererResult.repairs + javaResult.repairs + modResult.repairs)

        // 6. Determine startup stage
        val stage = inferStartupStage(session, category)

        // 7. Determine offending component
        val offendingComponent = modResult.offendingMod
            ?: nativeResult.crashingLibrary
            ?: signatureResults.firstOrNull()?.offendingComponent

        return CrashDiagnosis(
            category = category,
            severity = resolveSeverity(category, confidence),
            confidence = confidence,
            rootCause = rootCause,
            rootCauseDetail = rootCauseDetail,
            technicalDetail = technicalDetail,
            offendingComponent = offendingComponent,
            evidence = allEvidence,
            recommendedRepairs = repairs,
            startupStage = stage,
            matchedSignatureIds = matchedIds,
            aiEnhanced = false
        )
    }

    // ── Signature Matching ────────────────────────────────────────────────────

    private data class SignatureMatch(
        val signature: CrashSignature,
        val matchCount: Int,
        val evidence: List<CrashEvidenceItem>,
        val repairs: List<RepairAction>,
        val offendingComponent: String?
    )

    private fun matchSignatures(session: CrashSession): List<SignatureMatch> {
        val results = mutableListOf<SignatureMatch>()

        for (sig in CrashSignatureDatabase.signatures) {
            var matchCount = 0
            val evidence = mutableListOf<CrashEvidenceItem>()

            for (pattern in sig.patterns) {
                if (matchesPattern(session, pattern)) {
                    matchCount++
                    // Build an evidence item from the matched pattern
                    val evidenceText = sig.evidenceTemplates.getOrElse(matchCount - 1) {
                        "Pattern '${pattern.value.take(60)}' matched in ${pattern.field}"
                    }
                    evidence.add(CrashEvidenceItem(
                        text = evidenceText,
                        weight = pattern.weight,
                        source = CrashEvidenceItem.EvidenceSource.RULE_ENGINE
                    ))
                }
            }

            if (matchCount >= sig.minMatchCount) {
                val repairs = sig.repairActionTypes.mapNotNull { typeName ->
                    runCatching {
                        val type = RepairAction.RepairType.valueOf(typeName)
                        val fixText = sig.recommendedFixes.getOrElse(0) { "Apply recommended fix" }
                        RepairAction(
                            type = type,
                            label = type.name.replace('_', ' ').lowercase()
                                .replaceFirstChar { it.uppercase() },
                            description = fixText,
                            rationale = sig.rootCauseDetail.ifBlank { sig.rootCause },
                            isReversible = true,
                            estimatedSuccessRate = sig.confidence,
                            difficulty = 2
                        )
                    }.getOrNull()
                }
                results.add(SignatureMatch(sig, matchCount, evidence, repairs, null))
            }
        }

        // Sort by confidence descending
        results.sortByDescending { it.signature.confidence }
        return results
    }

    private fun matchesPattern(session: CrashSession, pattern: SignaturePattern): Boolean {
        val fieldValue = getFieldValue(session, pattern.field) ?: return false
        return when (pattern.type) {
            "contains"            -> fieldValue.contains(pattern.value)
            "containsIgnoreCase"  -> fieldValue.contains(pattern.value, ignoreCase = true)
            "equals"              -> fieldValue == pattern.value
            "regex"               -> Regex(pattern.value).containsMatchIn(fieldValue)
            "exitCode"            -> session.exitCode.toString() == pattern.value
            "exitCodeRange"       -> {
                val parts = pattern.value.split("-")
                if (parts.size == 2) {
                    val lo = parts[0].trim().toIntOrNull() ?: return false
                    val hi = parts[1].trim().toIntOrNull() ?: return false
                    session.exitCode in lo..hi
                } else false
            }
            else -> false
        }
    }

    private fun getFieldValue(session: CrashSession, field: String): String? {
        return when (field) {
            "gameLog"             -> session.gameLog
            "jvmLog"              -> session.jvmLog
            "crashReportContent"  -> session.crashReportContent
            "hsErrLog"            -> session.hsErrLog
            "allLogs"             -> "${session.gameLog}\n${session.jvmLog}\n${session.crashReportContent}\n${session.hsErrLog}"
            "exitCode"            -> session.exitCode.toString()
            "renderer"            -> session.renderer ?: ""
            "javaVersion"         -> session.javaVersion ?: ""
            "loader"              -> session.loader ?: ""
            "mcVersion"           -> session.mcVersion ?: ""
            "jvmArgs"             -> session.jvmArgs
            "manufacturer"        -> session.deviceManufacturer ?: ""
            else                  -> null
        }
    }

    // ── Category & Confidence Resolution ─────────────────────────────────────

    private data class CategoryResolution(
        val category: CrashCategory,
        val confidence: Int,
        val rootCause: String,
        val rootCauseDetail: String,
        val technicalDetail: String,
        val matchedIds: List<String>
    )

    private fun computeCategory(
        session: CrashSession,
        sigMatches: List<SignatureMatch>,
        rendererResult: RendererAnalyzer.Result,
        javaResult: JavaAnalyzer.Result,
        modResult: ModAnalyzer.Result,
        nativeResult: NativeAnalyzer.Result
    ): CategoryResolution {

        // Signature DB is authoritative when confidence is high
        val topSig = sigMatches.firstOrNull()
        if (topSig != null && topSig.signature.confidence >= 85) {
            val cat = runCatching { CrashCategory.valueOf(topSig.signature.category) }
                .getOrDefault(CrashCategory.UNKNOWN_CRASH)
            return CategoryResolution(
                category = cat,
                confidence = topSig.signature.confidence,
                rootCause = topSig.signature.rootCause,
                rootCauseDetail = topSig.signature.rootCauseDetail,
                technicalDetail = topSig.signature.technicalDetail,
                matchedIds = sigMatches.map { it.signature.id }
            )
        }

        // Specialist analyzers determine category when DB is insufficient
        val scores = mutableMapOf<CrashCategory, Int>()

        if (rendererResult.isRendererCrash) {
            scores[CrashCategory.RENDERER_CRASH] = rendererResult.confidence
            if (nativeResult.isNativeCrash) {
                scores[CrashCategory.GPU_DRIVER_CRASH] = (rendererResult.confidence + nativeResult.confidence) / 2
            }
        }
        if (javaResult.hasJavaIssue) {
            val allLogs = "${session.gameLog}\n${session.jvmLog}"
            if (allLogs.contains("OutOfMemoryError") || session.exitCode == 137) {
                scores[CrashCategory.OUT_OF_MEMORY] = javaResult.confidence
            } else if (allLogs.contains("Wrong Java version") || allLogs.contains("Unsupported class file major version")) {
                scores[CrashCategory.WRONG_JAVA_VERSION] = javaResult.confidence
            } else {
                scores[CrashCategory.JAVA_RUNTIME_CRASH] = javaResult.confidence
            }
        }
        if (modResult.hasModIssue) {
            val allLogs = "${session.gameLog}\n${session.jvmLog}"
            val loaderLower = session.loader?.lowercase() ?: ""
            when {
                loaderLower.contains("fabric") && allLogs.contains("fabric", ignoreCase = true) ->
                    scores[CrashCategory.FABRIC_LOADER_ERROR] = modResult.confidence
                loaderLower.contains("neoforge") ->
                    scores[CrashCategory.NEOFORGE_LOADER_ERROR] = modResult.confidence
                loaderLower.contains("forge") ->
                    scores[CrashCategory.FORGE_LOADER_ERROR] = modResult.confidence
                allLogs.contains("NoClassDefFoundError") || allLogs.contains("ClassNotFoundException") ->
                    scores[CrashCategory.MISSING_DEPENDENCY] = modResult.confidence
                else ->
                    scores[CrashCategory.MOD_CONFLICT] = modResult.confidence
            }
        }
        if (nativeResult.isNativeCrash && !rendererResult.isRendererCrash) {
            scores[CrashCategory.JVM_NATIVE_CRASH] = nativeResult.confidence
        }

        val topEntry = scores.maxByOrNull { it.value }
        if (topEntry != null && topEntry.value >= 30) {
            val rootCause = buildRootCause(topEntry.key, session, nativeResult, modResult, rendererResult)
            return CategoryResolution(
                category = topEntry.key,
                confidence = topEntry.value,
                rootCause = rootCause,
                rootCauseDetail = buildPlainLanguageExplanation(topEntry.key),
                technicalDetail = buildTechnicalDetail(session, nativeResult, modResult),
                matchedIds = sigMatches.map { it.signature.id }
            )
        }

        // Unknown crash fallback
        val techDetail = buildTechnicalDetail(session, nativeResult, modResult)
        return CategoryResolution(
            category = CrashCategory.UNKNOWN_CRASH,
            confidence = 20,
            rootCause = "No known crash signature matched the available evidence.",
            rootCauseDetail = "The crash could not be categorized with confidence. The technical details below may help you or a developer diagnose the problem.",
            technicalDetail = techDetail,
            matchedIds = emptyList()
        )
    }

    private fun buildRootCause(
        category: CrashCategory,
        session: CrashSession,
        native: NativeAnalyzer.Result,
        mod: ModAnalyzer.Result,
        renderer: RendererAnalyzer.Result
    ): String {
        return when (category) {
            CrashCategory.RENDERER_CRASH ->
                "The renderer (${session.renderer ?: "unknown"}) failed to initialize or crashed during operation."
            CrashCategory.GPU_DRIVER_CRASH ->
                "The GPU driver caused a native crash (${native.crashSignal ?: "unknown signal"})."
            CrashCategory.OUT_OF_MEMORY ->
                "Minecraft ran out of memory. Allocated: ${session.allocatedRamMb} MB."
            CrashCategory.WRONG_JAVA_VERSION ->
                "The installed Java version is incompatible with Minecraft ${session.mcVersion ?: "this version"}."
            CrashCategory.JVM_NATIVE_CRASH ->
                "The JVM crashed due to a native error (${native.crashSignal ?: "unknown signal"}" +
                        "${if (native.crashingLibrary != null) " in ${native.crashingLibrary}" else ""})."
            CrashCategory.MISSING_DEPENDENCY ->
                "A required mod dependency is missing${if (mod.offendingMod != null) " (${mod.offendingMod})" else ""}."
            CrashCategory.MOD_CONFLICT ->
                "Two or more mods are conflicting${if (mod.offendingMod != null) " (${mod.offendingMod})" else ""}."
            CrashCategory.FABRIC_LOADER_ERROR ->
                "The Fabric mod loader failed to initialize${if (mod.offendingMod != null) " — possible issue with ${mod.offendingMod}" else ""}."
            CrashCategory.FORGE_LOADER_ERROR ->
                "The Forge mod loader failed to initialize${if (mod.offendingMod != null) " — possible issue with ${mod.offendingMod}" else ""}."
            CrashCategory.NEOFORGE_LOADER_ERROR ->
                "The NeoForge mod loader failed to initialize${if (mod.offendingMod != null) " — possible issue with ${mod.offendingMod}" else ""}."
            CrashCategory.INVALID_JVM_ARGUMENTS ->
                "One or more JVM arguments are invalid or unsupported."
            else -> "Crash cause could not be determined from available evidence."
        }
    }

    private fun buildPlainLanguageExplanation(category: CrashCategory): String {
        return when (category) {
            CrashCategory.RENDERER_CRASH ->
                "The part of Zeryth Launcher that draws the Minecraft screen stopped working. This usually happens because of an incompatibility between your GPU and the selected renderer. Switching to a different renderer often fixes this immediately."
            CrashCategory.GPU_DRIVER_CRASH ->
                "Your phone's graphics driver crashed. This is usually caused by an incompatible renderer or a known driver bug on your GPU model. Try switching to a different renderer."
            CrashCategory.OUT_OF_MEMORY ->
                "Minecraft ran out of RAM. You may have allocated too little memory, or Minecraft plus your mods needs more than your device has available. Try adjusting the memory allocation in the launcher settings."
            CrashCategory.WRONG_JAVA_VERSION ->
                "The Java version installed is not compatible with this version of Minecraft. Each Minecraft version requires a specific Java version — for example, Minecraft 1.17+ needs Java 17 or newer."
            CrashCategory.JVM_NATIVE_CRASH ->
                "A low-level component of the Java runtime crashed. This can happen because of a corrupted Java installation, incompatible native library, or a renderer problem. Try reinstalling the Java runtime."
            CrashCategory.MISSING_DEPENDENCY ->
                "A mod is trying to use another mod or library that isn't installed. This usually means a mod is missing one of its required dependencies. Check that all required mods are installed and have compatible versions."
            CrashCategory.MOD_CONFLICT ->
                "Two or more of your installed mods are not compatible with each other. This often happens when mods modify the same part of Minecraft in conflicting ways. Try disabling mods one by one to identify the conflict."
            CrashCategory.FABRIC_LOADER_ERROR ->
                "The Fabric mod loader ran into an error while loading your mods. This could be caused by a mod that was not built for the current Minecraft version, a missing Fabric API, or a mod conflict."
            CrashCategory.FORGE_LOADER_ERROR ->
                "The Forge mod loader ran into an error while loading your mods. A mod may be incompatible with the current Minecraft or Forge version."
            CrashCategory.NEOFORGE_LOADER_ERROR ->
                "The NeoForge mod loader encountered an error. A mod may be incompatible with the current Minecraft or NeoForge version."
            CrashCategory.INVALID_JVM_ARGUMENTS ->
                "One or more JVM startup arguments you've set are not recognized or are no longer supported. Resetting JVM arguments to safe defaults usually resolves this."
            else ->
                "The crash could not be identified automatically. The technical details may help a developer diagnose the problem."
        }
    }

    private fun buildTechnicalDetail(
        session: CrashSession,
        native: NativeAnalyzer.Result,
        mod: ModAnalyzer.Result
    ): String {
        return buildString {
            if (session.exitCode != 0) append("Exit code: ${session.exitCode}${if (session.isSignal) " (signal)" else ""}\n")
            native.crashSignal?.let { append("Signal: $it\n") }
            native.crashingLibrary?.let { append("Crashing library: $it\n") }
            mod.offendingMod?.let { append("Offending mod: $it\n") }
            if (session.renderer != null) append("Renderer: ${session.renderer}\n")
            if (session.javaVersion != null) append("Java: ${session.javaVersion}\n")
            if (session.mcVersion != null) append("MC: ${session.mcVersion} (${session.loader ?: "vanilla"})\n")
        }.trim()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun resolveSeverity(category: CrashCategory, confidence: Int): CrashSeverity {
        return when {
            category == CrashCategory.JVM_NATIVE_CRASH -> CrashSeverity.CRITICAL
            category == CrashCategory.UNKNOWN_CRASH && confidence < 30 -> CrashSeverity.UNKNOWN
            confidence >= 80 -> category.defaultSeverity
            else -> CrashSeverity.MEDIUM
        }
    }

    private fun inferStartupStage(
        session: CrashSession,
        category: CrashCategory
    ): CrashDiagnosis.StartupStage {
        val allLogs = "${session.gameLog}\n${session.jvmLog}\n${session.crashReportContent}"
        return when {
            category == CrashCategory.RENDERER_CRASH || category == CrashCategory.GPU_DRIVER_CRASH ->
                CrashDiagnosis.StartupStage.RENDERER_INIT
            allLogs.contains("Loading mods", ignoreCase = true) ||
                    allLogs.contains("Fabric Loader", ignoreCase = true) ||
                    allLogs.contains("FML", ignoreCase = true) ->
                CrashDiagnosis.StartupStage.MOD_LOADING
            allLogs.contains("resource pack", ignoreCase = true) ->
                CrashDiagnosis.StartupStage.RESOURCE_PACK_LOADING
            allLogs.contains("shader", ignoreCase = true) ->
                CrashDiagnosis.StartupStage.SHADER_LOADING
            allLogs.contains("Loading world", ignoreCase = true) ||
                    allLogs.contains("Preparing spawn area", ignoreCase = true) ->
                CrashDiagnosis.StartupStage.WORLD_LOADING
            session.exitCode != 0 && session.jvmLog.isBlank() ->
                CrashDiagnosis.StartupStage.JVM_START
            else -> CrashDiagnosis.StartupStage.UNKNOWN
        }
    }

    private fun mergeRepairs(repairs: List<RepairAction>): List<RepairAction> {
        val seen = mutableSetOf<RepairAction.RepairType>()
        return repairs
            .filter { seen.add(it.type) }
            .sortedBy { it.difficulty }
    }
}
