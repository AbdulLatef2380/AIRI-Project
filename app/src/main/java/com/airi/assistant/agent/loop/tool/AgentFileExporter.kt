package com.airi.assistant.agent.loop.tool

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/**
 * Phase 1, item 7: the only code that moves a `write_file` result out of the app.
 *
 * Files stay in `filesDir/agent_files/` (the writer's folder). Every action re-resolves the
 * card's name inside that folder and rejects anything that is not a plain file directly in
 * it, so a card can never be used to reach another path. Blocking calls ([saveToDownloads])
 * must run off the main thread.
 */
object AgentFileExporter {
    enum class SaveResult { SAVED, UNAVAILABLE, FAILED }

    private fun directory(context: Context): File =
        File(context.filesDir, AgentFileWriter.DIRECTORY_NAME)

    /** The card's file, or null when it is gone or its name does not stay inside the folder. */
    fun resolve(context: Context, card: AgentFileCard): File? {
        val name = AgentFileWriter.sanitizeName(card.fileName) ?: return null
        val root = runCatching { directory(context).canonicalFile }.getOrNull() ?: return null
        val file = runCatching { File(root, name).canonicalFile }.getOrNull() ?: return null
        return file.takeIf { it.parentFile == root && it.isFile }
    }

    private fun uriFor(context: Context, file: File): Uri? = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()

    /** Chooser that sends the file to another app (a read-only grant on a content:// URI). */
    fun shareIntent(context: Context, card: AgentFileCard): Intent? {
        val file = resolve(context, card) ?: return null
        val uri = uriFor(context, file) ?: return null
        val send = Intent(Intent.ACTION_SEND).apply {
            type = card.shareMimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, card.fileName)
            clipData = ClipData.newRawUri(card.fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, card.fileName).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Chooser that opens the file in another app, for example a Python editor. */
    fun openIntent(context: Context, card: AgentFileCard): Intent? {
        val file = resolve(context, card) ?: return null
        val uri = uriFor(context, file) ?: return null
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, card.shareMimeType)
            clipData = ClipData.newRawUri(card.fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(view, card.fileName).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Copies the file into the public Downloads folder through MediaStore, which needs no
     * storage permission on Android 10 and later. Older versions would need a legacy storage
     * permission the app does not hold, so they report [SaveResult.UNAVAILABLE] and the UI
     * points the person at Share instead.
     */
    fun saveToDownloads(context: Context, card: AgentFileCard): SaveResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return SaveResult.UNAVAILABLE
        val file = resolve(context, card) ?: return SaveResult.FAILED
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, card.fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, card.downloadMimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val target = runCatching {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return SaveResult.FAILED
        return try {
            val output = resolver.openOutputStream(target) ?: throw IOException("No output stream")
            output.use { sink -> file.inputStream().use { source -> source.copyTo(sink) } }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(target, done, null, null)
            SaveResult.SAVED
        } catch (_: Exception) {
            runCatching { resolver.delete(target, null, null) }
            SaveResult.FAILED
        }
    }
}
