package com.streamlinemyjob.inspect.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.streamlinemyjob.inspect.AppContainer
import com.streamlinemyjob.inspect.CoordinatorApp
import com.streamlinemyjob.inspect.MainActivity
import com.streamlinemyjob.inspect.TestHooks
import com.streamlinemyjob.inspect.data.AppDatabase
import com.streamlinemyjob.inspect.data.SeedLoader
import com.streamlinemyjob.inspect.data.read
import com.streamlinemyjob.inspect.testdata.Snapshot
import java.util.Random

/**
 * Helpers for the instrumented screen tests (designs/stage-1-foundation.md 4.16). Items are found
 * by test tag only, never by their text.
 */
object ScreenTestSupport {
    const val WAIT_MS = 15_000L

    /** The run's device seed (`smjSeed`, from the commit, 4.5) mixed with the test's name. */
    fun random(test: String): Random {
        val seed = InstrumentationRegistry.getArguments().getString("smjSeed")?.toLongOrNull()
            ?: System.currentTimeMillis().also { println("smjSeed not set; using $it") }
        println("device seed $seed for $test")
        return Random(seed + test.hashCode().toLong())
    }

    val container: AppContainer get() = CoordinatorApp.current.container

    fun db(): AppDatabase = container.database()

    fun snapshot(): Snapshot = Snapshot.read(db())

    /** Resets the database to the confirmed seed (read from the app's own asset) and launches the app. */
    fun resetAndLaunch(): ActivityScenario<MainActivity> {
        reset()
        return launch()
    }

    /** Resets the database to the confirmed seed, read from the app's own asset. */
    fun reset() {
        TestHooks.reset(SeedLoader.bundledSeed(InstrumentationRegistry.getInstrumentation().targetContext))
    }

    /** Launches the app on the database as it is. */
    fun launch(): ActivityScenario<MainActivity> = ActivityScenario.launch(MainActivity::class.java)

    /**
     * `waitUntil` that says what it was waiting for and which test tags were on screen when it gave up (a bare
     * timeout says neither, and a device test cannot be re-run with a debugger).
     */
    fun ComposeTestRule.waitLabelled(label: String, condition: () -> Boolean) {
        try {
            waitUntil(WAIT_MS, condition)
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            val tags = onAllNodes(androidx.compose.ui.test.SemanticsMatcher("any") { true }, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .mapNotNull { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag) }
                .distinct().joinToString(",").take(700)
            throw AssertionError("timed out waiting for $label; tags on screen: $tags")
        }
    }

    fun ComposeTestRule.waitForTag(tag: String): SemanticsNodeInteraction {
        waitLabelled("tag $tag") { onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
        return onNodeWithTag(tag)
    }

    fun ComposeTestRule.waitForNoTag(tag: String) {
        waitLabelled("tag $tag to go") { onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isEmpty() }
    }

    fun ComposeTestRule.click(tag: String) {
        waitForTag(tag).performClick()
    }

    /**
     * Taps an item of a scrolling screen (the finding editor) by its tag. The tap is the item's own
     * click action, so it works wherever the item has been scrolled to.
     */
    fun ComposeTestRule.activate(tag: String) {
        waitForTag(tag).performSemanticsAction(SemanticsActions.OnClick)
    }

    fun ComposeTestRule.typeInto(tag: String, text: String) {
        val node = waitForTag(tag)
        node.performTextClearance()
        node.performTextInput(text)
    }

    /** Waits until [condition] holds on the database (the screens write off the main thread). */
    fun ComposeTestRule.waitForData(condition: (Snapshot) -> Boolean) {
        waitLabelled("the data to change") { condition(snapshot()) }
    }

    fun back(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
    }
}
