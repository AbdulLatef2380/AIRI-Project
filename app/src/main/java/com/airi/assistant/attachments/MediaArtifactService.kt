package com.airi.assistant.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.airi.assistant.workspace.ArtifactManager
import com.airi.assistant.workspace.ArtifactProvenance
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Local, bounded image/audio transforms. No media bytes are logged or uploaded. */
class MediaArtifactService(
    private val context: Context,
    private val artifactManager: ArtifactManager,
) {
    private val imageClient by lazy {
        OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }
    data class MediaArtifactLink(
        val artifactId: String,
        val fileName: String,
        val mimeType: String,
        val link: String,
        val downloadUri: String?,
        val size: Long = 0L,
        val sha256: String = "",
        val runId: String? = null,
        val traceId: String? = null,
    ) {
        fun markdown(): String = "[$fileName]($link)"
        fun structured(): String = JSONObject().apply {
            put("artifactId", artifactId); put("fileName", fileName); put("mimeType", mimeType)
            put("size", size); put("sha256", sha256); put("preview", link)
            put("download", downloadUri ?: JSONObject.NULL); put("runId", runId ?: JSONObject.NULL)
            put("traceId", traceId ?: JSONObject.NULL)
            put("shareState", if (downloadUri == null) "unavailable" else "available"); put("verified", true)
        }.toString()
    }

    suspend fun processImage(
        sessionId: String,
        source: Uri,
        outputName: String = "airi-image.jpg",
        maxDimension: Int = 2048,
        quality: Int = 88,
        runId: String? = null,
        traceId: String? = null,
    ): MediaArtifactLink {
        require(maxDimension in 64..4096)
        require(quality in 1..100)
        val bitmap = context.contentResolver.openInputStream(source).use { input ->
            BitmapFactory.decodeStream(input) ?: throw IllegalArgumentException("Image could not be decoded")
        }
        return try {
            val scaled = scale(bitmap, maxDimension)
            try {
                val bytes = java.io.ByteArrayOutputStream().use { output ->
                    require(scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)) { "Image encoding failed" }
                    output.toByteArray()
                }
                val artifact = artifactManager.createBinaryArtifact(
                    sessionId = sessionId,
                    name = outputName.substringAfterLast('/').ifBlank { "airi-image.jpg" },
                    type = ArtifactManager.ArtifactType.IMAGE,
                    bytes = bytes,
                    mimeType = "image/jpeg",
                    description = "Processed image (${scaled.width}×${scaled.height})",
                    agentId = "media_processor",
                )
                link(artifact, "image/jpeg", runId, traceId)
            } finally {
                if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
            }
        } finally {
            if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
        }
    }

    /** Downloads a generated image, validates its bytes/type, then persists it locally. */
    suspend fun generateImageFromPrompt(
        sessionId: String,
        prompt: String,
        width: Int = 1024,
        height: Int = 1024,
        outputName: String = "airi-generated-image.jpg",
        runId: String? = null,
        traceId: String? = null,
    ): MediaArtifactLink {
        require(prompt.isNotBlank()) { "Image prompt is required" }
        require(width in 256..2048 && height in 256..2048) { "Image dimensions are outside the safe range" }
        val encoded = java.net.URLEncoder.encode(prompt, "UTF-8")
        val url = "https://image.pollinations.ai/prompt/$encoded?width=$width&height=$height&nologo=true"
        val request = Request.Builder().url(url).get().build()
        val (bytes, mime) = imageClient.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Image provider returned HTTP ${response.code}" }
            val body = response.body ?: throw IllegalStateException("Image provider returned no body")
            val declaredMime = body.contentType()?.toString()?.substringBefore(';').orEmpty().lowercase()
            require(declaredMime.startsWith("image/")) { "Image provider returned unsupported content type" }
            val data = body.bytes()
            require(data.isNotEmpty()) { "Image provider returned an empty body" }
            require(data.size <= ArtifactManager.MAX_BINARY_ARTIFACT_BYTES) { "Generated image exceeds the 25MB safety limit" }
            require(BitmapFactory.decodeByteArray(data, 0, data.size) != null) { "Downloaded bytes are not a decodable image" }
            data to declaredMime
        }
        val artifact = artifactManager.createBinaryArtifact(
            sessionId = sessionId,
            name = outputName.substringAfterLast('/').ifBlank { "airi-generated-image.jpg" },
            type = ArtifactManager.ArtifactType.IMAGE,
            bytes = bytes,
            mimeType = mime,
            description = "AI-generated image downloaded from provider",
            agentId = "image_generator",
            provenance = ArtifactProvenance(
                projectId = sessionId,
                toolId = "image_generator",
                summary = "provider=pollinations; run=${runId?.take(40) ?: "none"}; trace=${traceId?.take(40) ?: "none"}",
            ),
        )
        check(artifactManager.verifyArtifact(artifact.id)) { "Generated image artifact was not verified on disk" }
        return link(artifact, mime, runId, traceId)
    }

    suspend fun processAudio(
        sessionId: String,
        source: Uri,
        outputName: String = "airi-audio.m4a",
        startMs: Long = 0L,
        endMs: Long? = null,
        runId: String? = null,
        traceId: String? = null,
    ): MediaArtifactLink {
        require(startMs >= 0L)
        val input = copyToCache(source, "airi_audio_in_")
        val output = File.createTempFile("airi_audio_out_", ".m4a", context.cacheDir)
        return try {
            val durationMs = MediaMetadataRetriever().run {
                setDataSource(input.absolutePath)
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            }
            val end = (endMs ?: durationMs).coerceAtMost(durationMs)
            require(end > startMs) { "Audio range is empty" }
            remuxAudio(input, output, startMs * 1000L, end * 1000L)
            require(output.length() <= ArtifactManager.MAX_BINARY_ARTIFACT_BYTES) { "Audio exceeds the 25MB safety limit" }
            val artifact = artifactManager.createBinaryArtifact(
                sessionId = sessionId,
                name = outputName.substringAfterLast('/').ifBlank { "airi-audio.m4a" },
                type = ArtifactManager.ArtifactType.AUDIO,
                bytes = output.readBytes(),
                mimeType = "audio/mp4",
                description = "Processed audio (${startMs}–${end} ms)",
                agentId = "media_processor",
            )
            link(artifact, "audio/mp4", runId, traceId)
        } finally {
            input.delete()
            output.delete()
        }
    }

    suspend fun processVideo(sessionId: String, source: Uri, outputName: String = "airi-video.mp4", runId: String? = null, traceId: String? = null): MediaArtifactLink {
        val input = copyToCache(source, "airi_video_in_")
        val output = File.createTempFile("airi_video_out_", ".mp4", context.cacheDir)
        return try {
            remuxTracks(input, output)
            require(output.isFile && output.length() > 0L) { "Video output was not produced" }
            require(output.length() <= ArtifactManager.MAX_BINARY_ARTIFACT_BYTES) { "Video exceeds the 25MB safety limit" }
            val artifact = artifactManager.createBinaryArtifact(sessionId, outputName.substringAfterLast('/').ifBlank { "airi-video.mp4" }, ArtifactManager.ArtifactType.VIDEO, output.readBytes(), "video/mp4", "Processed video", "media_processor")
            check(artifactManager.verifyArtifact(artifact.id)) { "Video artifact was not verified on disk" }
            link(artifact, "video/mp4", runId, traceId)
        } finally { input.delete(); output.delete() }
    }

    private fun remuxTracks(input: File, output: File) {
        val extractor = MediaExtractor(); var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(input.absolutePath)
            require(extractor.trackCount > 0) { "No media tracks found" }
            val createdMuxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = createdMuxer
            val tracks = (0 until extractor.trackCount).associateWith { createdMuxer.addTrack(extractor.getTrackFormat(it)) }
            createdMuxer.start(); (0 until extractor.trackCount).forEach(extractor::selectTrack)
            val buffer = ByteBuffer.allocate(512 * 1024); val info = MediaCodec.BufferInfo()
            while (true) {
                val track = extractor.sampleTrackIndex; if (track < 0) break
                buffer.clear(); val size = extractor.readSampleData(buffer, 0); if (size <= 0) break
                info.offset = 0; info.size = size; info.presentationTimeUs = extractor.sampleTime
                info.flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                createdMuxer.writeSampleData(tracks.getValue(track), buffer, info); extractor.advance()
            }
        } finally { runCatching { muxer?.stop() }; runCatching { muxer?.release() }; extractor.release() }
    }

    private fun remuxAudio(input: File, output: File, startUs: Long, endUs: Long) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(input.absolutePath)
            val audioTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
            } ?: throw IllegalArgumentException("No audio track found")
            extractor.selectTrack(audioTrack)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrack = muxer.addTrack(extractor.getTrackFormat(audioTrack))
            muxer.start()
            val buffer = ByteBuffer.allocate(256 * 1024)
            val info = android.media.MediaCodec.BufferInfo()
            while (true) {
                val timestamp = extractor.sampleTime
                if (timestamp < 0L || timestamp > endUs) break
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size <= 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = timestamp - startUs
                // MediaExtractor and MediaCodec expose different flag domains.
                // Only the sync/key-frame semantic is valid for MediaMuxer.
                info.flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                muxer.writeSampleData(muxerTrack, buffer, info)
                extractor.advance()
            }
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            extractor.release()
        }
    }

    private fun copyToCache(uri: Uri, prefix: String): File {
        val output = File.createTempFile(prefix, ".bin", context.cacheDir)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open media URI" }
            FileOutputStream(output).use { destination ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    require(total <= ArtifactManager.MAX_BINARY_ARTIFACT_BYTES) { "Media exceeds the 25MB safety limit" }
                    destination.write(buffer, 0, read)
                }
            }
        }
        return output
    }

    private fun scale(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / largest.toFloat()
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
    }

    private fun link(artifact: ArtifactManager.Artifact, mime: String, runId: String? = null, traceId: String? = null) = MediaArtifactLink(
        artifactId = artifact.id,
        fileName = artifact.name,
        mimeType = mime,
        link = artifactManager.internalLink(artifact.id),
        downloadUri = artifactManager.downloadUri(artifact.id)?.toString(),
        size = artifact.sizeBytes,
        sha256 = artifact.contentHash,
        runId = runId,
        traceId = traceId,
    )
}
