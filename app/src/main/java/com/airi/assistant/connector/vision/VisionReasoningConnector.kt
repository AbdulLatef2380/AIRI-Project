package com.airi.assistant.connector.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.airi.assistant.connector.Connector
import com.airi.assistant.connector.ConnectorInput
import com.airi.assistant.connector.ConnectorMeta
import com.airi.assistant.connector.ConnectorOutput
import com.airi.assistant.connector.ConnectorState
import com.airi.assistant.connector.ConnectorType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * VisionReasoningConnector — structural image analysis and scene description.
 *
 * Produces structured visual descriptions from images: dimensions, dominant
 * colours, brightness zones, aspect ratio, region analysis, and a prose
 * description suitable for passing to an LLM as context.
 *
 * All processing is fully on-device — no network calls.
 *
 * ── ACTIONS ─────────────────────────────────────────────────────────────
 *
 * | action        | required params           | notes                          |
 * |---------------|---------------------------|--------------------------------|
 * | `describe`    | uri OR base64             | Full structural description     |
 * | `colors`      | uri OR base64             | Dominant colour palette         |
 * | `dimensions`  | uri OR base64             | Width, height, aspect ratio     |
 * | `brightness`  | uri OR base64             | Luminance map and zone report   |
 * | `regions`     | uri OR base64, grid=NxM   | Grid-based region analysis      |
 */
