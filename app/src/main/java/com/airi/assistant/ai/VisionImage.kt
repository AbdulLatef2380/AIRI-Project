package com.airi.assistant.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.InputStream

/**
 * Vision-pipeline helpers — used only by ChatViewModel.sendMessageWithImage
 * when the loaded model has been detected as vision-capable AND a projector
 * (mmproj) has been loaded. Keeps three concerns in one place so the
 * ViewModel never touches raw Bitmap APIs:
 *
 *   1. Decode an image URI WITH a memory cap (`inSampleSize` from the
 *      reported source bounds — never decodes a 12MP camera frame at full
 *      resolution into a 144MB ARGB_8888 bitmap).
 *   2. Downscale the in-memory Bitmap so the longest side is at most
 *      [MAX_LONGEST_SIDE_PX]. This bounds the per-call allocation that the
 *      native bridge has to make for the RGB byte array.
 *   3. Convert ARGB_8888 → packed RGB888 (width*height*3 bytes, row-major,
 *      top-down) — the exact layout LlamaBridge.cpp/airi_eval_image expects.
 *
 * AIRI tags emitted on every successful prepare so the prep layer can
 * be audited from logcat without any UI instrumentation:
 *
 *   IMG_PREP_DECODED   uri=… src=WxH inSampleSize=…
 *   IMG_PREP_SCALED    src=WxH dst=WxH bytes=…
 *   IMG_PREP_RGB888    bytes=… w=… h=…
 */
object VisionImage {
    private const val TAG = "AIRI_VISION"

    /**
     * Hard cap for the longest image side passed to the native bridge.
     *
     * 672 is the canonical input resolution for LLaVA-1.5 / SmolVLM /
     * Qwen2-VL’s "tiny" vision tower; running larger gives no quality
     * benefit on a 2-7B class on-device backbone and roughly TRIPLES the
     * prefill time (vision tokens scale with H*W). 672*672*3 = 1.29 MB
     * for the RGB byte array — comfortable on 2GB devices.
     */
    const val MAX_LONGEST_SIDE_PX = 672

    /**
     * Hard maximum bytes the RGB ByteArray can occupy. A defensive ceiling
     * separate from MAX_LONGEST_SIDE_PX so a caller that accidentally
     * passes an already-loaded Bitmap can't blow past 4MB even if our
     * downscale logic regresses. (672*672*3 = 1.29MB, so 4MB is ~3x.)
     */
    const val MAX_RGB_BYTES = 4 * 1024 * 1024

