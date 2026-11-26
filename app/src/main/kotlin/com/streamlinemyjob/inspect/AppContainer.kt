package com.streamlinemyjob.inspect

import android.app.Application
import android.content.Context
import com.streamlinemyjob.inspect.core.Drawing
import com.streamlinemyjob.inspect.data.AppDatabase
import com.streamlinemyjob.inspect.data.BundledDrawing
import com.streamlinemyjob.inspect.data.RegionLinkLoader
import com.streamlinemyjob.inspect.data.Repository
import com.streamlinemyjob.inspect.data.SeedLoader
import java.time.Clock

/** The app's `Application`: it creates the [AppContainer] (designs/stage-1-foundation.md 4.16). */
class CoordinatorApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        current = this
    }

    companion object {
        /** The running app, for the debug build's test hooks. */
        lateinit var current: CoordinatorApp
            private set
    }
}

/**
 * Manual dependency wiring, no Hilt (designs/stage-1-foundation.md 3, 4.16). Holds the database
 * (`smj.db`) and the [Repository] over it. The database is opened and, if it has no lists, seeded
 * from the bundled confirmed seed on first use; then [RegionLinkLoader] adds any missing region links
 * (4.23) from the bundled link file. Every call may touch the database, so the screens
 * call it off the main thread.
 */
class AppContainer(context: Context, private val clock: Clock = Clock.systemDefaultZone()) {
    private val appContext: Context = context.applicationContext
    private var db: AppDatabase? = null
    private var repo: Repository? = null

    /** The bundled drawing (4.20), read once. */
    val drawing: Drawing by lazy { BundledDrawing.parseDrawing(BundledDrawing.drawingText(appContext)) }

    /** The open database, opened and seeded from the bundled seed if it is not open. */
    @Synchronized
    fun database(): AppDatabase = db ?: open(SeedLoader.bundledSeed(appContext))

    /** The repository over [database]. */
    @Synchronized
    fun repository(): Repository {
        val current = database()
        return repo ?: Repository(current, clock, drawing).also { repo = it }
    }

    /**
     * Opens the database from its file and loads [seedText] into it if it has no lists, then runs the
     * region-link loader with [linksText] (the bundled link file when null). Used on first use and by the
     * debug build's test hooks.
     */
    @Synchronized
    fun open(seedText: String, linksText: String? = null): AppDatabase {
        close()
        val opened = AppDatabase.build(appContext)
        SeedLoader.loadIfEmpty(opened, seedText)
        // The bundled link file belongs to the bundled seed; a link file that was handed in (the debug build's test hooks,
        // 4.26) belongs to the seed that was handed in with it.
        val links = if (linksText == null) {
            BundledDrawing.parseLinks(BundledDrawing.linksText(appContext), SeedLoader.bundledSeed(appContext), drawing)
        } else {
            BundledDrawing.parseLinks(linksText, seedText, drawing)
        }
        RegionLinkLoader.run(opened, drawing, links)
        db = opened
        repo = null
        return opened
    }

    /** Closes the database if it is open; the next [database] call opens it again from its file. */
    @Synchronized
    fun close() {
        db?.close()
        db = null
        repo = null
    }

    /** Closes the database and deletes its file. */
    @Synchronized
    fun deleteDatabaseFile() {
        close()
        appContext.deleteDatabase(AppDatabase.FILE_NAME)
    }
}