class VisionReasoningConnector(
    private val appContext: Context
) : Connector {

    override val id          = "vision_reasoning"
    override val name        = "Vision Reasoning"
    override val description = "Analyse images for structure, colours, brightness, and scene composition."
    override val type        = ConnectorType.LOCAL

    private val _state = MutableStateFlow(
        ConnectorState(connected = true, healthy = true, statusLine = "Vision connector ready")
    )

    override fun meta() = ConnectorMeta(
        id = id, name = name, description = description, type = type,
        tags = listOf("vision", "image", "color", "analyse", "describe", "scene", "pixel")
    )

    override fun state(): StateFlow<ConnectorState> = _state.asStateFlow()
    override suspend fun connect()    = _state.value
    override suspend fun disconnect() { /* always-on */ }

    override suspend fun execute(input: ConnectorInput): ConnectorOutput = withContext(Dispatchers.IO) {
        val bitmap = loadBitmap(input) ?: return@withContext ConnectorOutput.Failure(
            "load_error", "Cannot load image — provide 'uri' or 'base64' param"
        )

        val start = System.currentTimeMillis()
        val result = when (input.action.lowercase()) {
            "describe"   -> describe(bitmap)
            "colors"     -> colors(bitmap)
            "dimensions" -> dimensions(bitmap)
            "brightness" -> brightness(bitmap)
            "regions"    -> regions(bitmap, input.params["grid"] ?: "3x3")
            else -> {
                bitmap.recycle()
                return@withContext ConnectorOutput.Failure("unknown_action", "VisionReasoningConnector: unknown action '${input.action}'")
            }
        }

        bitmap.recycle()
        val elapsed = System.currentTimeMillis() - start
        Log.i("AIRI_PROOF", "VISION_ANALYZED action=${input.action} elapsed=${elapsed}ms")
        ConnectorOutput.Success(text = result, data = mapOf("elapsed_ms" to elapsed.toString()), durationMs = elapsed)
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private fun describe(bmp: Bitmap): String {
        val w = bmp.width; val h = bmp.height
        val aspect = if (h > 0) w.toFloat() / h else 0f
        val ratio  = when {
            aspect > 1.7 -> "widescreen (16:9 approx)"
            aspect > 1.3 -> "landscape (4:3 approx)"
            aspect < 0.6 -> "portrait (tall)"
            aspect < 0.8 -> "portrait (slight)"
            else         -> "square / near-square"
        }
        val palette   = extractPalette(bmp, 5)
        val luma      = avgLuminance(bmp)
        val lumaDesc  = when {
            luma > 200 -> "very bright"
            luma > 140 -> "bright"
            luma > 90  -> "moderate"
            luma > 40  -> "dark"
            else       -> "very dark"
        }
        val colorStr  = palette.joinToString(", ") { "#${it.toHex()}" }
        return "Image ${w}×${h}px | $ratio | $lumaDesc (avg_luma=$luma) | " +
               "dominant colours: $colorStr"
    }

    private fun colors(bmp: Bitmap): String {
        val palette = extractPalette(bmp, 8)
        return palette.mapIndexed { i, argb ->
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8)  and 0xFF
            val b = argb           and 0xFF
            "#${argb.toHex()} rgb($r,$g,$b) luma=${luminance(argb)} — ${colorName(r, g, b)}"
        }.joinToString("\n")
    }

    private fun dimensions(bmp: Bitmap): String {
        val w = bmp.width; val h = bmp.height
        val mpx = (w.toLong() * h) / 1_000_000.0
        return "Width=${w}px Height=${h}px Megapixels=${"%.2f".format(mpx)} AspectRatio=${w}/${h}"
    }

    private fun brightness(bmp: Bitmap): String {
        val zones = mapOf(
            "top-left"     to sampleRegion(bmp, 0f, 0f, 0.5f, 0.5f),
            "top-right"    to sampleRegion(bmp, 0.5f, 0f, 1f, 0.5f),
            "centre"       to sampleRegion(bmp, 0.25f, 0.25f, 0.75f, 0.75f),
            "bottom-left"  to sampleRegion(bmp, 0f, 0.5f, 0.5f, 1f),
            "bottom-right" to sampleRegion(bmp, 0.5f, 0.5f, 1f, 1f),
        )
        val lines = zones.map { (zone, luma) ->
            val desc = when {
                luma > 200 -> "very bright"
                luma > 140 -> "bright"
                luma > 90  -> "moderate"
                luma > 40  -> "dark"
                else       -> "very dark"
            }
            "$zone: luma=$luma ($desc)"
        }
        return lines.joinToString("\n")
    }

    private fun regions(bmp: Bitmap, grid: String): String {
        val parts  = grid.split("x", "X").mapNotNull { it.trim().toIntOrNull() }
        val cols   = (parts.getOrNull(0) ?: 3).coerceIn(1, 8)
        val rows   = (parts.getOrNull(1) ?: 3).coerceIn(1, 8)
        val lines  = mutableListOf<String>()
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val x0 = col.toFloat()  / cols
                val y0 = row.toFloat()  / rows
                val x1 = (col + 1f)     / cols
                val y1 = (row + 1f)     / rows
                val luma = sampleRegion(bmp, x0, y0, x1, y1)
                val palette = extractRegionPalette(bmp, x0, y0, x1, y1, n = 3)
                val colors  = palette.joinToString("/") { "#${it.toHex()}" }
                lines += "[$row,$col] luma=$luma colors=$colors"
            }
        }
        return lines.joinToString("\n")
    }

    // ── Pixel Analysis ────────────────────────────────────────────────────────

    private fun avgLuminance(bmp: Bitmap): Int {
        val sample = 20
        val xStep = (bmp.width  / (sample + 1)).coerceAtLeast(1)
        val yStep = (bmp.height / (sample + 1)).coerceAtLeast(1)
        var sum = 0; var count = 0
        for (i in 1..sample) for (j in 1..sample) {
            sum += luminance(bmp.getPixel(
                (i * xStep).coerceAtMost(bmp.width - 1),
                (j * yStep).coerceAtMost(bmp.height - 1)
            ))
            count++
        }
        return if (count == 0) 0 else sum / count
    }

    private fun sampleRegion(bmp: Bitmap, x0: Float, y0: Float, x1: Float, y1: Float): Int {
        val px0 = (x0 * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val py0 = (y0 * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        val px1 = (x1 * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val py1 = (y1 * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        if (px0 >= px1 || py0 >= py1) return 0
        val steps = 8
        var sum = 0; var count = 0
        val xStep = ((px1 - px0) / steps).coerceAtLeast(1)
        val yStep = ((py1 - py0) / steps).coerceAtLeast(1)
        var x = px0
        while (x < px1) {
            var y = py0
            while (y < py1) {
                sum += luminance(bmp.getPixel(x.coerceAtMost(bmp.width - 1), y.coerceAtMost(bmp.height - 1)))
                count++
                y += yStep
            }
            x += xStep
        }
        return if (count == 0) 0 else sum / count
    }

    private fun extractPalette(bmp: Bitmap, n: Int): List<Int> =
        extractRegionPalette(bmp, 0f, 0f, 1f, 1f, n)

    private fun extractRegionPalette(bmp: Bitmap, x0: Float, y0: Float, x1: Float, y1: Float, n: Int): List<Int> {
        val px0 = (x0 * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val py0 = (y0 * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        val px1 = (x1 * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val py1 = (y1 * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        if (px0 >= px1 || py0 >= py1) return emptyList()

        val samples = mutableListOf<Int>()
        val stepX   = ((px1 - px0) / (n * 3 + 1)).coerceAtLeast(1)
        val stepY   = ((py1 - py0) / (n * 3 + 1)).coerceAtLeast(1)
        var x = px0
        while (x < px1 && samples.size < n * 20) {
            var y = py0
            while (y < py1 && samples.size < n * 20) {
                samples += bmp.getPixel(x.coerceAtMost(bmp.width - 1), y.coerceAtMost(bmp.height - 1)) and 0xFFFFFF
                y += stepY
            }
            x += stepX
        }
        return samples.groupBy { quantize(it) }.entries
            .sortedByDescending { it.value.size }
            .take(n)
            .map { it.key }
    }

    private fun quantize(argb: Int): Int {
        val r = ((argb shr 16) and 0xFF) and 0xE0
        val g = ((argb shr 8)  and 0xFF) and 0xE0
        val b = (argb           and 0xFF) and 0xE0
        return (r shl 16) or (g shl 8) or b
    }

    private fun luminance(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8)  and 0xFF
        val b = argb           and 0xFF
        return (0.2126 * r + 0.7152 * g + 0.0722 * b).toInt()
    }

    private fun Int.toHex(): String = "%06X".format(this and 0xFFFFFF)

    private fun colorName(r: Int, g: Int, b: Int): String = when {
        r > 200 && g < 80  && b < 80  -> "red"
        r < 80  && g > 180 && b < 80  -> "green"
        r < 80  && g < 80  && b > 200 -> "blue"
        r > 200 && g > 200 && b < 80  -> "yellow"
        r > 200 && g < 100 && b > 200 -> "magenta"
        r < 80  && g > 180 && b > 200 -> "cyan"
        r > 200 && g > 150 && b < 100 -> "orange"
        r > 180 && g > 180 && b > 180 -> "white"
        r < 80  && g < 80  && b < 80  -> "black"
        r > 100 && g > 100 && b > 100 -> "grey"
        else                           -> "mixed"
    }

    // ── Bitmap loading ────────────────────────────────────────────────────────

    private fun loadBitmap(input: ConnectorInput): Bitmap? {
        val uri    = input.params["uri"]
        val base64 = input.params["base64"]
        return when {
            !uri.isNullOrBlank() -> runCatching {
                val stream = appContext.contentResolver.openInputStream(Uri.parse(uri))
                BitmapFactory.decodeStream(stream).also { stream?.close() }
            }.getOrNull()
            !base64.isNullOrBlank() -> runCatching {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
            else -> null
        }
    }
}
