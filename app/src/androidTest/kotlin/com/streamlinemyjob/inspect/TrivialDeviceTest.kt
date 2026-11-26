package com.streamlinemyjob.inspect

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.streamlinemyjob.inspect.ui.ScreenTestSupport
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.waitForTag
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrivialDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    /** S1.3's test; since S1.10 the start screen is Vehicles instead of the placeholder text. */
    @Test
    fun startScreenShowsOnTheDebugApp() {
        val packageName = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        assertTrue("tests must run against the .debug app", packageName.endsWith(".debug"))
        ScreenTestSupport.resetAndLaunch().use {
            compose.waitForTag("vehicle-search").assertIsDisplayed()
        }
    }
}
