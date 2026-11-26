package com.streamlinemyjob.inspect.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** FindingText (designs/stage-1-foundation.md 4.17): the four worked examples and the rule behind them. */
class FindingTextTest {
    private fun view(
        list: String, area: String, damage: String, action: String?, note: String? = null,
        listRemoved: Boolean = false, areaRemoved: Boolean = false, damageRemoved: Boolean = false, actionRemoved: Boolean = false,
        noAction: Boolean = action == null,
    ) = FindingView(list, listRemoved, area, areaRemoved, damage, damageRemoved, action, actionRemoved, noAction, note)

    @Test
    fun example1AnAlloyFindingWithAnAction() {
        assertEquals("Alloys › DSF: Kerbed → Alloy refurb", FindingText.of(view("Alloys", "DSF", "Kerbed", "Alloy refurb")))
    }

    @Test
    fun example2ARemovedDamageIsMarked() {
        assertEquals(
            "Panels › DSF door: Dent (removed) → Dent removal (PDR)",
            FindingText.of(view("Panels", "DSF door", "Dent", "Dent removal (PDR)", damageRemoved = true)),
        )
    }

    @Test
    fun example3ARemovedListAndNoAction() {
        assertEquals(
            "Tyres (removed) › PSR: Crack → No action",
            FindingText.of(view("Tyres", "PSR", "Crack", null, listRemoved = true)),
        )
    }

    @Test
    fun example4AwkwardGeneratedNamesAreUsedAsTheyAre() {
        assertEquals(
            "𝔸rch & Trim › O'Brien \"Mint\": Chip → Valet ñ",
            FindingText.of(view("𝔸rch & Trim", "O'Brien \"Mint\"", "Chip", "Valet ñ")),
        )
    }

    @Test
    fun theNoteGoesOnTheNextLinesAndOnlyThere() {
        val withNote = FindingText.of(view("Alloys", "DSF", "Kerbed", null, note = "Deep gouge\nnear the valve"))
        assertEquals("Alloys › DSF: Kerbed → No action\nDeep gouge\nnear the valve", withNote)
        val without = FindingText.of(view("Alloys", "DSF", "Kerbed", null, note = null))
        assertFalse(without.contains('\n'))
    }

    @Test
    fun removedMarksAppearExactlyWhereTheFlagsAre() {
        val random = Random(20261125)
        repeat(300) {
            val names = List(4) { "n${random.nextInt(1_000_000)} ${"ab 𝔸&\"'ñ-".random(random)}" }
            val flags = List(4) { random.nextBoolean() }
            val v = FindingView(names[0], flags[0], names[1], flags[1], names[2], flags[2], names[3], flags[3], false, null)
            val expected = "${names[0]}${mark(flags[0])} › ${names[1]}${mark(flags[1])}: " +
                "${names[2]}${mark(flags[2])} → ${names[3]}${mark(flags[3])}"
            assertEquals(expected, FindingText.of(v))
        }
    }

    private fun mark(removed: Boolean) = if (removed) " (removed)" else ""

    private fun String.random(random: Random): Char = this[random.nextInt(length)]

    @Test
    fun theSameAreaQuestionNamesTheExistingFinding() {
        assertEquals(
            "DSF door already has a finding in this inspection (Dent → Dent removal (PDR)). Add another?",
            FindingText.sameAreaQuestion(view("Panels", "DSF door", "Dent", "Dent removal (PDR)")),
        )
        assertEquals(
            "DSF door (removed) already has a finding in this inspection (Dent (removed) → No action). Add another?",
            FindingText.sameAreaQuestion(view("Panels", "DSF door", "Dent", null, areaRemoved = true, damageRemoved = true)),
        )
    }

    @Test
    fun anActionNotChosenYetSaysSoAndIsNotNoAction() {
        assertEquals(
            "Panels › Bonnet: Scratch → Action not chosen yet",
            FindingText.of(view("Panels", "Bonnet", "Scratch", null, noAction = false)),
        )
        assertEquals("Action not chosen yet", FindingText.NOT_CHOSEN)
        // The three states of 4.29 give three different endings.
        val endings = listOf(
            FindingText.of(view("A", "B", "C", "Valet")),
            FindingText.of(view("A", "B", "C", null, noAction = true)),
            FindingText.of(view("A", "B", "C", null, noAction = false)),
        ).map { it.substringAfter("→ ") }
        assertEquals(listOf("Valet", "No action", "Action not chosen yet"), endings)
        assertEquals(
            "B already has a finding in this inspection (C → Action not chosen yet). Add another?",
            FindingText.sameAreaQuestion(view("A", "B", "C", null, noAction = false)),
        )
    }

    @Test
    fun noActionIsAFixedTextNotAName() {
        assertTrue(FindingText.of(view("A", "B", "C", null)).endsWith("→ No action"))
    }
}
