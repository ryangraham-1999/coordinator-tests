package com.streamlinemyjob.inspect.testdata

import org.junit.Assert.assertEquals
import org.junit.Test

class TrivialTest {
    @Test
    fun moduleIsWired() {
        assertEquals("testdata", Module.NAME)
    }
}
