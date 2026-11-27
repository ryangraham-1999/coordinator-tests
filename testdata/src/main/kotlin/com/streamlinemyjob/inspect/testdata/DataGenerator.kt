package com.streamlinemyjob.inspect.testdata

import com.streamlinemyjob.inspect.core.ActionChoice
import com.streamlinemyjob.inspect.core.Dates
import com.streamlinemyjob.inspect.core.Drawing
import com.streamlinemyjob.inspect.core.Mileage
import com.streamlinemyjob.inspect.core.Photos
import com.streamlinemyjob.inspect.core.Reg
import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.core.Rules
import com.streamlinemyjob.inspect.core.Text
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Random

/** Something the generator did not expect from the port (4.15): the run stops at once. */
class GeneratorError(message: String) : RuntimeException(message)

/**
 * The data generator (designs/stage-1-foundation.md 4.15). Writes vehicles, inspections and exactly
 * N findings through a [RepositoryPort] only, so every Repository rule runs, and returns the expected
 * snapshot, built from what it wrote and what the port returned (never read back after writing).
 *
 * Write order: each vehicle followed by its inspections (oldest first), then the findings,
 * inspection by inspection in the order the inspections were added.
 */
object DataGenerator {
    /** Most findings on one inspection (4.15). */
    const val FINDINGS_PER_INSPECTION_MAX = 10

    /** Inspections per vehicle (4.15). */
    const val INSPECTIONS_MIN = 1
    const val INSPECTIONS_MAX = 5

    /** From this many findings on, 29 February, mileage 0 and mileage 999,999 are each used at least once (4.15). */
    const val EXTREMES_FROM = 50

    /**
     * Writes through [port] and returns the expected snapshot. [start] is the time of write 0; write k
     * (refused ones included) happens at `start + k` milliseconds. With a [drawing] (4.26, from S1.27), each
     * finding whose area value is linked from at least one region and is offered gets, with probability 0.5, a
     * dot at a [PointSampler] point of one of those regions; without one, no finding has a dot.
     */
    fun generate(
        random: Random, port: RepositoryPort, findings: Int, start: Instant, drawing: Drawing? = null, photos: Boolean = false,
    ): Snapshot = Run(random, port, findings, start.toEpochMilli(), drawing, photos).go()

    private class PlannedInspection(
        val vehicle: Int,
        val index: Int,
        val date: LocalDate,
        val typedDate: String,
        val mileage: Int,
        val typedMileage: String,
        val lower: Boolean,
    )

    private class PlannedFinding(
        val inspection: Int,
        var area: Snapshot.ValueRow,
        val damage: Snapshot.ValueRow,
        /** The action value, or null for [noAction] or not chosen yet. */
        val action: Snapshot.ValueRow?,
        /** True: "No action" chosen on purpose; false with no [action]: not chosen yet (4.29). */
        var noAction: Boolean,
        val rawNote: String,
    )

