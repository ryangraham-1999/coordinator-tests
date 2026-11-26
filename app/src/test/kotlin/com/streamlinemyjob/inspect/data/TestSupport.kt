package com.streamlinemyjob.inspect.data

import androidx.test.core.app.ApplicationProvider
import com.streamlinemyjob.inspect.core.Dates
import com.streamlinemyjob.inspect.core.Drawing
import com.streamlinemyjob.inspect.core.LinkData
import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.core.Seed
import com.streamlinemyjob.inspect.core.SeedData
import com.streamlinemyjob.inspect.testdata.RegGenerator
import com.streamlinemyjob.inspect.testdata.Snapshot
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Random
import java.util.UUID

/**
 * Random for a test (designs/stage-1-foundation.md 4.5): the run's seed from `smj.seed` (the clock
 * otherwise, printed) plus a salt from the test's name. A `java.util.Random`, as the generators take.
 */
object TestRandom {
    val runSeed: Long = System.getProperty("smj.seed")?.toLongOrNull()
        ?: System.currentTimeMillis().also { println("smj.seed not set; using $it") }

    fun forTest(fullyQualifiedName: String): Random = Random(runSeed + fullyQualifiedName.hashCode().toLong())
}

/** A clock that moves on one millisecond every time it is read, so `created_at` order is strict. */
class TickingClock(start: Instant = Instant.now(), private val zone: ZoneId = ZoneId.of("Europe/London")) : Clock() {
    private var now = start.toEpochMilli()
    override fun instant(): Instant = Instant.ofEpochMilli(now++)
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = TickingClock(Instant.ofEpochMilli(now), zone)
}

/** The confirmed seed, read from the file itself (designs/stage-1-foundation.md 9). */
object ConfirmedSeed {
    val text: String by lazy { File(System.getProperty("smj.seedFile")).readText() }
    val data: SeedData by lazy { (Seed.parse(text) as Seed.Valid).seed }
    val listNames: List<String> get() = data.lists.map { it.name }
}

/** The bundled drawing and its starting link file, read from the files themselves (designs/stage-1-foundation.md 9). */
object ConfirmedDrawing {
    val text: String by lazy { File(System.getProperty("smj.drawingFile")).readText() }
    val linksText: String by lazy { File(System.getProperty("smj.linksFile")).readText() }
    val drawing: Drawing by lazy { (Drawing.parse(text) as Drawing.Companion.Valid).drawing }
    val links: LinkData by lazy { BundledDrawing.parseLinks(linksText, ConfirmedSeed.text, drawing) }
}

/**
 * A fresh database on its own file, seeded with [seedText], then the region-link loader with [linksText]
 * (the confirmed link file when not given; with another seed its links start not linked), and a repository over it.
 */
class TestDb(seedText: String, val clock: TickingClock = TickingClock(), linksText: String? = null) : AutoCloseable {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val name = "test-${UUID.randomUUID()}.db"
    val db: AppDatabase = AppDatabase.build(context, name, allowMainThread = true)
    val drawing: Drawing = ConfirmedDrawing.drawing
    val repo = Repository(db, clock, drawing)

    init {
        SeedLoader.loadIfEmpty(db, seedText)
        val links = linksText?.let { BundledDrawing.parseLinks(it, seedText, drawing) } ?: ConfirmedDrawing.links
        RegionLinkLoader.run(db, drawing, links)
    }

    val raw get() = db.openHelper.writableDatabase

    fun exec(sql: String, vararg args: Any?) = raw.execSQL(sql, args)

    fun lists(): List<TagList> = db.tagDao().allLists()
    fun values(): List<TagValue> = db.tagDao().allValues()

    /** Values of active lists of [role], active ones only, in list order then value order. */
    fun activeValues(role: Role): List<TagValue> {
        val lists = lists().filter { it.role == role && !it.removed }.sortedBy { it.position }
        val values = values().filter { !it.removed }.groupBy { it.listId }
        return lists.flatMap { l -> values[l.id].orEmpty().sortedBy { it.position } }
    }

    fun snapshot(): Snapshot = Snapshot.read(db)

    fun check(): List<Violation> = DataCheck.run(db, drawing)

    override fun close() {
        db.close()
        context.deleteDatabase(name)
    }
}

/**
 * Fills [t] through the repository with a few vehicles from [RegGenerator], inspections and findings
 * on random offered values (some with no action, some with notes). Not S1.9's `DataGenerator`:
 * just enough rows for every integrity rule to have something to look at.
 */
fun populate(t: TestDb, random: Random, vehicles: Int = 4) {
    val regs = RegGenerator(random)
    val areas = t.activeValues(Role.AREA)
    val damages = t.activeValues(Role.DAMAGE)
    val actions = t.activeValues(Role.ACTION)
    val today = LocalDate.now(t.clock)
    repeat(vehicles) {
        val vehicleId = (t.repo.addVehicle(regs.next().typed) as Ok).value
        repeat(1 + random.nextInt(3)) { n ->
            val date = today.minusDays(random.nextInt(3000).toLong())
            val r = t.repo.addInspection(vehicleId, Dates.format(date), "${random.nextInt(200_000)}", confirmedLower = true)
            val inspectionId = (r as Ok).value
            repeat(1 + random.nextInt(4)) { k ->
                val action = if (actions.isEmpty() || random.nextInt(5) == 0) null else actions[random.nextInt(actions.size)].id
                val note = when ((n + k) % 3) { 0 -> null; 1 -> "Note ${random.nextInt(1000)}"; else -> "Line one\nLine \"two\" é" }
                val f = t.repo.addFinding(
                    inspectionId, areas[random.nextInt(areas.size)].id, damages[random.nextInt(damages.size)].id,
                    actionOf(action), note, confirmedSameArea = true,
                )
                check(f is Ok) { "populate: $f" }
            }
        }
    }
}
