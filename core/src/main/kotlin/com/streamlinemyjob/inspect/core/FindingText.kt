package com.streamlinemyjob.inspect.core

/**
 * A finding with the names it shows (designs/stage-1-foundation.md 4.17): the area list, area, damage
 * and action names as they are now (a rename shows at once, C5), each with whether it is removed, and
 * the note. [actionName] is null for a finding with no action. Nothing in it is a fixed name: the text
 * is built from whatever the owner's lists hold.
 */
data class FindingView(
    val areaListName: String,
    val areaListRemoved: Boolean,
    val areaName: String,
    val areaRemoved: Boolean,
    val damageName: String,
    val damageRemoved: Boolean,
    val actionName: String?,
    val actionRemoved: Boolean,
    /** "No action" chosen on purpose (4.29); only meaningful when [actionName] is null. */
    val noAction: Boolean,
    val note: String?,
)

/** The one function every screen (and later the PDF) uses to write a finding (4.17). */
object FindingText {
    const val REMOVED = " (removed)"
    const val NO_ACTION = "No action"
    const val NOT_CHOSEN = "Action not chosen yet"

    /**
     * ```
     * <area list name>[ (removed)] › <area name>[ (removed)]: <damage name>[ (removed)] → <action name>[ (removed)] | No action | Action not chosen yet
     * <note, if any, on the next line(s)>
     * ```
     * The note follows after a line break, exactly as it was cleaned and saved (4.7). The dot of a
     * finding does not change this text (4.17).
     */
    fun of(finding: FindingView): String {
        val head = buildString {
            append(marked(finding.areaListName, finding.areaListRemoved))
            append(" › ")
            append(marked(finding.areaName, finding.areaRemoved))
            append(": ")
            append(damageAndAction(finding))
        }
        return if (finding.note == null) head else head + "\n" + finding.note
    }

    /** `<damage> → <action>`, `<damage> → No action` or `<damage> → Action not chosen yet`, with each name marked if removed. */
    fun damageAndAction(finding: FindingView): String =
        marked(finding.damageName, finding.damageRemoved) + " → " +
            (finding.actionName?.let { marked(it, finding.actionRemoved) } ?: if (finding.noAction) NO_ACTION else NOT_CHOSEN)

    /**
     * The question the same-area dialog asks (4.12, C4) about the finding already on that area:
     * `DSF door already has a finding in this inspection (Dent → Dent removal (PDR)). Add another?`
     */
    fun sameAreaQuestion(existing: FindingView): String =
        marked(existing.areaName, existing.areaRemoved) + " already has a finding in this inspection (" +
            damageAndAction(existing) + "). Add another?"

    private fun marked(name: String, removed: Boolean): String = if (removed) name + REMOVED else name
}
