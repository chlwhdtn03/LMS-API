package io.github.chlwhdtn03

import io.github.chlwhdtn03.data.Lms.DayOfWeek
import io.github.chlwhdtn03.data.Lms.Semester
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 네트워크 연결 없이 [LmsApi]의 내부 HTML 파서와 문자열 유틸리티를 검증합니다. */
class LmsApiParserSurfaceTest {
    @Test
    fun parsesEveryPublicHtmlResponse() {
        val timetable = LmsApi.parseTimetable(timetableHtml())
        assertEquals("2026학년도", timetable.year)
        assertEquals("1학기", timetable.semester)
        assertEquals(1, timetable.items.size)
        assertEquals(DayOfWeek.MONDAY, timetable.items.single().dayOfWeek)
        assertEquals("분산시스템", timetable.items.single().subject)

        val graduateTable = LmsApi.parseGraduateTable(
            tableRow("전공", "전공학점", "60", "63", "3", "충족"),
        )
        assertEquals(1, graduateTable.items.size)
        assertEquals("전공학점", graduateTable.items.single().requirement)

        val tuitionTable = LmsApi.parseTuitionTable(
            tableRow(
                "",
                "2026학년도",
                "1학기",
                "4",
                "정규등록",
                "2026.02.20",
                "4,000,000",
                "1,000,000",
                "3,000,000",
                "",
                "",
                "",
                "",
                "",
            ),
        )
        assertEquals(1, tuitionTable.items.size)
        assertEquals("3,000,000", tuitionTable.items.single().paymentAmount)

        val scholarshipTable = LmsApi.parseScholarshipHistoryTable(
            tableRow(
                "",
                "2026",
                "1학기",
                "성적우수장학금",
                "등록금감면",
                "지급완료",
                "",
                "",
                "2026.02.20",
                "1,000,000",
                "1,000,000",
                "0",
                "0",
                "",
                "",
            ),
        )
        assertEquals(1, scholarshipTable.items.size)
        assertEquals("성적우수장학금", scholarshipTable.items.single().scholarshipName)

        val gradeTable = LmsApi.parseGradeTable(
            html = tableRow("", "A+", "4.5", "분산시스템", "전공", "3", "담당교수", "", "21500001"),
            defaultYear = "2026",
            defaultSemester = Semester.FIRST,
        )
        assertEquals(1, gradeTable.items.size)
        assertEquals("21500001", gradeTable.items.single().subjectCode)

        val summaryTable = LmsApi.parseSemesterGradeSummaryTable(summaryTableHtml())
        assertEquals(1, summaryTable.items.size)
        assertEquals(Semester.FIRST, summaryTable.items.single().semester)

        val chapel = LmsApi.parseChapelInformation(
            html = chapelHtml(),
            defaultYear = "2026",
            defaultSemester = Semester.FIRST,
        )
        assertEquals("2026", chapel.year)
        assertEquals(Semester.FIRST, chapel.semester)
        assertEquals(1, chapel.seatStatusTable.items.size)
        assertEquals(1, chapel.attendanceTable.items.size)
        assertEquals(1, chapel.absenceTable.items.size)
    }

    @Test
    fun findsAndMergesSemesterSummaryTables() {
        val html = """
            <div ct="ST" id="SUMMARY_TABLE">
                <table>${summaryRow()}</table>
            </div>
        """.trimIndent()
        assertEquals("SUMMARY_TABLE", LmsApi.findSemesterGradeSummaryTableId(html))

        val first = LmsApi.parseSemesterGradeSummaryTable(summaryTableHtml())
        val second = LmsApi.parseSemesterGradeSummaryTable(summaryTableHtml())
        val merged = LmsApi.mergeSemesterGradeSummaryTables(first, second)
        assertEquals(1, merged.items.size)
    }

    @Test
    fun handlesPublicStringUtilities() {
        val pem = normalizePem(
            "-----BEGIN RSA PRIVATE KEY-----payload-----END RSA PRIVATE KEY-----",
        )
        assertTrue(pem.contains("-----BEGIN RSA PRIVATE KEY-----\n"))
        assertTrue(pem.contains("\n-----END RSA PRIVATE KEY-----"))

        assertEquals("A & B", "&#x41; &amp; B".decodeHtmlEntities())
        assertEquals("첫 줄\n둘째 줄", "<p>첫 줄<br>둘째 줄</p>".stripHtmlTags())
    }

    private fun timetableHtml(): String {
        return """
            <label for="YEAR">학년도</label>
            <input id="YEAR" value="2026학년도">
            <label for="SEMESTER">학기</label>
            <input id="SEMESTER" value="1학기">
            <table ct="ST">
                <thead>
                    <tr>
                        <th role="columnheader" title="시간"></th>
                        <th role="columnheader" title="월요일"></th>
                    </tr>
                </thead>
                <tbody>
                    <tr>
                        <td cc="0">1교시<br>(09:00-09:50)</td>
                        <td cc="1">분산시스템<br>담당교수<br>월 1교시<br>정보관 101호</td>
                    </tr>
                </tbody>
            </table>
        """.trimIndent()
    }

    private fun chapelHtml(): String {
        return """
            ${table(
                headers = List(9) { "좌석헤더$it" },
                values = listOf(
                    "20260001",
                    "월 1교시",
                    "한경직기념관",
                    "1",
                    "A-01",
                    "0",
                    "완료",
                    "P",
                    "",
                ),
            )}
            ${table(
                headers = List(9) { "출결헤더$it" },
                values = listOf(
                    "20260001",
                    "2026.03.10",
                    "채플",
                    "담당자",
                    "교목실",
                    "제목",
                    "출석",
                    "",
                    "",
                ),
            )}
            ${table(
                headers = List(11) { "결석계헤더$it" },
                values = listOf(
                    "2026",
                    "1학기",
                    "공결",
                    "2026.03.10",
                    "2026.03.10",
                    "사유",
                    "reason",
                    "2026.03.11",
                    "2026.03.12",
                    "",
                    "승인",
                ),
            )}
        """.trimIndent()
    }

    private fun summaryTableHtml(): String = "<table>${summaryRow()}</table>"

    private fun summaryRow(): String {
        return tableRow(
            "",
            "2026",
            "1학기",
            "18",
            "18",
            "3",
            "4.0",
            "60",
            "90",
            "1/100",
            "1/100",
            "",
            "",
            "",
        )
    }

    private fun tableRow(vararg values: String): String {
        return values.joinToString(prefix = "<table><tr>", postfix = "</tr></table>") {
            "<td>$it</td>"
        }
    }

    private fun table(headers: List<String>, values: List<String>): String {
        val headerRow = headers.joinToString(prefix = "<tr>", postfix = "</tr>") {
            "<th>$it</th>"
        }
        val valueRow = values.joinToString(prefix = "<tr>", postfix = "</tr>") {
            "<td>$it</td>"
        }
        return "<table>$headerRow$valueRow</table>"
    }
}
