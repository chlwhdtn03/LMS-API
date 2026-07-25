package io.github.chlwhdtn03.internal

import io.github.chlwhdtn03.GradeCache
import io.github.chlwhdtn03.data.Lms.*
import io.github.chlwhdtn03.decodeHtmlEntities
import io.github.chlwhdtn03.stripHtmlTags
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** 성적 상세·학기별 성적 요약 조회와 파싱을 담당합니다. */
@OptIn(ExperimentalTime::class)
internal class GradeService(
    private val webDynpro: WebDynproService,
    private val cache: GradeCache,
) {
    suspend fun getGradeTable(year: String?, semester: Semester?): GradeTable {
        if (year == null && semester == null) {
            val cachedYear = cache.latestYear
            val cachedSemester = cache.latestSemester
            if (cachedYear != null && cachedSemester != null) {
                return getGradeTable(cachedYear, cachedSemester)
            }
        }

        val (_, gradeTable) = loadGradeContextAndTable(year, semester)
        if (year == null && semester == null) {
            cache.latestYear = gradeTable.year
            cache.latestSemester = gradeTable.semester
        }
        return gradeTable
    }

    private suspend fun loadGradeContextAndTable(
        year: String?,
        semester: Semester?,
    ): Pair<WebDynproContext, GradeTable> {
        var context = webDynpro.openSession(URL, APP_NAME)
        val currentHtml = context.html
        if (cache.latestYear == null || cache.latestSemester == null) {
            val defaultYear = webDynpro.parseYear(currentHtml)
            val defaultSemester = webDynpro.parseSemester(currentHtml)
            if (defaultYear.isNotBlank() && defaultSemester != null) {
                cache.latestYear = defaultYear
                cache.latestSemester = defaultSemester
            }
        }

        val yearControlId = Regex(
            """<label\b[^>]*\bfor="([^"]+)"[^>]*>(?:(?!</?label\b).)*?학년도""",
            RegexOption.IGNORE_CASE,
        ).find(currentHtml)?.groupValues?.get(1)
            ?: Regex("""id="([^"]+:VIW_MAIN\.PERYR)"""")
                .find(currentHtml)?.groupValues?.get(1)
            ?: DEFAULT_YEAR_CONTROL_ID
        val semesterControlId = Regex(
            """<label\b[^>]*\bfor="([^"]+)"[^>]*>(?:(?!</label>).)*?학기""",
            RegexOption.IGNORE_CASE,
        ).find(currentHtml)?.groupValues?.get(1)
            ?: Regex("""id="([^"]+:VIW_MAIN\.PERID)"""")
                .find(currentHtml)?.groupValues?.get(1)
            ?: DEFAULT_SEMESTER_CONTROL_ID
        val searchButtonId = Regex(
            """<(?:div|button)\b[^>]*\bid="([^"]+)"[^>]*ct="B"[^>]*>(?:(?!<(?:div|button)\b).)*?조회""",
            RegexOption.IGNORE_CASE,
        ).find(currentHtml)?.groupValues?.get(1)
            ?: Regex("""id="([^"]+:VIW_MAIN\.BTN_SEARCH)"""")
                .find(currentHtml)?.groupValues?.get(1)
            ?: DEFAULT_SEARCH_BUTTON_ID

        val buttonEvent =
            "Button_Press~E002Id~E004$searchButtonId~E003~E002ClientAction~E004submit" +
                "~E005ResponseData~E004delta~E003~E002~E003"
        val focusInfo = webDynpro.escape("""{"sFocussedId":"$searchButtonId"}""")
        val formRequest =
            "Form_Request~E002Id~E004sap.client.SsrClient.form~E005Async~E004false" +
                "~E005FocusInfo~E004$focusInfo~E005Hash~E004~E005DomChanged~E004false" +
                "~E005IsDirty~E004false~E003~E002ResponseData~E004delta~E003~E002~E003"
        val events = if (year != null && semester != null) {
            val yearEvent =
                "ComboBox_Select~E002Id~E004$yearControlId~E005Key~E004$year" +
                    "~E005ByEnter~E004false~E003~E002ClientAction~E004submit" +
                    "~E005ResponseData~E004delta~E003~E002~E003"
            val semesterEvent =
                "ComboBox_Select~E002Id~E004$semesterControlId~E005Key~E004${semester.code}" +
                    "~E005ByEnter~E004false~E003~E002ClientAction~E004submit" +
                    "~E005ResponseData~E004delta~E003~E002~E003"
            listOf(yearEvent, semesterEvent, buttonEvent, formRequest)
        } else {
            listOf(buttonEvent, formRequest)
        }

        context = webDynpro.submitEvents(
            context = context,
            eventQueue = events.joinToString("~E001"),
        )
        return context to parseGradeTable(context.html, year, semester)
    }

    suspend fun getSemesterGradeSummaryTable(): SemesterGradeSummaryTable {
        val currentYear = Clock.System.now().toString().take(4).toInt()
        var (context, _) = loadGradeContextAndTable(
            currentYear.minus(1).toString(),
            Semester.FIRST,
        )
        context = webDynpro.refreshSession(context)
        var initialHtml = context.html
        var summaryTable = parseSemesterGradeSummaryTable(initialHtml)
        if (summaryTable.items.isEmpty()) {
            val retry = loadGradeContextAndTable(
                currentYear.minus(1).toString(),
                Semester.FIRST,
            )
            context = webDynpro.refreshSession(retry.first)
            initialHtml = context.html
            summaryTable = parseSemesterGradeSummaryTable(initialHtml)
        }

        val tableId = findSemesterGradeSummaryTableId(initialHtml) ?: return summaryTable
        val visibleRowCount = summaryTable.items.size
        if (visibleRowCount == 0) return summaryTable

        var firstVisibleRow = visibleRowCount
        repeat(20) {
            context = fetchTableRows(context, tableId, firstVisibleRow)
            val scrolledTable = parseSemesterGradeSummaryTable(context.html)
            if (scrolledTable.items.isEmpty()) return summaryTable

            val merged = mergeSemesterGradeSummaryTables(summaryTable, scrolledTable)
            if (merged.items.size == summaryTable.items.size) return summaryTable
            summaryTable = merged
            firstVisibleRow += visibleRowCount
        }
        return summaryTable
    }

    fun findSemesterGradeSummaryTableId(html: String): String? {
        val decodedHtml = html.decodeHtmlEntities()
        val controlRegex = Regex(
            """<(?:div|table)\b(?=[^>]*\bct="(?:ST|CT)")(?=[^>]*\bid="([^"]+)")[^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        val controls = controlRegex.findAll(decodedHtml).toList()
        for ((index, control) in controls.withIndex()) {
            val end = controls.getOrNull(index + 1)?.range?.first ?: decodedHtml.length
            val controlHtml = decodedHtml.substring(control.range.first, end)
            if (parseSemesterGradeSummaryTable(controlHtml).items.isNotEmpty()) {
                return control.groupValues[1]
            }
        }

        val summaryRow = Regex(
            """<tr\b[^>]*>([\s\S]*?)</tr>""",
            RegexOption.IGNORE_CASE,
        ).findAll(decodedHtml)
            .firstOrNull { parseSemesterGradeSummaryTable(it.value).items.isNotEmpty() }
            ?: return null
        val tableTag = Regex(
            """<table\b[^>]*\bid="([^"]+)"[^>]*>""",
            RegexOption.IGNORE_CASE,
        ).findAll(decodedHtml.substring(0, summaryRow.range.first)).lastOrNull()
            ?: return null
        return tableTag.groupValues[1]
            .removeSuffix("-content")
            .removeSuffix("-table")
    }

    fun mergeSemesterGradeSummaryTables(
        first: SemesterGradeSummaryTable,
        second: SemesterGradeSummaryTable,
    ): SemesterGradeSummaryTable {
        val merged = linkedMapOf<String, SemesterGradeSummaryCell>()
        (first.items + second.items).forEach { cell ->
            val key = "${cell.year}:${cell.semester?.code.orEmpty()}"
            if (key !in merged) {
                merged[key] = cell
            }
        }
        return SemesterGradeSummaryTable(items = merged.values.toList())
    }

    fun parseSemesterGradeSummaryTable(html: String): SemesterGradeSummaryTable {
        val decodedHtml = html.decodeHtmlEntities()
        val rowRegex = Regex("""<tr\b[^>]*>([\s\S]*?)</tr>""", RegexOption.IGNORE_CASE)
        val cellRegex = Regex(
            """<(td|th)\b[^>]*>([\s\S]*?)</\1>""",
            RegexOption.IGNORE_CASE,
        )
        val result = mutableListOf<SemesterGradeSummaryCell>()

        for (rowMatch in rowRegex.findAll(decodedHtml)) {
            val cells = cellRegex.findAll(rowMatch.groupValues[1])
                .map { it.groupValues[2].stripHtmlTags() }
                .toList()
            if (cells.size != 14) continue

            val year = cells[1].trim()
            val semesterName = cells[2].trim()
            if (year.length != 4 || !year.all { it.isDigit() } || semesterName.isBlank()) {
                continue
            }
            result += SemesterGradeSummaryCell(
                year = year,
                semester = Semester.fromName(semesterName),
                attemptedCredits = cells[3].trim(),
                earnedCredits = cells[4].trim(),
                pfCredits = cells[5].trim(),
                gpa = cells[6].trim(),
                gpaSum = cells[7].trim(),
                arithmeticMean = cells[8].trim(),
                semesterRank = cells[9].trim(),
                totalRank = cells[10].trim(),
                academicWarning = cells[11].trim(),
                consultationStatus = cells[12].trim(),
                failedYearStatus = cells[13].trim(),
            )
        }
        return SemesterGradeSummaryTable(items = result)
    }

    fun parseGradeTable(
        html: String,
        defaultYear: String?,
        defaultSemester: Semester?,
    ): GradeTable {
        val decodedHtml = html.decodeHtmlEntities()
        val parsedYear = webDynpro.parseYear(html)
        val parsedSemester = webDynpro.parseSemester(html)
        val finalYear = parsedYear.ifBlank { defaultYear.orEmpty() }
        val finalSemester = parsedSemester ?: defaultSemester ?: Semester.FIRST
        val rowRegex = Regex("""<tr\b[^>]*>([\s\S]*?)</tr>""", RegexOption.IGNORE_CASE)
        val cellRegex = Regex(
            """<(td|th)\b[^>]*>([\s\S]*?)</\1>""",
            RegexOption.IGNORE_CASE,
        )
        val result = mutableListOf<GradeCell>()

        for (rowMatch in rowRegex.findAll(decodedHtml)) {
            val cells = cellRegex.findAll(rowMatch.groupValues[1])
                .map { it.groupValues[2].stripHtmlTags() }
                .toList()
            if (cells.size != 9) continue

            val subjectCode = cells[8].trim()
            val subjectName = cells[3].trim()
            if (
                subjectCode.length < 7 ||
                !subjectCode.all { it.isDigit() } ||
                subjectName.isBlank()
            ) {
                continue
            }
            result += GradeCell(
                subjectCode = subjectCode,
                subjectName = subjectName,
                classification = cells[4].trim(),
                credits = cells[5].trim(),
                grade = cells[1].trim(),
                gradePoint = cells[2].trim(),
                professor = cells[6].trim(),
            )
        }
        return GradeTable(year = finalYear, semester = finalSemester, items = result)
    }

    private suspend fun fetchTableRows(
        context: WebDynproContext,
        tableId: String,
        firstVisibleRow: Int,
    ): WebDynproContext {
        val scrollEvent =
            "Table_VerticalScroll~E002Id~E004$tableId~E005FirstVisibleItemIndex" +
                "~E004$firstVisibleRow~E005AccessType~E004SCROLLBAR~E003~E002ClientAction" +
                "~E004submit~E005ResponseData~E004delta~E003~E002~E003"
        val focusInfo = webDynpro.escape("""{"sFocussedId":"$tableId"}""")
        val formRequest =
            "Form_Request~E002Id~E004sap.client.SsrClient.form~E005Async~E004false" +
                "~E005FocusInfo~E004$focusInfo~E005Hash~E004~E005DomChanged~E004false" +
                "~E005IsDirty~E004false~E003~E002ResponseData~E004delta~E003~E002~E003"
        return webDynpro.submitEvents(
            context = context,
            eventQueue = listOf(scrollEvent, formRequest).joinToString("~E001"),
        )
    }

    private companion object {
        const val APP_NAME = "ZCMB3W0017"
        const val ECC_BASE_URL = "https://ecc.ssu.ac.kr:8443"
        const val URL = "$ECC_BASE_URL/sap/bc/webdynpro/SAP/$APP_NAME"
        const val DEFAULT_YEAR_CONTROL_ID =
            "ZCMW_PERIOD_RE.ID_0DC742680F42DA9747594D1AE51A0C69:VIW_MAIN.PERYR"
        const val DEFAULT_SEMESTER_CONTROL_ID =
            "ZCMW_PERIOD_RE.ID_0DC742680F42DA9747594D1AE51A0C69:VIW_MAIN.PERID"
        const val DEFAULT_SEARCH_BUTTON_ID = "ZCMB3W0017.ID_0001:VIW_MAIN.BTN_SEARCH"
    }
}
