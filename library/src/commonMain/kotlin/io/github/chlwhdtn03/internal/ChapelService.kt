package io.github.chlwhdtn03.internal

import io.github.chlwhdtn03.ChapelCache
import io.github.chlwhdtn03.data.Lms.*
import io.github.chlwhdtn03.decodeHtmlEntities
import io.github.chlwhdtn03.stripHtmlTags
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*

/** 채플 좌석·출결·결석계 조회와 파싱을 담당합니다. */
internal class ChapelService(
    private val client: HttpClient,
    private val webDynpro: WebDynproService,
    private val cache: ChapelCache,
) {
    suspend fun getChapelInformation(
        year: String?,
        semester: Semester?,
    ): ChapelInformation {
        if (year == null && semester == null) {
            val cachedYear = cache.latestYear
            val cachedSemester = cache.latestSemester
            if (cachedYear != null && cachedSemester != null) {
                return getChapelInformation(cachedYear, cachedSemester)
            }
        }
        if (year != null && semester != null) {
            val information = cache.information
            if (
                information != null &&
                year == information.year &&
                semester == information.semester
            ) {
                return information
            }
        }

        val currentHtml = webDynpro.fetchHtml(URL, APP_NAME)
        if (cache.latestYear == null || cache.latestSemester == null) {
            val defaultYear = webDynpro.parseYear(currentHtml)
            val defaultSemester = webDynpro.parseSemester(currentHtml)
            if (defaultYear.isNotBlank() && defaultSemester != null) {
                cache.latestYear = defaultYear
                cache.latestSemester = defaultSemester
            }
        }

        val (secureId, formAction) = webDynpro.requireSession(
            APP_NAME,
            "채플 페이지 세션을 초기화하지 못했습니다.",
        )
        val yearControlId = Regex(
            """<label\b[^>]*\bfor="([^"]+)"[^>]*>(?:(?!</?label\b).)*?학년도""",
            RegexOption.IGNORE_CASE,
        ).find(currentHtml)?.groupValues?.get(1)
            ?: Regex("""id="([^"]+:VIW_MAIN\.PERYR)"""")
                .find(currentHtml)?.groupValues?.get(1)
            ?: ""
        val semesterControlId = Regex(
            """<label\b[^>]*\bfor="([^"]+)"[^>]*>(?:(?!</label>).)*?학기""",
            RegexOption.IGNORE_CASE,
        ).find(currentHtml)?.groupValues?.get(1)
            ?: Regex("""id="([^"]+:VIW_MAIN\.PERID)"""")
                .find(currentHtml)?.groupValues?.get(1)
            ?: ""
        val searchButtonId = Regex(
            """<(?:div|button)\b[^>]*\bid="([^"]+)"[^>]*ct="B"[^>]*>(?:(?!<(?:div|button)\b).)*?조회""",
            RegexOption.IGNORE_CASE,
        ).find(currentHtml)?.groupValues?.get(1)
            ?: Regex("""id="([^"]+\.BTN_SEARCH)"""")
                .find(currentHtml)?.groupValues?.get(1)
            ?: ""

        if (yearControlId.isNotBlank()) cache.yearControlId = yearControlId
        if (semesterControlId.isNotBlank()) cache.semesterControlId = semesterControlId
        if (searchButtonId.isNotBlank()) cache.searchButtonId = searchButtonId

        val activeYearControlId = yearControlId.ifBlank { cache.yearControlId.orEmpty() }
        val activeSemesterControlId =
            semesterControlId.ifBlank { cache.semesterControlId.orEmpty() }
        val activeSearchButtonId =
            searchButtonId.ifBlank { cache.searchButtonId ?: DEFAULT_SEARCH_BUTTON_ID }

        if (semester == Semester.SUMMER || semester == Semester.WINTER) {
            throw IllegalArgumentException("채플은 계절학기 조회를 지원하지 않습니다.")
        }

        val buttonEvent =
            "Button_Press~E002Id~E004$activeSearchButtonId~E003~E002ClientAction" +
                "~E004submit~E003~E002~E003"
        val focusInfo = webDynpro.escape("""{"sFocussedId":"$activeSearchButtonId"}""")
        val formRequest =
            "Form_Request~E002Id~E004sap.client.SsrClient.form~E005Async~E004false" +
                "~E005FocusInfo~E004$focusInfo~E005Hash~E004~E005DomChanged~E004false" +
                "~E005IsDirty~E004false~E003~E002ResponseData~E004delta~E003~E002~E003"
        val events = if (
            year != null &&
            semester != null &&
            activeYearControlId.isNotBlank() &&
            activeSemesterControlId.isNotBlank()
        ) {
            val yearEvent =
                "ComboBox_Select~E002Id~E004$activeYearControlId~E005Key~E004$year" +
                    "~E005ByEnter~E004false~E003~E002ClientAction~E004submit" +
                    "~E005ResponseData~E004delta~E003~E002~E003"
            val semesterEvent =
                "ComboBox_Select~E002Id~E004$activeSemesterControlId~E005Key" +
                    "~E004${semester.code}~E005ByEnter~E004false~E003~E002ClientAction" +
                    "~E004submit~E005ResponseData~E004delta~E003~E002~E003"
            listOf(yearEvent, semesterEvent, buttonEvent, formRequest)
        } else {
            listOf(buttonEvent, formRequest)
        }

        val resultHtml = submitEvents(
            secureId = secureId,
            formAction = formAction,
            eventQueue = events.joinToString("~E001"),
        )
        updateSession(resultHtml, secureId, formAction)

        val information = parseChapelInformation(resultHtml, year, semester)
        if (year == null && semester == null) {
            cache.latestYear = information.year
            cache.latestSemester = information.semester
        }
        cache.information = information
        return information
    }

    fun parseChapelInformation(
        html: String,
        defaultYear: String?,
        defaultSemester: Semester?,
    ): ChapelInformation {
        val decodedHtml = html.decodeHtmlEntities()
        val parsedYear = webDynpro.parseYear(html)
        val parsedSemester = webDynpro.parseSemester(html)
        val year = parsedYear.ifBlank { defaultYear.orEmpty() }
        val semester = parsedSemester ?: defaultSemester ?: Semester.FIRST
        var seatStatusTable = ChapelSeatStatusTable(emptyList())
        var attendanceTable = ChapelAttendanceTable(emptyList())
        var absenceTable = ChapelAbsenceTable(emptyList())

        for (tableHtml in extractAllTables(decodedHtml)) {
            val rawTable = parseRawTable(removeInnerTables(tableHtml)) ?: continue
            if (rawTable.rows.isEmpty()) continue

            val firstRow = rawTable.rows[0]
            when {
                isSeatStatusTable(rawTable.headers.size, firstRow) -> {
                    seatStatusTable = ChapelSeatStatusTable(
                        items = rawTable.rows.map { row ->
                            val values = row.toHeaderMap(SEAT_STATUS_HEADERS)
                            ChapelSeatStatusCell(
                                classGroup = values["분반"].orEmpty(),
                                timetable = values["시간표"].orEmpty(),
                                classroom = values["강의실"].orEmpty(),
                                seatNo = values["좌석번호"].orEmpty(),
                                absenceCount = values["결석일수"].orEmpty(),
                                gradeResult = values["성적"].orEmpty(),
                                rawValues = values,
                            )
                        },
                    )
                }

                isAttendanceTable(rawTable.headers.size, firstRow) -> {
                    attendanceTable = ChapelAttendanceTable(
                        items = rawTable.rows.map { row ->
                            val values = row.toHeaderMap(ATTENDANCE_HEADERS)
                            ChapelAttendanceCell(
                                classGroup = values["분반"].orEmpty(),
                                date = values["수업일자"].orEmpty(),
                                lectureType = values["강의구분"].orEmpty(),
                                status = values["출결상태"].orEmpty(),
                                rawValues = values,
                            )
                        },
                    )
                }

                isAbsenceTable(rawTable.headers.size, firstRow) -> {
                    absenceTable = ChapelAbsenceTable(
                        items = rawTable.rows.map { row ->
                            val values = row.toHeaderMap(ABSENCE_HEADERS)
                            ChapelAbsenceCell(
                                year = values["학년도"].orEmpty(),
                                semester = values["학기"].orEmpty(),
                                detail = values["결석구분상세"].orEmpty(),
                                rawValues = values,
                            )
                        },
                    )
                }
            }
        }

        return ChapelInformation(
            year = year,
            semester = semester,
            seatStatusTable = seatStatusTable,
            attendanceTable = attendanceTable,
            absenceTable = absenceTable,
        )
    }

    private fun isSeatStatusTable(columnCount: Int, firstRow: List<String>): Boolean {
        return columnCount == 9 &&
            firstRow.size >= 2 &&
            firstRow[0].length >= 8 &&
            firstRow[0].all { it.isDigit() } &&
            !firstRow[1].contains(".")
    }

    private fun isAttendanceTable(columnCount: Int, firstRow: List<String>): Boolean {
        return columnCount == 9 &&
            firstRow.size >= 2 &&
            firstRow[1].matches(Regex("""\d{4}\.\d{2}\.\d{2}"""))
    }

    private fun isAbsenceTable(columnCount: Int, firstRow: List<String>): Boolean {
        return columnCount == 11 &&
            firstRow.isNotEmpty() &&
            firstRow[0].matches(Regex("""\d{4}"""))
    }

    private fun List<String>.toHeaderMap(headers: List<String>): Map<String, String> {
        return buildMap {
            for (index in headers.indices) {
                if (index < this@toHeaderMap.size) {
                    put(headers[index], this@toHeaderMap[index])
                }
            }
        }
    }

    private fun extractAllTables(html: String): List<String> {
        val tables = mutableListOf<String>()
        var position = 0
        while (true) {
            val start = html.indexOf("<table", position, ignoreCase = true)
            if (start == -1) break
            val end = findNestedTableEnd(html, start)
            if (end != -1) {
                tables += html.substring(start, end)
            }
            position = start + 6
        }
        return tables
    }

    private fun removeInnerTables(tableHtml: String): String {
        var html = tableHtml
        while (true) {
            val start = html.indexOf("<table", 1, ignoreCase = true)
            if (start == -1) break
            val end = findNestedTableEnd(html, start)
            if (end == -1) break
            html = html.substring(0, start) + html.substring(end)
        }
        return html
    }

    private fun findNestedTableEnd(html: String, start: Int): Int {
        var depth = 1
        var position = start + 6
        while (depth > 0 && position < html.length) {
            val nextStart = html.indexOf("<table", position, ignoreCase = true)
            val nextEnd = html.indexOf("</table>", position, ignoreCase = true)
            if (nextEnd == -1) return -1

            if (nextStart != -1 && nextStart < nextEnd) {
                depth++
                position = nextStart + 6
            } else {
                depth--
                position = nextEnd + 8
                if (depth == 0) return position
            }
        }
        return -1
    }

    private fun parseRawTable(tableHtml: String): RawTable? {
        val decodedHtml = tableHtml.decodeHtmlEntities()
        val rowRegex = Regex("""<tr\b[^>]*>([\s\S]*?)</tr>""", RegexOption.IGNORE_CASE)
        val cellRegex = Regex(
            """<(td|th)\b[^>]*>([\s\S]*?)</\1>""",
            RegexOption.IGNORE_CASE,
        )
        val allRows = rowRegex.findAll(decodedHtml).map { rowMatch ->
            cellRegex.findAll(rowMatch.groupValues[1])
                .map { it.groupValues[2].stripHtmlTags().trim() }
                .toList()
        }.filter { it.isNotEmpty() }.toList()
        if (allRows.isEmpty()) return null
        return RawTable(headers = allRows[0], rows = allRows.drop(1))
    }

    private suspend fun submitEvents(
        secureId: String,
        formAction: String,
        eventQueue: String,
    ): String {
        val actionUrl = if (formAction.startsWith("http")) {
            formAction
        } else {
            "$ECC_BASE_URL$formAction"
        }
        val response = client.submitForm(
            url = actionUrl,
            formParameters = parameters {
                append("sap-charset", "utf-8")
                append("sap-wd-secure-id", secureId)
                append("fesrAppName", APP_NAME)
                append("fesrUseBeacon", "true")
                append("SAPEVENTQUEUE", eventQueue)
            },
        ) {
            headers {
                append(HttpHeaders.UserAgent, USER_AGENT)
                append(HttpHeaders.Accept, "*/*")
                append("X-Requested-With", "XMLHttpRequest")
                append(HttpHeaders.ContentType, "application/x-www-form-urlencoded; charset=UTF-8")
            }
        }
        return response.bodyAsText().decodeHtmlEntities().decodeHtmlEntities()
    }

    private fun updateSession(
        html: String,
        fallbackSecureId: String,
        fallbackFormAction: String,
    ) {
        val secureId = Regex(
            """name="sap-wd-secure-id"\s+value="([^"]+)"""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.get(1) ?: fallbackSecureId
        val formAction = Regex(
            """<form\s+[^>]*id="sap\.client\.SsrClient\.form"[^>]*action="([^"]+)"""",
            RegexOption.IGNORE_CASE,
        ).find(html)
            ?.groupValues
            ?.get(1)
            ?.decodeHtmlEntities()
            ?.decodeHtmlEntities()
            ?: fallbackFormAction
        webDynpro.updateSession(APP_NAME, secureId, formAction)
    }

    private data class RawTable(
        val headers: List<String>,
        val rows: List<List<String>>,
    )

    private companion object {
        const val APP_NAME = "ZCMW3681"
        const val ECC_BASE_URL = "https://ecc.ssu.ac.kr:8443"
        const val URL = "$ECC_BASE_URL/sap/bc/webdynpro/SAP/$APP_NAME"
        const val DEFAULT_SEARCH_BUTTON_ID = "WDB2"
        const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"
        val SEAT_STATUS_HEADERS =
            listOf("분반", "시간표", "강의실", "층수", "좌석번호", "결석일수", "설문조사", "성적", "비고")
        val ATTENDANCE_HEADERS =
            listOf("분반", "수업일자", "강의구분", "강사", "소속", "제목", "출결상태", "평가", "비고")
        val ABSENCE_HEADERS = listOf(
            "학년도",
            "학기",
            "결석구분상세",
            "결석시작일자",
            "결석종료일자",
            "결석사유(국문)",
            "결석사유(영문)",
            "신청일자",
            "승인일자",
            "거부사유",
            "상태",
        )
    }
}
