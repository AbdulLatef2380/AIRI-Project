package com.airi.assistant.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.airi.assistant.workspace.ArtifactManager
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/** Local, bounded image/audio transforms. No media bytes are logged or uploaded. */
class MediaArtifactService(
    private val context: Context,
    private val artifactManager: ArtifactManager,
) {
    data class MediaArtifactLink(
        val artifactId: String,
        val fileName: String,
        val mimeType: String,
        val link: String,
        val downloadUri: String?,
    ) {
        fun markdown(): String = "[$fileName]($link)"
    }

    suspend fun processImage(
        sessionId: String,
        source: Uri,
        outputName: String = "airi-image.jpg",
        maxDimension: Int = 2048,
        quality: Int = 88,
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
                link(artifact, "image/jpeg")
            } finally {
                if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
            }
        } finally {
            if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
        }
    }

    suspend fun processAudio(
        sessionId: String,
        source: Uri,
        outputName: String = "airi-audio.m4a",
        startMs: Long = 0L,
        endMs: Long? = null,
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
            link(artifact, "audio/mp4")
        } finally {
            input.delete()
            output.delete()
        }
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
                info.flags = extractor.sampleFlags
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

    private fun link(artifact: ArtifactManager.Artifact, mime: String) = MediaArtifactLink(
        artifactId = artifact.id,
        fileName = artifact.name,
        mimeType = mime,
        link = artifactManager.internalLink(artifact.id),
        downloadUri = artifactManager.downloadUri(artifact.id)?.toString(),
    )
}
