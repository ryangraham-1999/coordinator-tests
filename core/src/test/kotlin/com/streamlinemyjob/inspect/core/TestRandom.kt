package com.streamlinemyjob.inspect.core

import kotlin.random.Random

/**
 * Random for a test (designs/stage-1-foundation.md 4.5): the run's seed from system property
 * `smj.seed` (the check sets it; the clock otherwise, printed so a failure can be re-run with
 * `scripts/check --seed N`) plus a fixed salt from the test's fully qualified name.
 */
object TestRandom {
    val runSeed: Long = System.getProperty("smj.seed")?.toLongOrNull()
        ?: System.currentTimeMillis().also { println("smj.seed not set; using $it") }

    fun forTest(fullyQualifiedName: String): Random = Random(runSeed + fullyQualifiedName.hashCode().toLong())
}
