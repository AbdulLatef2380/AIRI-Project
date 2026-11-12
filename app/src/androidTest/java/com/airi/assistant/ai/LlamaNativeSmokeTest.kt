package com.airi.assistant.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-level proof for the JNI/native lifecycle.
 *
 * The test is intentionally opt-in: a real, redistributable GGUF must be pushed
 * by the caller and supplied as instrumentation arg `airi.nativeSmokeModel`.
 * This prevents CI from silently testing a fake model or committing model data.
 */
@RunWith(AndroidJUnit4::class)
class LlamaNativeSmokeTest {
    private fun modelFile(): File? = InstrumentationRegistry.getArguments()
        .getString("airi.nativeSmokeModel")
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)

    @Test
    fun nativeLibraryLoadsAndReportsDeviceAbi() {
        assertTrue("libairi_native.so failed to load: ${LlamaNative.loadFailureMessage()}", LlamaNative.isAvailable())
        assertTrue("ABI was not reported", LlamaNative.loadedAbi().isNotBlank())
        assertEquals("fresh JNI status", 0, LlamaNative.nativeGetLastStatus())
    }

    @Test
    fun realModelLoadsGeneratesStreamsCancelsAndResetsSession() {
        val model = modelFile()
        assumeTrue("Provide -e airi.nativeSmokeModel /data/local/tmp/model.gguf", model != null)
        assumeTrue("Model path does not exist on device", model!!.isFile && model.length() > 0)
        val modelPath = requireNotNull(model)
        assertTrue(LlamaNative.isAvailable())

        val loadResult = LlamaNative.loadModel(modelPath.absolutePath)
        assertEquals("loadModel failed", "LOAD_SUCCESS", loadResult)
        val firstSession = LlamaNative.nativeGetSessionId()
        assertTrue("loadModel did not create a native session", firstSession > 0)

        LlamaNative.beginSession()
        LlamaNative.appendUserTurn("Say one short word.\n")
        assertEquals("prefill status", 0, LlamaNative.nativeGetLastStatus())
        val output = StringBuilder()
        LlamaNative.generateNextTokens(8) { output.append(it) }
        assertTrue("generation produced no tokens", output.isNotEmpty())
        assertEquals("generation status", 0, LlamaNative.nativeGetLastStatus())

        LlamaNative.nativeClearCancel()
        val cancelLatch = CountDownLatch(1)
        val callbackError = AtomicReference<Throwable?>()
        Thread {
            try {
                LlamaNative.generateNextTokens(256) { cancelLatch.countDown() }
            } catch (error: Throwable) {
                callbackError.set(error)
            }
        }.start()
        assertTrue("generation did not begin before timeout", cancelLatch.await(30, TimeUnit.SECONDS))
        LlamaNative.nativeCancel()
        // The native loop checks the atomic flag between decode steps.
        for (i in 0 until 30) {
            if (LlamaNative.nativeGetLastStatus() == -2) break
            Thread.sleep(100)
        }
        assertEquals("native cancellation was not observed", -2, LlamaNative.nativeGetLastStatus())
        assertTrue("cancel callback raised", callbackError.get() == null)

        LlamaNative.nativeFullReset()
        assertTrue("full reset lost the session", LlamaNative.nativeGetSessionId() > firstSession)
        LlamaNative.resetSession()
        assertEquals("reset status", 0, LlamaNative.nativeGetLastStatus())
    }

    @Test
    fun optionalEmbeddingAndVisionPathsNeverClaimReadinessWithoutAssets() {
        assumeTrue("native library unavailable", LlamaNative.isAvailable())
        assertFalse("embedding model must not be reported ready before load", LlamaNative.getEmbeddingDim() > 0)
        assertFalse("vision projector must not be reported ready before load", LlamaNative.isMmprojLoaded())
    }
}
