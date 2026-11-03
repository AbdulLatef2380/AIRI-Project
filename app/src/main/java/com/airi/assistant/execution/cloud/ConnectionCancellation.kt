package com.airi.assistant.execution.cloud

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import java.net.HttpURLConnection

/**
 * Closes [conn] the moment the calling coroutine is CANCELLED.
 *
 * Why this exists (report item 10): the adapters used
 * `currentCoroutineContext()[Job]?.invokeOnCompletion { conn?.disconnect() }`. A Job that is
 * cancelled while its body is blocked in `reader.readLine()` does not complete until that body
 * returns, so the completion handler ran only AFTER the blocked read had ended on its own. A
 * timeout or user cancel therefore never interrupted the socket.
 *
 * The returned watcher is a child of the caller's job. It suspends forever and, when the parent
 * is cancelled, disconnects the socket from another thread, which makes the blocked read throw an
 * IOException immediately. The caller MUST cancel the returned job in a `finally` block,
 * otherwise the enclosing scope would wait for it.
 */
internal suspend fun watchConnectionForCancellation(conn: HttpURLConnection): Job =
    CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }
