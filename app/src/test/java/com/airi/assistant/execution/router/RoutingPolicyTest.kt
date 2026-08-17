package com.airi.assistant.execution.router

import com.airi.assistant.ai.QueryType
import com.airi.assistant.core.debug.ThermalLevel
import com.airi.assistant.execution.CapabilityProfile
import com.airi.assistant.execution.ExecOrigin
import com.airi.assistant.execution.ExecutionMode
import com.airi.assistant.execution.ExecutionRequest
import com.airi.assistant.execution.ExecutionResult
import com.airi.assistant.execution.PrivacyLevel
import com.airi.assistant.execution.backend.RuntimeBackend
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutingPolicyTest {

    @Test
    fun localOnlyAlwaysSelectsLocal() {
        val local = backend("local", ExecOrigin.LOCAL, available = true, CapabilityProfile.LOCAL_CPU)
        val cloud = backend("cloud", ExecOrigin.CLOUD, available = true, CapabilityProfile.CLOUD_STREAMING)

        val selection = RoutingPolicy.select(
            request = ExecutionRequest(prompt = "hello"),
            signals = signals(online = true),
            prefs = prefs(mode = ExecutionMode.LOCAL_ONLY),
            local = local,
            cloud = cloud
        )

        assertEquals(listOf(local), selection.backends)
    }

    @Test
    fun cloudOnlyOnlineSelectsCloudThenLocalFallback() {
        val local = backend("local", ExecOrigin.LOCAL, available = true, CapabilityProfile.LOCAL_CPU)
        val cloud = backend("cloud", ExecOrigin.CLOUD, available = true, CapabilityProfile.CLOUD_STREAMING)

        val selection = RoutingPolicy.select(
            request = ExecutionRequest(prompt = "hello"),
            signals = signals(online = true),
            prefs = prefs(mode = ExecutionMode.CLOUD_ONLY, offlineFallback = true),
            local = local,
            cloud = cloud
        )

        assertEquals(listOf(cloud, local), selection.backends)
    }

    @Test
    fun cloudOnlyOfflineWithFallbackSelectsLocalWithoutAttemptingCloud() {
        val local = backend("local", ExecOrigin.LOCAL, available = true, CapabilityProfile.LOCAL_CPU)
        val cloud = backend("cloud", ExecOrigin.CLOUD, available = true, CapabilityProfile.CLOUD_STREAMING)

        val selection = RoutingPolicy.select(
            request = ExecutionRequest(prompt = "hello"),
            signals = signals(online = false),
            prefs = prefs(mode = ExecutionMode.CLOUD_ONLY, offlineFallback = true),
            local = local,
            cloud = cloud
        )

        assertEquals(listOf(local), selection.backends)
    }

    @Test
    fun cloudOnlyOfflineWithoutFallbackKeepsCloudSelectionForExplicitErrorHandling() {
        val local = backend("local", ExecOrigin.LOCAL, available = true, CapabilityProfile.LOCAL_CPU)
        val cloud = backend("cloud", ExecOrigin.CLOUD, available = true, CapabilityProfile.CLOUD_STREAMING)

        val selection = RoutingPolicy.select(
            request = ExecutionRequest(prompt = "hello"),
            signals = signals(online = false),
            prefs = prefs(mode = ExecutionMode.CLOUD_ONLY, offlineFallback = false),
            local = local,
            cloud = cloud
        )

        assertEquals(listOf(cloud), selection.backends)
        assertEquals("CLOUD_ONLY mode — offline, no fallback", selection.rationale)
    }

    @Test
    fun hybridAnalyticalRequestPrefersCloudWhenOnline() {
        val local = backend("local", ExecOrigin.LOCAL, available = true, CapabilityProfile.LOCAL_CPU)
        val cloud = backend("cloud", ExecOrigin.CLOUD, available = true, CapabilityProfile.CLOUD_STREAMING)

        val selection = RoutingPolicy.select(
            request = ExecutionRequest(
                prompt = "Explain the trade-offs in detail",
                queryType = QueryType.ANALYTICAL,
                estimatedPromptTokens = 300
            ),
            signals = signals(online = true),
            prefs = prefs(mode = ExecutionMode.HYBRID),
            local = local,
            cloud = cloud
        )

        assertEquals(listOf(cloud, local), selection.backends)
    }

    @Test
    fun hybridOfflineSelectsOnlyLocal() {
        val local = backend("local", ExecOrigin.LOCAL, available = true, CapabilityProfile.LOCAL_CPU)
        val cloud = backend("cloud", ExecOrigin.CLOUD, available = true, CapabilityProfile.CLOUD_STREAMING)

        val selection = RoutingPolicy.select(
            request = ExecutionRequest(prompt = "hello"),
            signals = signals(online = false),
            prefs = prefs(mode = ExecutionMode.HYBRID),
            local = local,
            cloud = cloud
        )

        assertEquals(listOf(local), selection.backends)
    }

    @Test
    fun visionRequestUsesCloudWhenLocalCannotSupportVision() {
        val local = backend("local", ExecOrigin.LOCAL, available = true, CapabilityProfile.LOCAL_CPU)
        val cloud = backend("cloud", ExecOrigin.CLOUD, available = true, CapabilityProfile.CLOUD_STREAMING)

        val selection = RoutingPolicy.select(
            request = ExecutionRequest(prompt = "describe image", requiresVision = true),
            signals = signals(online = true),
            prefs = prefs(mode = ExecutionMode.HYBRID),
            local = local,
            cloud = cloud
        )

        assertEquals(listOf(cloud, local), selection.backends)
    }

    private fun prefs(
        mode: ExecutionMode,
        offlineFallback: Boolean = true
    ) = FakeRoutingPreferences(
        effectiveMode = mode,
        privacyLevel = PrivacyLevel.BALANCED,
        internetPermissionGranted = true,
        offlineFallbackEnabled = offlineFallback
    )

    private fun signals(online: Boolean) = DeviceSignals(
        thermalLevel = ThermalLevel.NONE,
        thermalRaw = 0,
        availRamMb = 2_048,
        totalRamMb = 4_096,
        isLowMemory = false,
        networkAvailable = online,
        networkType = if (online) DeviceSignals.NetworkType.WIFI else DeviceSignals.NetworkType.NONE,
        batteryLevel = 100,
        isCharging = true,
        cpuCores = 4
    )

    private fun backend(
        id: String,
        origin: ExecOrigin,
        available: Boolean,
        capabilities: CapabilityProfile
    ) = FakeBackend(id, origin, available, capabilities)

    private data class FakeRoutingPreferences(
        override val effectiveMode: ExecutionMode,
        override val privacyLevel: PrivacyLevel,
        override val internetPermissionGranted: Boolean,
        override val offlineFallbackEnabled: Boolean,
        override val isCloudBudgetExhausted: Boolean = false,
        override val maxDailyCloudTokens: Int = 50_000,
        override val cloudTokensUsedToday: Int = 0
    ) : RoutingPreferences

    private data class FakeBackend(
        override val id: String,
        override val origin: ExecOrigin,
        override val isAvailable: Boolean,
        override val capabilities: CapabilityProfile
    ) : RuntimeBackend {
        override val displayName: String = id

        override suspend fun generateStream(
            request: ExecutionRequest,
            onToken: suspend (String) -> Unit,
            onComplete: suspend (String, Long) -> Unit,
            onError: suspend (String) -> Unit
        ) = error("Not used by RoutingPolicyTest")

        override suspend fun generate(request: ExecutionRequest): ExecutionResult =
            error("Not used by RoutingPolicyTest")
    }
}
