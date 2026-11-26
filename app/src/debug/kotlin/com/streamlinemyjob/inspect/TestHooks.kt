package com.streamlinemyjob.inspect

/**
 * Test hooks (designs/stage-1-foundation.md 4.16). In the `debug` source set only, so the release
 * build has none. The instrumented tests call them, never from the main thread.
 */
object TestHooks {
    private val container: AppContainer
        get() = CoordinatorApp.current.container

    /**
     * Closes the database, deletes `smj.db`, and creates it again seeded from [seedJson], with the region
     * links loaded from [linksJson] (the bundled link file when not given; 4.26).
     */
    fun reset(seedJson: String, linksJson: String? = null) {
        container.deleteDatabaseFile()
        container.open(seedJson, linksJson)
    }

    /** Closes the database and opens it again from its file. */
    fun reopen() {
        val c = container
        c.close()
        c.database()
    }
}
