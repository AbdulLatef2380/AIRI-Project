package com.airi.assistant.connector.local

import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceActionPolicyTest {

    @Test
    fun permitsReadOnlyAppDiscovery() {
        assertTrue(DeviceActionPolicy.evaluate("list_apps") is DeviceActionPolicy.Decision.Allowed)
        assertTrue(DeviceActionPolicy.evaluate("find_app") is DeviceActionPolicy.Decision.Allowed)
    }

    @Test
    fun requiresUserTakeoverForAppAndPublicUrlLaunch() {
        assertTrue(DeviceActionPolicy.evaluate("open_app") is DeviceActionPolicy.Decision.RequiresUserTakeover)
        assertTrue(DeviceActionPolicy.evaluate("open_settings") is DeviceActionPolicy.Decision.RequiresUserTakeover)
        assertTrue(
            DeviceActionPolicy.evaluate("open_url", "https://example.com")
                is DeviceActionPolicy.Decision.RequiresUserTakeover
        )
    }

    @Test
    fun blocksPrivateOrInvalidUrlLaunch() {
        assertTrue(
            DeviceActionPolicy.evaluate("open_url", "http://127.0.0.1/private")
                is DeviceActionPolicy.Decision.Blocked
        )
        assertTrue(DeviceActionPolicy.evaluate("open_url", "file:///tmp/data") is DeviceActionPolicy.Decision.Blocked)
    }
}
