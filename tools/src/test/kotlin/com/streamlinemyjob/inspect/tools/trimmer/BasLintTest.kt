package com.streamlinemyjob.inspect.tools.trimmer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** `BasLint` against designs/stage-B-trimmer.md 4.9 and 6: a fixture with each fault in turn, one test per fault. */
class BasLintTest {
    /** A small clean module; [extra] lines go inside its one procedure. */
    private fun module(name: String = "Fixture", extra: List<String> = emptyList(), head: List<String>? = null): String {
        val lines = (head ?: listOf("Attribute VB_Name = \"$name\"", "' A fixture.", "Option Explicit", "")) +
            listOf("Public Sub Work(ByVal ws As Worksheet)", "    Dim n As Long") + extra.map { "    $it" } +
            listOf("    ws.Cells(1, 1).Value = n", "End Sub")
        return lines.joinToString("") { "$it\r\n" }
    }

    private fun lint(text: String, file: String = "Fixture.bas") = BasLint.check(file, text.toByteArray(Charsets.ISO_8859_1))
    private fun rules(text: String, file: String = "Fixture.bas") = lint(text, file).map { it.rule }

    @Test
    fun aCleanModulePasses() {
        assertEquals(emptyList<BasLint.Problem>(), lint(module()))
    }

    @Test
    fun aMissingOrWrongVbNameFails() {
        assertEquals(listOf("VB_NAME"), rules(module(head = listOf("' no attribute", "Option Explicit"))))
        assertEquals(listOf("VB_NAME"), rules(module(name = "Other")))
    }

    @Test
    fun aMissingOrLateOptionExplicitFails() {
        assertEquals(listOf("OPTION_EXPLICIT"), rules(module(head = listOf("Attribute VB_Name = \"Fixture\"", "' no option"))))
        assertEquals(listOf("OPTION_EXPLICIT"), rules(module(head = listOf("Attribute VB_Name = \"Fixture\"", "Private Const A As Long = 1", "Option Explicit"))))
    }

    @Test
    fun lfOnlyOrABareCrOrNoLastCrlfFails() {
        assertEquals(listOf("NOT_CRLF"), rules(module().replace("\r\n", "\n")))
        assertEquals(listOf("NOT_CRLF"), rules(module().replaceFirst("\r\n", "\r")))
        assertEquals(listOf("NOT_CRLF"), rules(module().removeSuffix("\r\n")))
    }

    @Test
    fun aNonAsciiCharacterFails() {
        assertEquals(listOf("NOT_ASCII"), rules(module(extra = listOf("' price in \u00a3"))))
        assertEquals(listOf("NOT_ASCII"), rules(module(extra = listOf("n = 1\u00a0"))))
    }

    @Test
    fun eachForbiddenWordFailsOnItsOwn() {
        val bad = mapOf(
            "Declare" to "Declare Function GetTickCount Lib \"kernel32\" () As Long",
            "Shell" to "Shell \"notepad.exe\"",
            "SendKeys" to "SendKeys \"~\"",
            "Kill" to "Kill \"C:\\x.txt\"",
            "FileCopy" to "FileCopy \"a\", \"b\"",
            "Open ... For Output" to "Open \"C:\\x.txt\" For Output As #1",
            "CreateObject" to "Set o = CreateObject(\"WScript.Shell\")",
            "Workbooks.Open" to "Workbooks.Open \"C:\\x.xlsx\"",
            "Application.Run" to "Application.Run name & \"X\"",
            ".Select" to "ws.Range(\"A1\").Select",
            "Selection" to "Selection.Copy",
            "ActiveWorkbook" to "Set wb = ActiveWorkbook",
            "ActiveSheet" to "Set ws = ActiveSheet",
            ".Save" to "ws.Parent.Save",
            ".Sort" to "ws.Range(\"A1:G9\").Sort Key1:=ws.Range(\"B1\")",
            "SortBy" to "v = Application.SortBy(r, k)",
            "dynamic-array function" to "v = Application.WorksheetFunction.Unique(r)",
            "PtrSafe" to "' see below",
            "LongLong" to "Dim big As LongLong",
        )
        assertEquals(BasLint.FORBIDDEN.map { it.first }.toSet(), bad.keys)
        for ((word, line) in bad) {
            val text = if (word == "PtrSafe") module(head = listOf("Attribute VB_Name = \"Fixture\"", "Option Explicit", "Private Declare PtrSafe Function F Lib \"x\" () As Long"))
            else module(extra = listOf(line))
            val found = lint(text).filter { it.rule == "FORBIDDEN" }.map { it.detail }
            assertTrue("$word not caught in: $line (found $found)", word in found)
        }
    }

