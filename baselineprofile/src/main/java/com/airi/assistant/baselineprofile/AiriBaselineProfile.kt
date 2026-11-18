package com.airi.assistant.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

@LargeTest
class AiriBaselineProfile {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startupAndFirstChatSurface() = rule.collect(packageName = "com.airi.assistant") {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.pkg("com.airi.assistant")), 5_000)
        // Exercise the first stable surface without depending on localized text.
        device.waitForIdle()
    }
}
