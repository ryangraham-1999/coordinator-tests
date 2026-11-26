package com.streamlinemyjob.inspect.testdata

import java.util.Random

/**
 * Random for a test (designs/stage-1-foundation.md 4.5): the run's seed from system property
 * `smj.seed` (the clock otherwise, printed) plus a fixed salt from the test's fully qualified name.
 * A `java.util.Random`, as the generators take (4.15).
 */
object TestRandom {
    val runSeed: Long = System.getProperty("smj.seed")?.toLongOrNull()
        ?: System.currentTimeMillis().also { println("smj.seed not set; using $it") }

    fun forTest(fullyQualifiedName: String): Random = Random(runSeed + fullyQualifiedName.hashCode().toLong())
}
