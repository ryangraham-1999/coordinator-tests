package com.streamlinemyjob.inspect.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TrivialTest {
    @Test
    fun moduleIsWired() {
        assertEquals("core", Module.NAME)
    }
}
