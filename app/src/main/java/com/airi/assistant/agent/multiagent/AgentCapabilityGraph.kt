package com.airi.assistant.agent.multiagent

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * AgentCapabilityGraph — declares what each named agent can do,
 * enabling the [AgentTaskDelegator] to route tasks semantically.
 *
 * Capabilities are registered at startup and queryable at runtime.
 * The graph also tracks capability dependencies (agent A requires B to be
 * active before it can operate).
 */
object AgentCapabilityGraph {

    private val TAG = "AgentCapabilityGraph"

    data class AgentCapability(
        val agentId:       String,
        val displayName:   String,
        val capabilities:  Set<String>,     // e.g. "code", "web", "file_write"
        val keywords:      Set<String>,     // routing keywords
        val requiresAgents:List<String> = emptyList(),  // agent dependencies
        var isActive:      Boolean = true
    )

    private val registry = ConcurrentHashMap<String, AgentCapability>()

    fun register(capability: AgentCapability) {
        registry[capability.agentId] = capability
        Log.d(TAG, "Registered agent '${capability.agentId}' with caps: ${capability.capabilities}")
    }

    fun findCapable(capability: String): List<AgentCapability> =
        registry.values.filter { it.isActive && capability in it.capabilities }

    fun findByKeyword(keyword: String): List<AgentCapability> =
        registry.values.filter { agent ->
            agent.isActive && agent.keywords.any { it.contains(keyword, ignoreCase = true) }
        }

    fun get(agentId: String): AgentCapability? = registry[agentId]

    fun setActive(agentId: String, active: Boolean) {
        registry[agentId]?.let {
            registry[agentId] = it.copy(isActive = active)
            Log.i(TAG, "Agent '$agentId' active=$active")
        }
    }

    fun allActive(): List<AgentCapability> = registry.values.filter { it.isActive }

    /** Install the built-in AIRI agent definitions. */
    fun installDefaults() {
        listOf(
            AgentCapability("planner",     "Planner",     setOf("plan","decompose","orchestrate"), setOf("plan","step","task","breakdown")),
            AgentCapability("research",    "Research",    setOf("web","search","summarize","rag"),  setOf("research","search","find","look up","web")),
            AgentCapability("code",        "Code",        setOf("code","compile","run","debug","git"),setOf("code","write","function","class","debug","script")),
            AgentCapability("ui",          "UI",          setOf("compose","ui","design","layout"),  setOf("ui","screen","design","layout","compose")),
            AgentCapability("voice",       "Voice",       setOf("stt","tts","voice","duplex"),      setOf("voice","speak","listen","audio","say")),
            AgentCapability("connector",   "Connector",   setOf("connector","api","oauth","http"),  setOf("connect","api","webhook","integrate","github","slack")),
            AgentCapability("security",    "Security",    setOf("security","permission","vault"),   setOf("secure","permission","auth","policy","risk")),
            AgentCapability("sandbox",     "Sandbox",     setOf("shell","execute","file_write","run"),setOf("run","execute","shell","command","sandbox","terminal")),
            AgentCapability("diagnostics", "Diagnostics", setOf("diagnose","monitor","health","log"),setOf("diagnose","health","monitor","debug","log","status")),
            AgentCapability("memory",      "Memory",      setOf("rag","embed","recall","store"),    setOf("remember","recall","memory","store","history","past")),
            AgentCapability("deployment",  "Deployment",  setOf("build","deploy","package","release"),setOf("deploy","build","release","publish","apk","ship"))
        ).forEach { register(it) }
    }
}
