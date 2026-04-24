package com.airi.assistant.perf

import android.content.Context
import android.os.Debug
import android.util.Log
import com.airi.assistant.ai.LlamaNative
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * On-device quantization benchmarking.
 *
 * Captures one record per local-LLM generation and writes them to
 * SharedPreferences ("airi_model_benchmarks"). The "Model Performance" screen
 * reads these back and shows a per-quantization comparison so the user can
 * empirically pick the best quant for their device — never guess.
 *
 * NOTE: this module ONLY measures. It does NOT change inference logic, does
 *       not pre-warm models, and does not bundle any quantizations.
 */

// =============================================================================
// Performance classification
// =============================================================================

enum class PerfClass { FAST, BALANCED, SLOW }

object PerfClassifier {
    /**
     * Classify a single generation run from its observed throughput AND
     * first-token latency. Heuristic tuned against mid-range Snapdragon
     * (≈8 t/s on a 2B Q4 with 1536 ctx).
     */
    fun classify(tokensPerSec: Float, firstTokenMs: Long): PerfClass = when {
        tokensPerSec >= 12f && firstTokenMs <= 4_000L  -> PerfClass.FAST
        tokensPerSec >= 5f  && firstTokenMs <= 10_000L -> PerfClass.BALANCED
        else                                           -> PerfClass.SLOW
    }
}

// =============================================================================
// Record + repository
// =============================================================================

data class ModelBenchmark(
    val timestamp:    Long,
    val modelId:      String,
    val modelDesc:    String,   // raw llama_model_desc, e.g. "gemma 2B Q4_K - Medium"
    val quantLabel:   String,   // parsed, e.g. "Q4_K_M"
    val nParams:      Long,
    val modelSizeMb:  Long,     // GGUF file size on disk
    val nCtx:         Int,
    val nThreads:     Int,
    val firstTokenMs: Long,
    val totalLatencyMs: Long,
    val decodedTokens:  Int,
    val tokensPerSec:   Float,
    val processMemMb:   Long,   // RSS at time of record
    val perfClass:      PerfClass
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("ts",        timestamp)
        put("modelId",   modelId)
        put("desc",      modelDesc)
        put("quant",     quantLabel)
        put("nParams",   nParams)
        put("sizeMb",    modelSizeMb)
        put("nCtx",      nCtx)
        put("nThreads",  nThreads)
        put("ftlMs",     firstTokenMs)
        put("totMs",     totalLatencyMs)
        put("toks",      decodedTokens)
        put("tps",       tokensPerSec.toDouble())
        put("memMb",     processMemMb)
        put("class",     perfClass.name)
    }

    companion object {
        fun fromJson(o: JSONObject): ModelBenchmark = ModelBenchmark(
            timestamp     = o.optLong  ("ts"),
            modelId       = o.optString("modelId"),
            modelDesc     = o.optString("desc"),
            quantLabel    = o.optString("quant"),
            nParams       = o.optLong  ("nParams"),
            modelSizeMb   = o.optLong  ("sizeMb"),
            nCtx          = o.optInt   ("nCtx"),
            nThreads      = o.optInt   ("nThreads"),
            firstTokenMs  = o.optLong  ("ftlMs"),
            totalLatencyMs= o.optLong  ("totMs"),
            decodedTokens = o.optInt   ("toks"),
            tokensPerSec  = o.optDouble("tps", 0.0).toFloat(),
            processMemMb  = o.optLong  ("memMb"),
            perfClass     = runCatching { PerfClass.valueOf(o.optString("class")) }
                              .getOrDefault(PerfClass.BALANCED)
        )
    }
}

object ModelBenchmarkRepository {
    private const val PREFS = "airi_model_benchmarks"
    private const val KEY   = "runs"
    private const val MAX_RUNS = 100   // hard cap so SharedPrefs stays small