    private class Run(
        val random: Random, val port: RepositoryPort, val n: Int, val start: Long, val drawing: Drawing?, val withPhotos: Boolean,
    ) {
        private var writes = 0L

        private fun <T> write(call: () -> T): T {
            port.setTime(start + writes)
            writes++
            return call()
        }

        fun go(): Snapshot {
            require(n >= 0) { "findings must not be negative: $n" }
            val before = port.snapshot()
            port.setTime(start)
            val today = port.today()

            val listById = before.lists.associateBy { it.id }
            fun offered(role: Role): List<Snapshot.ValueRow> {
                val lists = before.lists.filter { it.role == role && !it.removed }
                    .sortedWith(compareBy<Snapshot.ListRow> { it.position }.thenBy { it.id })
                val byList = before.values.filter { !it.removed }.groupBy { it.listId }
                return lists.flatMap { l -> byList[l.id].orEmpty().sortedWith(compareBy<Snapshot.ValueRow> { it.position }.thenBy { it.id }) }
            }
            val areas = offered(Role.AREA)
            val damages = offered(Role.DAMAGE)
            val actions = offered(Role.ACTION)
            if (n > 0 && (areas.isEmpty() || damages.isEmpty())) {
                throw GeneratorError("no offered area or damage value to make findings with (areas ${areas.size}, damages ${damages.size})")
            }

            // Regions by the area value they pick, for dots (offered values only: `areas` is the offered list).
            val regionsOf: Map<String, List<String>> = if (drawing == null) emptyMap() else {
                val offeredIds = areas.map { it.id }.toSet()
                port.regionLinks().entries.filter { it.value != null && it.value in offeredIds }
                    .groupBy({ it.value!! }, { it.key })
                    .mapValues { (_, regions) -> regions.sorted() }
            }

            val vehicleCount = maxOf(1, n / 5)
            val regs = planRegs(vehicleCount, before.vehicles.map { it.regKey }.toHashSet())
            val inspections = planInspections(vehicleCount, today)
            val planned = planFindings(inspections, areas, damages, actions)

            // ---- Writes ----
            val newVehicles = ArrayList<Snapshot.VehicleRow>()
            val newInspections = ArrayList<Snapshot.InspectionRow>()
            val newFindings = ArrayList<Snapshot.FindingRow>()
            val vehicleIds = ArrayList<String>()
            val inspectionIds = ArrayList<String>()

            for ((v, typed) in regs.withIndex()) {
                val at = start + writes
                val stored = Reg.check(typed) as? Reg.Ok ?: throw GeneratorError("vehicle ${v + 1} (raw \"$typed\"): core.Reg refuses it")
                val id = expectAdded("vehicle ${v + 1} (raw \"$typed\")", write { port.addVehicle(typed) })
                vehicleIds += id
                newVehicles += Snapshot.VehicleRow(id, stored.display, stored.key, at)
                for (p in inspections.filter { it.vehicle == v }) {
                    val what = "inspection ${p.index + 1} of vehicle ${stored.display} (raw ${p.typedMileage})"
                    if (p.lower) {
                        expectRefused(
                            what, ExpectedCodes.MILEAGE_LOWER_UNCONFIRMED,
                            write { port.addInspection(id, p.typedDate, p.typedMileage, false) },
                        )
                    }
                    val iat = start + writes
                    val iid = expectAdded(what, write { port.addInspection(id, p.typedDate, p.typedMileage, p.lower) })
                    inspectionIds += iid
                    newInspections += Snapshot.InspectionRow(iid, id, Dates.toStored(p.date), p.mileage, iat)
                }
            }

            val usedAreas = HashMap<Int, MutableSet<String>>()
            for ((k, f) in planned.withIndex()) {
                val inspectionId = inspectionIds[f.inspection]
                val p = inspections[f.inspection]
                val used = usedAreas.getOrPut(f.inspection) { HashSet() }
                val sameArea = f.area.id in used
                val what = "finding ${k + 1} (inspection ${p.index + 1} of vehicle ${newVehicles[p.vehicle].regDisplay}, " +
                    "inspection id $inspectionId, area ${f.area.name})"
                val actionId = f.action?.id
                val dot = dotFor(f.area.id, regionsOf)
                val choice = if (f.action != null) ActionChoice.Value(f.action.id) else if (f.noAction) ActionChoice.NoAction else ActionChoice.NotChosen
                if (sameArea) {
                    expectRefused(
                        what, ExpectedCodes.SAME_AREA_UNCONFIRMED,
                        write { port.addFinding(inspectionId, f.area.id, f.damage.id, choice, f.rawNote, false, dot) },
                    )
                }
                val fat = start + writes
                val fid = expectAdded(what, write { port.addFinding(inspectionId, f.area.id, f.damage.id, choice, f.rawNote, sameArea, dot) })
                used += f.area.id
                val note = Text.checkNote(f.rawNote)
                if (note.problem != null) throw GeneratorError("$what: the generator made a note core.Text refuses: ${note.problem}")
                val areaList = listById.getValue(f.area.listId)
                newFindings += Snapshot.FindingRow(
                    id = fid, inspectionId = inspectionId, areaValueId = f.area.id, damageValueId = f.damage.id,
                    actionValueId = actionId, noAction = f.noAction, note = note.cleaned, createdAt = fat, updatedAt = fat,
                    areaListName = areaList.name, areaName = f.area.name, damageName = f.damage.name, actionName = f.action?.name,
                    areaListRemoved = areaList.removed, areaRemoved = f.area.removed, damageRemoved = f.damage.removed,
                    actionRemoved = f.action?.removed ?: false,
                    dotX = dot?.x, dotY = dot?.y, dotDrawing = dot?.let { drawing!!.id },
                )
            }

            val newPhotos = ArrayList<Snapshot.PhotoRow>()
            val newFiles = LinkedHashMap<String, Long>()
            if (withPhotos) addPhotos(before, newVehicles, newInspections, newFindings, newPhotos, newFiles)

            return before.copy(
                vehicles = (before.vehicles + newVehicles).sortedBy { it.id },
                inspections = (before.inspections + newInspections).sortedBy { it.id },
                findings = (before.findings + newFindings).sortedBy { it.id },
                photos = (before.photos + newPhotos).sortedBy { it.id },
                photoFiles = before.photoFiles + newFiles,
            )
        }

        /**
         * Photos (stage 2 design 4.10): after the findings, each finding gets 0 to 3, one finding exactly 20 followed by a 21st that
         * must be refused with `PHOTO_LIMIT`; then about one finding in ten with 2 or more photos gets one `movePhoto` to a random
         * position. File names are worked out here with `core.Photos.fileName`, against the files the folder already held and the
         * ones made so far; the expected rows and files are built from that, never read back.
         */
        private fun addPhotos(
            before: Snapshot, vehicles: List<Snapshot.VehicleRow>, inspections: List<Snapshot.InspectionRow>,
            findings: List<Snapshot.FindingRow>, outRows: MutableList<Snapshot.PhotoRow>, outFiles: MutableMap<String, Long>,
        ) {
            val taken = HashSet<String>(before.photoFiles.keys)
            before.photos.mapTo(taken) { it.relPath }
            val vehicleOf = vehicles.associateBy { it.id }
            val inspectionOf = inspections.associateBy { it.id }
            val zone = port.zone()
            val full = if (findings.isEmpty()) -1 else random.nextInt(findings.size)
            val perFinding = HashMap<String, MutableList<Snapshot.PhotoRow>>()

            for ((k, f) in findings.withIndex()) {
                val inspection = inspectionOf.getValue(f.inspectionId)
                val vehicle = vehicleOf.getValue(inspection.vehicleId)
                val count = if (k == full) Photos.MAX_PER_FINDING else random.nextInt(4)
                val rows = perFinding.getOrPut(f.id) { ArrayList() }
                val what = "finding ${k + 1} (${vehicle.regDisplay}, ${inspection.date}, area ${f.areaName})"
                repeat(count) { i ->
                    val jpeg = FakeJpeg.make(random)
                    val width = between(1, Photos.LONG_SIDE)
                    val height = between(1, Photos.LONG_SIDE)
                    val quality = between(Photos.MIN_QUALITY, Photos.START_QUALITY)
                    val reduced = random.nextBoolean()
                    val at = start + writes
                    val id = expectAdded("photo ${i + 1} of $what", write { port.addPhoto(f.id, jpeg, width, height, quality, reduced) })
                    val time = java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalTime()
                    val name = Photos.fileName(vehicle.regKey, java.time.LocalDate.parse(inspection.date), f.areaName ?: "", time) {
                        Photos.relPath(vehicle.regKey, it) in taken
                    }
                    val path = Photos.relPath(vehicle.regKey, name)
                    taken += path
                    outFiles[path] = jpeg.size.toLong()
                    rows += Snapshot.PhotoRow(id, f.id, rows.size, path, width, height, jpeg.size, quality, reduced, at)
                }
                if (k == full) {
                    expectRefused(
                        "21st photo of $what", ExpectedCodes.PHOTO_LIMIT,
                        write { port.addPhoto(f.id, FakeJpeg.make(random), 64, 48, Photos.START_QUALITY, false) },
                    )
                }
            }
            for ((k, f) in findings.withIndex()) {
                val rows = perFinding.getValue(f.id)
                if (rows.size < 2 || random.nextInt(10) != 0) continue
                val moving = rows[random.nextInt(rows.size)]
                val to = random.nextInt(rows.size)
                expectAdded(
                    "move photo ${moving.id} of finding ${k + 1} to $to",
                    write { port.movePhoto(moving.id, to) },
                )
                rows.remove(moving)
                rows.add(to, moving)
                for (i in rows.indices) rows[i] = rows[i].copy(position = i)
            }
            for (rows in perFinding.values) outRows += rows
        }

        /** About half of the findings on a linked, offered area get a dot on one of its regions (4.26). */
        private fun dotFor(areaId: String, regionsOf: Map<String, List<String>>): PortDot? {
            val d = drawing ?: return null
            val regions = regionsOf[areaId] ?: return null
            if (random.nextInt(2) != 0) return null
            val region = regions[random.nextInt(regions.size)]
            val p = PointSampler.inside(random, d, region)
            return PortDot(p.x / d.width, p.y / d.height)
        }

        // ---- Plans ----

        private fun planRegs(count: Int, existingKeys: Set<String>): List<String> {
            val gen = RegGenerator(random)
            val out = ArrayList<String>(count)
            while (out.size < count) {
                val r = gen.next()
                if (r.key !in existingKeys) out += r.typed
            }
            return out
        }

        /** Every vehicle's inspections: strictly increasing dates, rising mileage with about one in ten lower. */
        private fun planInspections(vehicleCount: Int, today: LocalDate): List<PlannedInspection> {
            val counts = IntArray(vehicleCount) { between(INSPECTIONS_MIN, INSPECTIONS_MAX) }
            while (counts.sum() * FINDINGS_PER_INSPECTION_MAX < n) counts[random.nextInt(vehicleCount)]++
            val extremes = n >= EXTREMES_FROM
            val days = ChronoUnit.DAYS.between(Rules.DATE_MIN, today).toInt() + 1

            val dates = Array(vehicleCount) { v ->
                val set = HashSet<LocalDate>()
                while (set.size < counts[v]) set += Rules.DATE_MIN.plusDays(random.nextInt(days).toLong())
                set
            }
            if (extremes) {
                val leapDays = (Rules.DATE_MIN.year..today.year)
                    .map { LocalDate.of(it, 1, 1) }.filter { it.isLeapYear }.map { LocalDate.of(it.year, 2, 29) }
                    .filter { !it.isAfter(today) }
                val leap = leapDays[random.nextInt(leapDays.size)]
                val v = random.nextInt(vehicleCount)
                if (leap !in dates[v]) {
                    dates[v].remove(dates[v].elementAt(random.nextInt(dates[v].size)))
                    dates[v] += leap
                }
            }

            // Mileage 0 as one vehicle's first inspection, 999,999 as another's last (4.15).
            val zeroVehicle = if (extremes) random.nextInt(vehicleCount) else -1
            val topVehicle = if (extremes) (zeroVehicle + 1 + random.nextInt(vehicleCount - 1)) % vehicleCount else -1

            val miles = Array(vehicleCount) { IntArray(counts[it]) }
            val lower = Array(vehicleCount) { BooleanArray(counts[it]) }
            for (v in 0 until vehicleCount) {
                for (i in 0 until counts[v]) {
                    val prev = if (i == 0) -1 else miles[v][i - 1]
                    when {
                        i == 0 -> miles[v][0] = if (v == zeroVehicle) 0 else random.nextInt(150_001)
                        v == topVehicle && i == counts[v] - 1 -> miles[v][i] = Rules.MILEAGE_MAX
                        prev > 0 && random.nextInt(10) == 0 -> { miles[v][i] = random.nextInt(prev); lower[v][i] = true }
                        else -> miles[v][i] = minOf(Rules.MILEAGE_MAX, prev + random.nextInt(20_001))
                    }
                }
                if (v == topVehicle && counts[v] == 1) miles[v][0] = Rules.MILEAGE_MAX
            }
            // At least one lower mileage per run when any inspection can be lower (a previous one above 0).
            if (lower.none { it.any { b -> b } }) {
                val candidates = (0 until vehicleCount).flatMap { v ->
                    (1 until counts[v]).filter { i -> miles[v][i - 1] > 0 && !(v == topVehicle && i == counts[v] - 1) }.map { v to it }
                }
                if (candidates.isNotEmpty()) {
                    val (v, i) = candidates[random.nextInt(candidates.size)]
                    miles[v][i] = random.nextInt(miles[v][i - 1])
                    lower[v][i] = true
                }
            }

            return (0 until vehicleCount).flatMap { v ->
                dates[v].sorted().mapIndexed { i, d ->
                    PlannedInspection(v, i, d, typedDate(d), miles[v][i], typedMileage(miles[v][i]), lower[v][i])
                }
            }
        }

        /**
         * Exactly n findings, 0 to 10 per inspection. The first pass gives every offered area, damage
         * and action value a finding; then random picks, about one in ten with "No action" and one in ten not chosen yet and about
         * one in twenty on an area already used in the same inspection.
         */
        private fun planFindings(
            inspections: List<PlannedInspection>, areas: List<Snapshot.ValueRow>,
            damages: List<Snapshot.ValueRow>, actions: List<Snapshot.ValueRow>,
        ): List<PlannedFinding> {
            val perInspection = IntArray(inspections.size)
            val open = inspections.indices.toMutableList()
            repeat(n) {
                val at = random.nextInt(open.size)
                val i = open[at]
                perInspection[i]++
                if (perInspection[i] == FINDINGS_PER_INSPECTION_MAX) open.removeAt(at)
            }

            val areaQueue = ArrayDeque(areas.shuffled(random))
            val damageQueue = ArrayDeque(damages.shuffled(random))
            val actionQueue = ArrayDeque(actions.shuffled(random))
            val out = ArrayList<PlannedFinding>(n)
            var sameAreaCount = 0
            val coverageEnds = IntArray(1) { -1 }
            for (i in inspections.indices) {
                val used = LinkedHashSet<Snapshot.ValueRow>()
                repeat(perInspection[i]) {
                    val area: Snapshot.ValueRow = when {
                        areaQueue.isNotEmpty() -> areaQueue.removeFirst()
                        used.isNotEmpty() && random.nextInt(20) == 0 -> used.elementAt(random.nextInt(used.size))
                        else -> {
                            val free = areas.filter { it !in used }
                            if (free.isEmpty()) used.elementAt(random.nextInt(used.size)) else free[random.nextInt(free.size)]
                        }
                    }
                    if (area in used) sameAreaCount++
                    used += area
                    val damage = damageQueue.removeFirstOrNull() ?: damages[random.nextInt(damages.size)]
                    var noAction = false
                    val action = when {
                        actions.isEmpty() -> null.also { noAction = random.nextBoolean() }
                        actionQueue.isNotEmpty() -> actionQueue.removeFirst()
                        else -> when (random.nextInt(10)) {
                            0 -> null.also { noAction = true }
                            1 -> null
                            else -> actions[random.nextInt(actions.size)]
                        }
                    }
                    out += PlannedFinding(i, area, damage, action, noAction, note())
                    if (areaQueue.isEmpty() && damageQueue.isEmpty() && actionQueue.isEmpty() && coverageEnds[0] < 0) {
                        coverageEnds[0] = out.size - 1
                    }
                }
            }
            // With 20 findings or more, at least one "No action" and one "not chosen yet" (4.15): where a
            // state is missing, turn one finding after the first pass whose action another finding also has.
            if (n >= 20 && actions.isNotEmpty()) {
                fun has(noAction: Boolean) = out.any { it.action == null && it.noAction == noAction }
                for (wanted in listOf(true, false)) {
                    if (has(wanted)) continue
                    val counts = out.mapNotNull { it.action?.id }.groupingBy { it }.eachCount()
                    val spare = (coverageEnds[0] + 1 until out.size).filter { k -> out[k].action?.let { counts.getValue(it.id) > 1 } == true }
                    if (spare.isNotEmpty()) {
                        val k = spare[random.nextInt(spare.size)]
                        out[k] = PlannedFinding(out[k].inspection, out[k].area, out[k].damage, null, wanted, out[k].rawNote)
                    }
                }
            }
            // At least one same-area finding per run: an extra (after the first pass) finding moved onto
            // the area of an earlier finding in its inspection.
            if (sameAreaCount == 0 && coverageEnds[0] >= 0) {
                val candidates = (coverageEnds[0] + 1 until out.size).filter { k -> k > 0 && out[k - 1].inspection == out[k].inspection }
                if (candidates.isNotEmpty()) {
                    val k = candidates[random.nextInt(candidates.size)]
                    val earlier = out.subList(0, k).filter { it.inspection == out[k].inspection }
                    out[k].area = earlier[random.nextInt(earlier.size)].area
                }
            }
            return out
        }

        // ---- Typed forms ----

        private fun typedDate(d: LocalDate): String =
            if (random.nextBoolean()) "${d.dayOfMonth}/${d.monthValue}/${d.year}"
            else "%02d/%02d/%04d".format(d.dayOfMonth, d.monthValue, d.year)

        /** Plain, with commas, with spaces, or with leading zeros (still at most 6 digits). */
        private fun typedMileage(m: Int): String {
            val plain = m.toString()
            return when (random.nextInt(4)) {
                0 -> plain
                1 -> Mileage.grouped(m)
                2 -> Mileage.grouped(m).replace(',', ' ')
                else -> if (plain.length < Rules.MILEAGE_DIGITS_MAX) "0".repeat(1 + random.nextInt(Rules.MILEAGE_DIGITS_MAX - plain.length)) + plain else plain
            }
        }

        /** A note typed one of the 4.15 ways: empty, short, exactly 500 code points, multi-line, quotes and accents, padding. */
        private fun note(): String = when (random.nextInt(6)) {
            0 -> listOf("", " ", "  \n\t ")[random.nextInt(3)]
            1 -> SHORT[random.nextInt(SHORT.size)]
            2 -> "  " + exactNote(Rules.NOTE_MAX) + " \t"
            3 -> (1..between(2, 4)).joinToString("\n") { SHORT[random.nextInt(SHORT.size)] }
            4 -> QUOTED[random.nextInt(QUOTED.size)]
            else -> "  " + SHORT[random.nextInt(SHORT.size)].replace(" ", "   ") + " \t\n  " + SHORT[random.nextInt(SHORT.size)] + "   "
        }

        /** Exactly [codePoints] code points that cleaning leaves as they are (single spaces inside, none at the ends). */
        private fun exactNote(codePoints: Int): String {
            val sb = StringBuilder()
            var count = 0
            var lastSpace = true
            while (count < codePoints) {
                val space = !lastSpace && count < codePoints - 1 && random.nextInt(6) == 0
                val cp = if (space) ' '.code else NOTE_CHARS[random.nextInt(NOTE_CHARS.size)]
                sb.appendCodePoint(cp)
                lastSpace = space
                count++
            }
            return sb.toString()
        }

        private fun between(min: Int, max: Int) = min + random.nextInt(max - min + 1)

        private fun render(r: PortResult): String = when (r) {
            is PortResult.Added -> "Added(${r.id})"
            is PortResult.Refused -> if (r.detail.isEmpty()) "Refused(${r.code})" else "Refused(${r.code} ${r.detail})"
        }

        private fun expectAdded(what: String, r: PortResult): String =
            (r as? PortResult.Added)?.id ?: throw GeneratorError("$what: expected Added, got ${render(r)}")

        private fun expectRefused(what: String, code: String, r: PortResult) {
            if (r !is PortResult.Refused || r.code != code) throw GeneratorError("$what: expected Refused($code), got ${render(r)}")
        }
    }

    private val SHORT = listOf(
        "Scuff", "Small dent", "Chip on edge", "Kerbed twice", "Deep scratch near handle", "Tear in bolster",
        "Stone chip", "Paint fade", "Crack 3cm", "Light swirl marks",
    )

    private val QUOTED = listOf(
        "Customer said \"mint\" – it isn't", "O'Brien's note: façade dent, über deep", "Señor's \"repair\" – łatwe",
        "Kerb 'rash' on both, naïve fix", "Owner: \"leave it\" (café car)",
    )

    private val NOTE_CHARS: IntArray = (
        ('a'..'z').map { it.code } + ('A'..'Z').map { it.code } + ('0'..'9').map { it.code } +
            listOf("é", "ü", "ñ", "ł", "'", "\"", "&", "/", "(", "🚗", "𝔸").map { it.codePointAt(0) }
        ).toIntArray()
}
