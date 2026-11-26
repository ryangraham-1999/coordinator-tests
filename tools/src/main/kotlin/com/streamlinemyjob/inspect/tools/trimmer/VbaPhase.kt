package com.streamlinemyjob.inspect.tools.trimmer

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Phase 4 of `tools check-trimmer`, `VBA` (designs/stage-B-trimmer.md 4.12, 7c; P1 settled yes at SB.3).
 * Runs the real macro, `Trimmer.TrimSheet`, on every case of `TrimmerTestData.bas` in LibreOffice (VBA mode),
 * canary included. LibreOffice's VBA cannot read cell borders, so the listing (4.10.2) of each result is
 * written by the Python script below through LibreOffice's own interface, not by the VBA self-test
 * (owner's answer at SB.5, option 1); the self-test's own code first runs in full on the work PC (SB.7).
 */
fun interface VbaRunner {
    fun run(trimmerDir: File, workDir: File): VbaOutcome
}

sealed interface VbaOutcome {
    /** Not run here (no LibreOffice): shown, but does not fail the check. */
    data class Skipped(val line: String) : VbaOutcome

    /** Ran: [line] is the summary (`VBA PASS(15) canary=FAIL (as it should) big=4.0s`), [failures] the detail lines. */
    data class Ran(val passed: Boolean, val line: String, val failures: List<String>) : VbaOutcome
}

object LibreOfficeVba : VbaRunner {
    /** The whole phase may take this long (the 16 cases take about 3 minutes). */
    const val TIMEOUT_MINUTES = 15L

    override fun run(trimmerDir: File, workDir: File): VbaOutcome {
        workDir.deleteRecursively()
        workDir.mkdirs()
        val script = File(workDir, "vba_phase.py").apply { writeText(SCRIPT) }
        val log = File(workDir, "vba-phase.log")
        val p = try {
            ProcessBuilder("python3", "-I", script.path, trimmerDir.absolutePath, workDir.absolutePath)
                .redirectErrorStream(true).redirectOutput(log).start()
        } catch (e: java.io.IOException) {
            return VbaOutcome.Skipped("VBA SKIPPED (no python3 here; runs on the work PC only)")
        }
        if (!p.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            p.descendants().forEach { it.destroyForcibly() }
            p.destroyForcibly()
            return VbaOutcome.Ran(false, "VBA FAIL timed out", listOf("no result after $TIMEOUT_MINUTES minutes; see build/check/trimmer-vba/vba-phase.log"))
        }
        val lines = log.readLines()
        val head = lines.lastOrNull { it.startsWith("VBA ") }
        return when {
            p.exitValue() == 3 && head != null && head.startsWith("VBA SKIPPED") -> VbaOutcome.Skipped(head)
            head == null -> VbaOutcome.Ran(false, "VBA FAIL no result", listOf("exit ${p.exitValue()}; see build/check/trimmer-vba/vba-phase.log") + lines.takeLast(3).map { "| $it" })
            else -> VbaOutcome.Ran(p.exitValue() == 0, head, lines.dropWhile { it != head }.drop(1).filter { it.startsWith("  ") }.map { it.trim() })
        }
    }

    /** Written to build/check/trimmer-vba/vba_phase.py and run with `python3 -I`. */
    val SCRIPT = """
# The VBA phase of `tools check-trimmer` (designs/stage-B-trimmer.md 4.12, SB.5).
# Written out and run by VbaPhase.kt; usage: python3 -I vba_phase.py <trimmer folder> <work folder>
# Starts LibreOffice headless with a private profile, loads Trimmer.bas and TrimmerTestData.bas in VBA
# mode into a new document, and for every case: fills a new workbook (TrimmerTestData.FillCase), runs
# the real Trimmer.TrimSheet on it, and writes the result's listing (4.10.2) here, through
# LibreOffice's own interface, because LibreOffice's VBA cannot read cell borders (owner's answer,
# SB.5). The listing is compared with the case's CaseExpected. The case's own sheets must be unchanged.
# Prints one line, "VBA PASS(15) canary=FAIL (as it should) big=1.2s" or "VBA FAIL(...)", then
# up to five lines "  <detail>". Exit 0 pass, 1 fail, 3 when LibreOffice or Python uno is not here.
import os, shutil, subprocess, sys, time
from decimal import Decimal

HELPER = "\n".join([
    "Option VBASupport 1",
    "Option Explicit",
    "Public Function NewCase(ByVal i As Long) As String",
    "    Dim wb As Workbook",
    "    Set wb = Workbooks.Add(xlWBATWorksheet)",
    "    TrimmerTestData.FillCase i, wb.Worksheets(1)",
    "    NewCase = wb.Name & vbLf & wb.Worksheets(1).Name",
    "End Function",
    "Public Function TrimCase(ByVal wbName As String, ByVal wsName As String) As String",
    "    Dim rs As Worksheet, m As String",
    "    Set rs = Trimmer.TrimSheet(Workbooks(wbName).Worksheets(wsName), True, m)",
    "    If rs Is Nothing Then TrimCase = \"MSG\" & vbLf & m Else TrimCase = \"TAB\" & vbLf & rs.Name",
    "End Function",
    "Public Function CaseInfo(ByVal i As Long) As String",
    "    CaseInfo = TrimmerTestData.CaseName(i) & vbLf & TrimmerTestData.CaseExpected(i)",
    "End Function",
    "Public Function CaseTotal() As Long",
    "    CaseTotal = TrimmerTestData.CaseCount()",
    "End Function",
])
CANARY = "canary"
BIG = "big"
SEP = " | "
TICK = "[ ]"
EMPTY_LINE = SEP.join([""] * 7)


def skip(why):
    print("VBA SKIPPED (" + why + "; runs on the work PC only)")
    sys.exit(3)


try:
    import uno
    from com.sun.star.beans import PropertyValue
except Exception:
    skip("no LibreOffice Python here")


def pv(name, value):
    p = PropertyValue()
    p.Name = name
    p.Value = value
    return p


def shortest(v):
    if v == 0:
        return "0"
    return format(Decimal(repr(float(v))).normalize(), "f")


def module_code(path):
    text = open(path, encoding="ascii").read().replace("\r\n", "\n")
    lines = text.split("\n")
    name = lines[0].split('"')[1]
    return name, "Option VBASupport 1\n" + "\n".join(l for l in lines if not l.startswith("Attribute "))


def find_doc(desk, title):
    e = desk.getComponents().createEnumeration()
    while e.hasMoreElements():
        c = e.nextElement()
        try:
            if c.getTitle() == title:
                return c
        except Exception:
            pass
    return None


def listing(doc, sheet):
    nf = doc.NumberFormats
    cur = sheet.createCursor()
    cur.gotoEndOfUsedArea(False)
    last = cur.RangeAddress.EndRow
    lines, vehicles, rows = [], 0, []
    for r in range(last + 1):
        cells = []
        for c in range(7):
            cell = sheet.getCellByPosition(c, r)
            kind = cell.Type.value
            if kind == "EMPTY":
                edges = [cell.LeftBorder, cell.TopBorder, cell.BottomBorder, cell.RightBorder]
                s = TICK if c in (2, 5) and all(e.OuterLineWidth > 0 for e in edges) else ""
            elif kind == "VALUE":
                f = nf.getByKey(cell.NumberFormat).FormatString
                s = shortest(cell.Value) + ("" if f == "General" else " {" + f + "}")
            else:
                s = cell.String
            cells.append(s)
        if cells[2] == TICK:
            vehicles += 1
            rows.append(r)
        lines.append(SEP.join(cells))
    return lines, vehicles, rows


def block_summary(lines, vehicles):
    out, title, n = [], None, 0
    for line in lines[2:] + [EMPTY_LINE]:
        if line == EMPTY_LINE:
            if title is not None:
                out.append("BLOCK " + title + " " + str(n))
            title, n = None, 0
        elif title is None:
            title = line.split(SEP)[0]
        elif line.split(SEP)[2] == TICK:
            n += 1
    return "\n".join(out) + "\nROWS " + str(vehicles) + "\n"


def pairing_problem(sheet, rows):
    # 4.10.3: each big row's description is "Car <reg> m<mileage> p<price in pence>".
    for r in rows:
        desc = sheet.getCellByPosition(0, r).String
        reg_cell = sheet.getCellByPosition(1, r)
        reg = "" if reg_cell.Type.value == "EMPTY" else (shortest(reg_cell.Value) if reg_cell.Type.value == "VALUE" else reg_cell.String)
        want = "Car " + reg + " m" + shortest(sheet.getCellByPosition(3, r).Value) + " p" + str(int(round(sheet.getCellByPosition(4, r).Value * 100)))
        if desc != want:
            return BIG + " row " + str(r + 1) + ': expected "' + want + '" got "' + desc + '"'
    return ""


def snapshot(doc, skip_name=None):
    shots = []
    for name in doc.Sheets.ElementNames:
        if name == skip_name:
            continue
        sh = doc.Sheets.getByName(name)
        cur = sh.createCursor()
        cur.gotoEndOfUsedArea(False)
        a = cur.RangeAddress
        data = sh.getCellRangeByPosition(0, 0, a.EndColumn, a.EndRow).getDataArray()
        fmts = tuple(tuple(sh.getCellByPosition(c, r).NumberFormat for c in range(a.EndColumn + 1)) for r in range(a.EndRow + 1))
        shots.append((name, data, fmts))
    return shots


def first_difference(name, expected, actual):
    e, a = expected.split("\n"), actual.split("\n")
    for i in range(max(len(e), len(a))):
        el = e[i] if i < len(e) else ""
        al = a[i] if i < len(a) else ""
        if el != al or i >= len(e) or i >= len(a):
            return name + " line " + str(i + 1) + ': expected "' + el + '" got "' + al + '"'
    return name + ": differs"


def connect(port, proc):
    local = uno.getComponentContext()
    resolver = local.ServiceManager.createInstanceWithContext("com.sun.star.bridge.UnoUrlResolver", local)
    for _ in range(240):
        try:
            return resolver.resolve("uno:socket,host=localhost,port=" + str(port) + ";urp;StarOffice.ComponentContext")
        except Exception:
            if proc.poll() is not None:
                break
            time.sleep(0.5)
    print("VBA FAIL could not start LibreOffice")
    sys.exit(1)


def run(trimmer_dir, work):
    port = 2111
    profile = os.path.join(work, "profile")
    proc = subprocess.Popen(
        ["soffice", "--headless", "--invisible", "--norestore", "--nologo",
         "-env:UserInstallation=" + uno.systemPathToFileUrl(profile),
         "--accept=socket,host=localhost,port=" + str(port) + ";urp;"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
    try:
        return cases(trimmer_dir, work, connect(port, proc))
    finally:
        try:
            os.killpg(proc.pid, 9)
        except Exception:
            pass
        proc.wait()


def cases(trimmer_dir, work, ctx):
    desk = ctx.ServiceManager.createInstanceWithContext("com.sun.star.frame.Desktop", ctx)
    doc = desk.loadComponentFromURL("private:factory/scalc", "_blank", 0, (pv("Hidden", True),))
    libs = doc.BasicLibraries
    if not libs.hasByName("Standard"):
        libs.createLibrary("Standard")
    lib = libs.getByName("Standard")
    for f in ("Trimmer.bas", "TrimmerTestData.bas"):
        name, code = module_code(os.path.join(trimmer_dir, f))
        lib.insertByName(name, code)
    lib.insertByName("VbaPhaseHelper", HELPER)
    libs.VBACompatibilityMode = True
    url = uno.systemPathToFileUrl(os.path.join(work, "vba-phase.ods"))
    doc.storeToURL(url, (pv("FilterName", "calc8"),))
    doc.close(True)
    doc = desk.loadComponentFromURL(url, "_blank", 0, (pv("Hidden", True), pv("MacroExecutionMode", 4)))
    sp = doc.getScriptProvider()

    def script(name):
        s = sp.getScript("vnd.sun.star.script:Standard.VbaPhaseHelper." + name + "?language=Basic&location=document")
        return lambda *args: s.invoke(tuple(args), (), ())[0]

    new_case, trim_case, case_info, count = script("NewCase"), script("TrimCase"), script("CaseInfo"), script("CaseTotal")
    total = passed = 0
    failed, details, big_seconds, canary_failed = [], [], None, None
    for i in range(1, count() + 1):
        name, expected = case_info(i).split("\n", 1)
        wb_name, ws_name = new_case(i).split("\n")
        case_doc = find_doc(desk, wb_name)
        before = snapshot(case_doc)
        modified_before = doc.isModified()
        t = time.time()
        result = trim_case(wb_name, ws_name).split("\n", 1)
        seconds = time.time() - t
        detail = ""
        if result[0] == "MSG":
            actual = result[1]
            if len(case_doc.Sheets.ElementNames) != len(before):
                detail = name + ": a tab was added although the trim stopped"
            after = snapshot(case_doc)
        else:
            sheet = case_doc.Sheets.getByName(result[1])
            lines, vehicles, rows = listing(case_doc, sheet)
            actual = "\n".join(lines) + "\nROWS " + str(vehicles) + "\n"
            if name == BIG:
                actual = block_summary(lines, vehicles)
                detail = pairing_problem(sheet, rows)
                big_seconds = seconds
            if len(case_doc.Sheets.ElementNames) != len(before) + 1:
                detail = name + ": the tab count went from " + str(len(before)) + " to " + str(len(case_doc.Sheets.ElementNames))
            after = snapshot(case_doc, result[1])
        if not detail and after != before:
            detail = name + ": the case's own sheets changed"
        if not detail and doc.isModified() != modified_before:
            detail = name + ": the trimmer file was changed"
        if not detail and actual != expected:
            detail = first_difference(name, expected, actual)
        case_doc.close(True)
        if name == CANARY:
            canary_failed = bool(detail)
            continue
        total += 1
        if detail:
            failed.append(name)
            details.append(detail)
        else:
            passed += 1
    doc.close(True)

    if canary_failed is False:
        print("VBA BROKEN: canary passed")
        return 1
    if passed == total:
        line = "VBA PASS(" + str(total) + ")"
    else:
        line = "VBA FAIL(" + str(passed) + "/" + str(total) + "): " + ", ".join(failed)
    if canary_failed:
        line += " canary=FAIL (as it should)"
    if big_seconds is not None:
        line += " big=" + ("%.1f" % big_seconds) + "s"
    print(line)
    for d in details[:5]:
        print("  " + d)
    return 0 if passed == total and canary_failed else 1


if __name__ == "__main__":
    if shutil.which("soffice") is None:
        skip("no LibreOffice here")
    os.makedirs(sys.argv[2], exist_ok=True)
    sys.exit(run(sys.argv[1], sys.argv[2]))
"""
}
