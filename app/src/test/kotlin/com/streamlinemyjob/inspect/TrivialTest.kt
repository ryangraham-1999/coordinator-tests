package com.streamlinemyjob.inspect

import org.junit.Assert.assertTrue
import org.junit.Test

class TrivialTest {
    @Test
    fun appJvmTestsRun() {
        assertTrue(MainActivity::class.java.name.endsWith(".MainActivity"))
    }
}