    @Test
    fun forbiddenWordsInCommentsOrTextAndTheAllowedFormsPass() {
        val ok = module(extra = listOf(
            "' Shell, Kill, ActiveSheet and .Select in a comment",
            "Rem SendKeys in a Rem line",
            "ws.Cells(2, 1).Value = \"Selection ActiveWorkbook .Sort Kill\"",
            "Dim d As Object",
            "Set d = CreateObject(\"Scripting.Dictionary\")",
            "Application.Run \"TrimStock\"",
            "ws.Activate",
            "Select Case n",
            "End Select",
        ))
        assertEquals(emptyList<BasLint.Problem>(), lint(ok))
    }

    @Test
    fun aProcedureOf400LinesFailsAnd399Passes() {
        // module() makes a procedure of 4 lines plus the extra ones.
        assertEquals(listOf("PROC_TOO_LONG"), rules(module(extra = List(396) { "n = n + 1" })))
        assertEquals(emptyList<String>(), rules(module(extra = List(395) { "n = n + 1" })))
    }

    @Test
    fun anUnclosedProcedureFails() {
        assertEquals(listOf("PROC_UNCLOSED"), rules(module().replace("End Sub\r\n", "")))
    }

    @Test
    fun aConstantTheSelfTestUsesMustBePublicInTrimmer() {
        val trimmer = "Attribute VB_Name = \"Trimmer\"\r\nOption Explicit\r\nPublic Const TAB_NAME As String = \"Trimmed\"\r\n" +
            "Public Function TrimSheet(ByVal ws As Worksheet, ByVal quiet As Boolean) As Variant\r\nEnd Function\r\n"
        val good = "Attribute VB_Name = \"TrimmerSelfTest\"\r\nOption Explicit\r\nPrivate Const MY_LIMIT As Long = 5\r\n" +
            "Public Sub RunSelfTest()\r\n    Dim v As Variant\r\n    v = Trimmer.TrimSheet(Nothing, True)\r\n    Debug.Print TAB_NAME, Trimmer.TAB_NAME, MY_LIMIT\r\nEnd Sub\r\n"
        assertEquals(emptyList<BasLint.Problem>(), BasLint.checkConstants(trimmer, good))
        val badQualified = good.replace("Trimmer.TAB_NAME", "Trimmer.NONE_TEXT")
        assertEquals(listOf("CONST_MISSING"), BasLint.checkConstants(trimmer, badQualified).map { it.rule })
        val badBare = good.replace("Debug.Print TAB_NAME", "Debug.Print KEPT_NAMES")
        assertEquals(listOf("CONST_MISSING"), BasLint.checkConstants(trimmer, badBare).map { it.rule })
    }

    @Test
    fun theFolderCheckNeedsAllFourModulesAndChecksEveryBasFile() {
        val dir = Files.createTempDirectory("baslint").toFile()
        try {
            assertEquals(
                listOf("MISSING Trimmer.bas", "MISSING TrimmerSelfTest.bas", "MISSING TrimmerTestData.bas", "MISSING TrimmerKeys.bas"),
                BasLint.checkFolder(dir).map { "${it.rule} ${it.file}" },
            )
            dir.resolve("TrimmerTestData.bas").writeBytes(module(name = "TrimmerTestData").toByteArray())
            dir.resolve("TrimmerSelfTest.bas").writeBytes(module(name = "TrimmerSelfTest").toByteArray())
            dir.resolve("TrimmerKeys.bas").writeBytes(module(name = "TrimmerKeys").toByteArray())
            assertEquals(listOf("MISSING Trimmer.bas"), BasLint.checkFolder(dir).map { "${it.rule} ${it.file}" })
            dir.resolve("Trimmer.bas").writeBytes(module(name = "Trimmer").replace("\r\n", "\n").toByteArray())
            assertEquals(listOf("NOT_CRLF Trimmer.bas"), BasLint.checkFolder(dir).map { "${it.rule} ${it.file}" })
            dir.resolve("Trimmer.bas").writeBytes(module(name = "Trimmer").toByteArray())
            assertEquals(emptyList<BasLint.Problem>(), BasLint.checkFolder(dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
