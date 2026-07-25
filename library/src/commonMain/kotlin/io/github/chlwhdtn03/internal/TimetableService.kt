package io.github.chlwhdtn03.internal

import io.github.chlwhdtn03.data.Lms.DayOfWeek
import io.github.chlwhdtn03.data.Lms.Semester
import io.github.chlwhdtn03.data.Lms.Timetable
import io.github.chlwhdtn03.data.Lms.TimetableCell
import io.github.chlwhdtn03.decodeHtmlEntities
import io.github.chlwhdtn03.stripHtmlTags

/** 시간표 조회 요청 생성과 HTML 파싱을 담당합니다. */
internal class TimetableService(
    private val webDynpro: WebDynproService,
) {
    suspend fun getTimetable(year: String?, semester: Semester?): Timetable {
        var context = webDynpro.openSession(TIMETABLE_URL, APP_NAME)
        val currentHtml = context.html

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
        return parseTimetable(context.html)
    }

    fun parseTimetable(html: String): Timetable {
        val labelRegex = Regex(
            """<label\b[^>]*for="([^"]+)"[^>]*>(?:(?!</label>)[\s\S])*?학년도""",
            RegexOption.IGNORE_CASE,
        )
        val inputValueRegex = { id: String ->
            Regex("""id="$id"[^>]*value="([^"]+)"""", RegexOption.IGNORE_CASE)
        }
        var year = ""
        labelRegex.find(html)?.groupValues?.get(1)?.let { id ->
            year = inputValueRegex(id).find(html)?.groupValues?.get(1)?.decodeHtmlEntities().orEmpty()
        }

        val semesterLabelRegex = Regex(
            """<label\b[^>]*for="([^"]+)"[^>]*>(?:(?!</label>)[\s\S])*?학기""",
            RegexOption.IGNORE_CASE,
        )
        var semester = ""
        semesterLabelRegex.find(html)?.groupValues?.get(1)?.let { id ->
            semester =
                inputValueRegex(id).find(html)?.groupValues?.get(1)?.decodeHtmlEntities().orEmpty()
        }
        if (year.isBlank()) {
            year = Regex("""value="([^"]*학년도)"""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)?.decodeHtmlEntities().orEmpty()
        }
        if (semester.isBlank()) {
            semester = Regex("""value="([^"]*학기)"""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)?.decodeHtmlEntities().orEmpty()
        }

        val targetHeaderIndex = html.indexOf("title=\"월요일\"", ignoreCase = true).let {
            if (it == -1) html.indexOf("title=\"월\"", ignoreCase = true) else it
        }
        var tableContent = html
        if (targetHeaderIndex != -1) {
            val controlIndex = html.lastIndexOf("ct=\"ST\"", targetHeaderIndex, ignoreCase = true)
            val searchStart = if (controlIndex != -1) controlIndex else targetHeaderIndex
            val tableStart = html.lastIndexOf("<table", searchStart, ignoreCase = true)
            if (tableStart != -1) {
                tableContent = html.substring(tableStart, findTableEnd(html, tableStart))
            }
        }

        val headerPattern = Regex(
            """<th\b[^>]*role="columnheader"[^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        val titlePattern = Regex("""title="([^"]+)"""", RegexOption.IGNORE_CASE)
        val dataPattern = Regex("""7:'([^']+)'""")
        val textPattern = Regex(
            """<span[^>]*ct="CP"[^>]*>([^<]+)</span>""",
            RegexOption.IGNORE_CASE,
        )
        val headers = headerPattern.findAll(tableContent).map { match ->
            val headerTag = match.value
            titlePattern.find(headerTag)?.groupValues?.get(1)?.let { return@map it }

            val start = tableContent.indexOf(headerTag)
            val nextHeader = tableContent.indexOf("<th", start + 1, ignoreCase = true)
            val end = if (nextHeader != -1) nextHeader else tableContent.length
            val fullContent = tableContent.substring(start, end)
            dataPattern.find(fullContent)?.groupValues?.get(1)
                ?: textPattern.find(fullContent)?.groupValues?.get(1)
                ?: ""
        }.toList()

        val bodyStart = tableContent.indexOf("<tbody", ignoreCase = true)
        val body = if (bodyStart != -1) tableContent.substring(bodyStart) else tableContent
        val rowRegex = Regex("""<tr\b[^>]*>([\s\S]*?)</tr>""", RegexOption.IGNORE_CASE)
        val cellRegex = Regex(
            """<td\b[^>]*cc="(\d+)"[^>]*>([\s\S]*?)</td>""",
            RegexOption.IGNORE_CASE,
        )
        val cells = mutableListOf<TimetableCell>()

        for (rowMatch in rowRegex.findAll(body)) {
            val rowCells = cellRegex.findAll(rowMatch.groupValues[1]).toList()
            if (rowCells.isEmpty()) continue

            val periodCell = rowCells.firstOrNull { it.groupValues[1] == "0" } ?: continue
            val periodText = periodCell.groupValues[2].stripHtmlTags()
            if (periodText.isBlank()) continue
            val periodParts = periodText.split("\n")
            val periodName = periodParts.getOrNull(0).orEmpty()
            val periodTime = periodParts.getOrNull(1).orEmpty()

            for (cellMatch in rowCells) {
                val columnIndex = cellMatch.groupValues[1].toIntOrNull() ?: continue
                if (columnIndex == 0) continue

                val cellHtml = cellMatch.groupValues[2]
                if (
                    cellHtml.contains("lsSTEmptyRow") ||
                    cellHtml.contains("비어있음") ||
                    cellHtml.contains("비어 임")
                ) {
                    continue
                }
                val cellText = cellHtml.stripHtmlTags()
                if (cellText.isBlank()) continue
                val lines = cellText.split("\n")
                val subject = lines.getOrNull(0).orEmpty()
                if (subject.isBlank()) continue

                cells += TimetableCell(
                    dayOfWeek = DayOfWeek.fromKoreanName(dayName(headers, columnIndex))
                        ?: DayOfWeek.MONDAY,
                    period = periodName,
                    periodTime = periodTime,
                    subject = subject,
                    professor = lines.getOrNull(1).orEmpty(),
                    time = lines.getOrNull(2).orEmpty(),
                    classroom = lines.getOrNull(3).orEmpty(),
                )
            }
        }
        return Timetable(year = year, semester = semester, items = cells)
    }

    private fun findTableEnd(html: String, tableStart: Int): Int {
        var openCount = 0
        for (match in Regex("""</?table\b""", RegexOption.IGNORE_CASE).findAll(html, tableStart)) {
            if (match.value.startsWith("</", ignoreCase = true)) {
                openCount--
                if (openCount == 0) {
                    return match.range.last + 1
                }
            } else {
                openCount++
            }
        }
        return html.length
    }

    private fun dayName(headers: List<String>, columnIndex: Int): String {
        if (columnIndex in headers.indices && headers[columnIndex].isNotBlank()) {
            return headers[columnIndex]
        }
        val defaults = listOf(
            "시간",
            "월요일",
            "화요일",
            "수요일",
            "목요일",
            "금요일",
            "토요일",
            "일요일",
        )
        return defaults.getOrNull(columnIndex) ?: "알 수 없음"
    }

    private companion object {
        const val APP_NAME = "ZCMW2102"
        const val ECC_BASE_URL = "https://ecc.ssu.ac.kr:8443"
        const val TIMETABLE_URL = "$ECC_BASE_URL/sap/bc/webdynpro/SAP/$APP_NAME"
        const val DEFAULT_YEAR_CONTROL_ID =
            "ZCMW_PERIOD_RE.ID_0DC742680F42DA9747594D1AE51A0C69:VIW_MAIN.PERYR"
        const val DEFAULT_SEMESTER_CONTROL_ID =
            "ZCMW_PERIOD_RE.ID_0DC742680F42DA9747594D1AE51A0C69:VIW_MAIN.PERID"
        const val DEFAULT_SEARCH_BUTTON_ID = "ZCMW2102.ID_0001:VIW_MAIN.BTN_SEARCH"
    }
}