    /**
     * Decode an image URI into an ARGB_8888 Bitmap whose longest side is at
     * most [MAX_LONGEST_SIDE_PX]. Returns null on any failure (file gone,
     * unreadable format, OOM during decode).
     *
     * Uses the standard two-pass BitmapFactory pattern:
     *   pass 1: inJustDecodeBounds = true   → reads only header
     *   pass 2: inJustDecodeBounds = false  → decodes at the chosen sample
     *
     * This is the ONLY entry point that touches `contentResolver.openInputStream`.
     */
    fun decodeAndDownscale(ctx: Context, uri: Uri): Bitmap? {
        val resolver = ctx.contentResolver
        // ── Pass 1: bounds only ──────────────────────────────────────────
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            resolver.openInputStream(uri)?.use { input: InputStream ->
                BitmapFactory.decodeStream(input, null, bounds)
            } ?: return null.also {
                Log.w(TAG, "decodeAndDownscale: openInputStream returned null uri=$uri")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "decodeAndDownscale: SecurityException for uri=$uri: ${e.message}")
            return null
        } catch (e: Throwable) {
            Log.w(TAG, "decodeAndDownscale: bounds pass failed: ${e.message}")
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "decodeAndDownscale: degenerate bounds w=${bounds.outWidth} h=${bounds.outHeight}")
            return null
        }
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight

        // Compute the smallest power-of-two inSampleSize that brings the
        // long side ≤ MAX_LONGEST_SIDE_PX. This is what BitmapFactory wants
        // (see Android docs: inSampleSize MUST be a power of two).
        var sampleSize = 1
        while (
            (srcW / sampleSize) > MAX_LONGEST_SIDE_PX ||
            (srcH / sampleSize) > MAX_LONGEST_SIDE_PX
        ) {
            sampleSize *= 2
        }

        // ── Pass 2: actual decode at the chosen sample ───────────────────
        val opts = BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize
            // Required so JVM can recompute strides for the sampled bitmap.
            inMutable = false
        }
        val sampled: Bitmap? = try {
            resolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, opts)
            }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "decodeAndDownscale OOM at sample=$sampleSize src=${srcW}x${srcH}: ${e.message}")
            return null
        } catch (e: Throwable) {
            Log.w(TAG, "decodeAndDownscale: decode pass failed: ${e.message}")
            return null
        }
        if (sampled == null) {
            Log.w(TAG, "decodeAndDownscale: decodeStream returned null uri=$uri")
            return null
        }
        Log.i(
            "AIRI",
            "IMG_PREP_DECODED uri=$uri src=${srcW}x${srcH} inSampleSize=$sampleSize " +
                "decoded=${sampled.width}x${sampled.height}"
        )
        // The sample step rounds down; for awkward source ratios the long
        // side may still exceed our cap. Apply an exact bilinear downscale
        // as a final guarantee.
        return downscaleBitmap(sampled)
    }

    /**
     * Final exact downscale step. Returns the input untouched if it's
     * already within the cap. Recycles the input when a new bitmap is
     * created — callers MUST treat the input as invalid after this call.
     */
    fun downscaleBitmap(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val longest = maxOf(w, h)
        if (longest <= MAX_LONGEST_SIDE_PX) return src
        val scale = MAX_LONGEST_SIDE_PX.toFloat() / longest
        val dstW = (w * scale).toInt().coerceAtLeast(1)
        val dstH = (h * scale).toInt().coerceAtLeast(1)
        val scaled = try {
            Bitmap.createScaledBitmap(src, dstW, dstH, true /* bilinear */)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "downscaleBitmap OOM dst=${dstW}x${dstH}: ${e.message}")
            return src
        }
        if (scaled !== src) src.recycle()
        Log.i(
            "AIRI",
            "IMG_PREP_SCALED src=${w}x${h} dst=${dstW}x${dstH} bytes=${dstW * dstH * 3}"
        )
        return scaled
    }

    /**
     * Pack an ARGB_8888 Bitmap into a top-down, row-major RGB888 ByteArray
     * (width*height*3 bytes). Throws IllegalStateException if the result
     * would exceed [MAX_RGB_BYTES] (defensive — should be impossible after
     * decodeAndDownscale, but the bridge contract demands an upper bound).
     *
     * Returns null on any unexpected failure (caller treats null as
     * "fall back to text marker").
     */
    fun bitmapToRgb888(bmp: Bitmap): ByteArray? {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return null
        val needed = w * h * 3
        if (needed <= 0 || needed > MAX_RGB_BYTES) {
            Log.e(TAG, "bitmapToRgb888 refused: w=$w h=$h needed=$needed cap=$MAX_RGB_BYTES")
            return null
        }
        val argb = IntArray(w * h)
        try {
            bmp.getPixels(argb, 0, w, 0, 0, w, h)
        } catch (e: Throwable) {
            Log.e(TAG, "bitmapToRgb888: getPixels failed: ${e.message}")
            return null
        }
        val out = try {
            ByteArray(needed)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "bitmapToRgb888 OOM allocating $needed bytes: ${e.message}")
            return null
        }
        var di = 0
        var i = 0
        val n = argb.size
        while (i < n) {
            val px = argb[i]
            // ARGB_8888 layout is 0xAARRGGBB.
            out[di]     = ((px ushr 16) and 0xFF).toByte() // R
            out[di + 1] = ((px ushr  8) and 0xFF).toByte() // G
            out[di + 2] = ( px          and 0xFF).toByte() // B
            di += 3
            i  += 1
        }
        Log.i("AIRI", "IMG_PREP_RGB888 bytes=$needed w=$w h=$h")
        return out
    }
}