    /** Append a new run (oldest evicted past MAX_RUNS). Thread-safe enough for our usage. */
    @Synchronized
    fun append(ctx: Context, run: ModelBenchmark) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY, "[]") ?: "[]"
        val arr = runCatching { JSONArray(existing) }.getOrDefault(JSONArray())
        arr.put(run.toJson())
        // Evict oldest if oversized.
        val trimmed = if (arr.length() > MAX_RUNS) {
            val out = JSONArray()
            for (i in (arr.length() - MAX_RUNS) until arr.length()) out.put(arr.get(i))
            out
        } else arr
        prefs.edit().putString(KEY, trimmed.toString()).apply()
        Log.i(
            "AIRI_BENCH",
            "RUN quant=${run.quantLabel} sizeMb=${run.modelSizeMb} " +
            "tps=%.2f ftl=${run.firstTokenMs}ms class=${run.perfClass.name}".format(run.tokensPerSec)
        )
    }

    @Synchronized
    fun all(ctx: Context): List<ModelBenchmark> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val out = ArrayList<ModelBenchmark>(arr.length())
        for (i in 0 until arr.length()) {
            runCatching { ModelBenchmark.fromJson(arr.getJSONObject(i)) }
                .onSuccess { out.add(it) }
        }
        return out
    }

    /** Group by quantLabel and average the headline numbers. */
    fun summaryByQuant(ctx: Context): List<QuantSummary> {
        val runs = all(ctx)
        if (runs.isEmpty()) return emptyList()
        return runs.groupBy { it.quantLabel.ifBlank { "unknown" } }
            .map { (q, list) ->
                QuantSummary(
                    quantLabel    = q,
                    runs          = list.size,
                    avgTokensPerSec = list.map { it.tokensPerSec }.average().toFloat(),
                    avgFirstTokenMs = list.map { it.firstTokenMs.toDouble() }.average().toLong(),
                    avgTotalMs      = list.map { it.totalLatencyMs.toDouble() }.average().toLong(),
                    avgMemMb        = list.map { it.processMemMb.toDouble() }.average().toLong(),
                    representativeSizeMb = list.map { it.modelSizeMb }.maxOrNull() ?: 0L,
                    dominantClass   = list.groupingBy { it.perfClass }.eachCount()
                                          .maxByOrNull { it.value }?.key ?: PerfClass.BALANCED
                )
            }
            .sortedBy { it.quantLabel }
    }

    @Synchronized
    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }
}

data class QuantSummary(
    val quantLabel: String,
    val runs: Int,
    val avgTokensPerSec: Float,
    val avgFirstTokenMs: Long,
    val avgTotalMs: Long,
    val avgMemMb: Long,
    val representativeSizeMb: Long,
    val dominantClass: PerfClass
)

// =============================================================================
// Native metadata helpers
// =============================================================================

data class NativeModelMeta(
    val description: String,   // raw llama_model_desc
    val quantLabel:  String,   // parsed (Q4_K_M, Q5_K_M, Q6_K, F16, …)
    val nParams:     Long,
    val sizeBytes:   Long
)

object ModelMetaProbe {
    private val QUANT_REGEX = Regex(
        """\b(Q\d(?:_[A-Z0-9]+)*|F16|BF16|F32|IQ\d[A-Z0-9_]*)\b""",
        RegexOption.IGNORE_CASE
    )

    /** Read live metadata from the loaded model (or null if none). */
    fun probe(): NativeModelMeta? {
        if (!LlamaNative.isAvailable()) return null
        val raw = runCatching { LlamaNative.getModelDescription() }.getOrNull() ?: return null
        if (raw == "UNAVAILABLE" || raw.isBlank()) return null
        val parts = raw.split('|')
        val desc      = parts.getOrNull(0).orEmpty()
        val nParams   = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        val sizeBytes = parts.getOrNull(2)?.toLongOrNull() ?: 0L
        val quant = QUANT_REGEX.find(desc)?.value?.uppercase()?.replace("-", "_")
            ?: "UNKNOWN"
        return NativeModelMeta(desc, quant, nParams, sizeBytes)
    }

    /** Process RSS in MB (Debug.MemoryInfo.totalPss). 0 if unavailable. */
    fun processMemoryMb(): Long = runCatching {
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        // totalPss is in kB.
        (mi.totalPss / 1024L).coerceAtLeast(0L)
    }.getOrDefault(0L)

    /** GGUF file size on disk (MB). */
    fun fileSizeMb(path: String?): Long {
        if (path.isNullOrBlank()) return 0L
        return runCatching { File(path).length() / (1024L * 1024L) }.getOrDefault(0L)
    }
}
