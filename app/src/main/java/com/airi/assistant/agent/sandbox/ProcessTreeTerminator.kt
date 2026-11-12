package com.airi.assistant.agent.sandbox

/**
 * Best-effort descendant cleanup for a policy-bounded child process.
 * Android releases without ProcessHandle support fall back to terminating the
 * root process; this is not an OS containment boundary or a child-reaping proof.
 */
internal object ProcessTreeTerminator {
    fun terminate(process: Process) {
        runCatching {
            val handleClass = Class.forName("java.lang.ProcessHandle")
            val rootHandle = Process::class.java.getMethod("toHandle").invoke(process)
            val stream = handleClass.getMethod("descendants").invoke(rootHandle)
                as? java.util.stream.Stream<*>
            val handles = mutableListOf<Any>()
            stream?.use { processStream ->
                processStream.forEach { handle -> if (handle != null) handles.add(handle) }
            }
            val destroy = handleClass.getMethod("destroyForcibly")
            handles.forEach { handle -> runCatching { destroy.invoke(handle) } }
        }
        runCatching { process.destroyForcibly() }
    }
}
