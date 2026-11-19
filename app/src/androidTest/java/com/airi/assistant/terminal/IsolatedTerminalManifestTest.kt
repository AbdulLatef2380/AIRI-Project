package com.airi.assistant.terminal

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IsolatedTerminalManifestTest {
    @Test
    fun serviceIsNonExportedAndIsolated() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getServiceInfo(
            android.content.ComponentName(context, IsolatedTerminalService::class.java),
            0,
        )
        assertFalse(info.exported)
        val isolated = info.flags and ServiceInfo.FLAG_ISOLATED_PROCESS != 0
        assertTrue(isolated)
    }
}
