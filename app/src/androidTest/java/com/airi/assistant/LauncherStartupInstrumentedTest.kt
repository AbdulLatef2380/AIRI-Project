package com.airi.assistant

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Guards the installed app's real launcher path, not only isolated UI components. */
@RunWith(AndroidJUnit4::class)
class LauncherStartupInstrumentedTest {

    @Test
    fun launcherActivityColdStartReachesResumedState() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(false, activity.isFinishing)
                assertEquals(false, activity.isDestroyed)
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertEquals(Lifecycle.State.RESUMED, scenario.state)
                assertEquals(false, activity.isFinishing)
            }
        }
    }
}
