package io.github.chlwhdtn03

import io.github.chlwhdtn03.data.Lms.CourseCatalogCategory
import io.github.chlwhdtn03.data.Lms.CourseCatalogQuery
import io.github.chlwhdtn03.data.Lms.DayOfWeek
import io.github.chlwhdtn03.data.Lms.Semester
import io.github.chlwhdtn03.internal.WebDynproService
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
    fun parsesPreRegistrationTable() {
        val table = LmsApi.parsePreRegistrationTable(preRegistrationHtml())

        assertEquals("2026학년도 2학기 예비수강신청(장바구니): 2026.08.03 ~ 2026.08.10", table.period)
        assertEquals("예약 상태: 예약 완료", table.reservationStatus)
        assertEquals("2", table.totalCourseCount)
        assertEquals("6.0", table.totalCredits)
        assertEquals("20", table.availableCredits)
        assertEquals(2, table.items.size)
        assertEquals("001", table.items[0].priority)
        assertEquals("2150692501", table.items[0].subjectCode)
        assertEquals("컴퓨터비전 (가)", table.items[0].subjectName)
        assertEquals("https://ecc.ssu.ac.kr:8443/plan?id=1", table.items[0].plan)
        assertEquals("화 목 15:00-16:15\n정보과학관 21101", table.items[0].schedule)
        assertEquals("", table.items[0].section)
        assertEquals("12", table.items[1].savedStudentCount)
    }

    @Test
    fun parsesCourseCatalogRowsAndPlanLinks() {
        val query = CourseCatalogQuery(
            year = "2026",
            semester = Semester.FIRST,
            category = CourseCatalogCategory.DEPARTMENT,
        )
        val regular = LmsApi.parseCourseCatalogTable(
            courseCatalogHtml(
                listOf(
                    "<a href=\"https://ecc.ssu.ac.kr/plan?id=1&amp;lang=KO\">조회</a>",
                    "전선-컴퓨터",
                    "복선-컴퓨터",
                    "공학주제",
                    "2150168401",
                    "컴퓨터비전",
                    "가",
                    "EL+",
                    "01",
                    "김교수",
                    "AI소프트웨어학부",
                    "3.0/3.0",
                    "45",
                    "3",
                    "월 09:00-10:15",
                    "학사과정",
                ),
                totalCount = 1,
            ),
            query,
        )

        assertEquals(1, regular.totalCourseCount)
        assertEquals("2150168401", regular.items.single().subjectCode)
        assertEquals("컴퓨터비전", regular.items.single().subjectName)
        assertEquals("https://ecc.ssu.ac.kr/plan?id=1&lang=KO", regular.items.single().plan)
        assertEquals("", regular.items.single().curriculumArea)

        val general = LmsApi.parseCourseCatalogTable(
            courseCatalogHtml(
                listOf(
                    "로그 조회",
                    "교필",
                    "",
                    "",
                    "인문",
                    "2150000101",
                    "현대인과성서",
                    "",
                    "",
                    "01",
                    "이교수",
                    "베어드교양대학",
                    "3.0/3.0",
                    "100",
                    "20",
                    "화 10:30-11:45",
                    "전체",
                ),
                totalCount = 1,
            ),
            query.copy(category = CourseCatalogCategory.REQUIRED_GENERAL),
        )

        assertEquals("인문", general.items.single().curriculumArea)
        assertEquals("2150000101", general.items.single().subjectCode)
        assertEquals("현대인과성서", general.items.single().subjectName)
        assertEquals("", general.items.single().plan)
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

    @Test
    fun parsesWebDynproExternalWindowUrl() {
        val html = """
            application.exec("openExternalWindow",{
                "url":"https\x3a\x2f\x2foffice.ssu.ac.kr\x2foz70\x2fozView.jsp\x3fa\x3d1\x26b\x3d2"
            });
        """.trimIndent()

        assertEquals(
            listOf("https://office.ssu.ac.kr/oz70/ozView.jsp?a=1&b=2"),
            WebDynproService(client) {}.parseExternalWindowUrls(html),
        )
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

    private fun courseCatalogHtml(values: List<String>, totalCount: Int): String {
        val cells = values.mapIndexed { index, value -> "<td cc=\"$index\">$value</td>" }
            .joinToString("")
        return """
            <table id="OTHER_TABLE" ct="ST"><tr>$cells</tr></table>
            <span>총 ${totalCount}건</span>
            <table id="COURSE_RESULT" ct="ST">
                <tr><th>강의계획서 유무</th></tr>
                <tr>$cells</tr>
            </table>
        """.trimIndent()
    }

    private fun preRegistrationHtml(): String {
        val headers = listOf(
            "우선순위", "계획", "이수구분", "다전공구분", "공학인증", "교과영역", "과목번호", "과목명",
            "분반", "교수명", "시간/학점(설계)", "요일/시간(강의실)", "수강 신청일", "비고", "담은 인원", "취소",
        ).joinToString(separator = "") { "<th>$it</th>" }
        val firstRow = preRegistrationRow(
            priority = "001",
            values = listOf(
                "<a href=\"/plan?id=1\">조회</a>", "전선-컴퓨터", "복선-컴퓨터", "", "", "2150692501", "컴퓨터비전 (가)", "",
                "이제영", "3.00 / 3.0", "화 목 15:00-16:15<br>정보과학관 21101", "2026.08.04", "본인 신청", "17",
            ),
        )
        val secondRow = preRegistrationRow(
            priority = "002",
            values = listOf(
                "", "전필-컴퓨터", "복필-컴퓨터", "", "", "2150516203", "운영체제 (다)", "",
                "홍지만", "3.00 / 3.0", "월 수 15:00-16:15", "2026.08.04", "본인 신청", "12",
            ),
        )
        return """
            <span>2026학년도 2학기 예비수강신청(장바구니): 2026.08.03 ~ 2026.08.10</span>
            <div title="예약 상태: 예약 완료">예비수강 신청 내역</div>
            <table ct="ST"><tr>$headers</tr>$firstRow$secondRow</table>
            <table>
                <tr><td><label for="TOTAL">총 신청 과목수</label><span id="TOTAL" ct="TV">2</span></td>
                <td><span ct="TV">6.0</span></td>
                <td><label for="AVAILABLE">수강가능학점</label><span id="AVAILABLE" ct="TV">20</span></td></tr>
            </table>
        """.trimIndent()
    }

    private fun preRegistrationRow(priority: String, values: List<String>): String {
        val priorityCell = "<td><table><tr><td><input value=\"$priority\"></td></tr></table></td>"
        return values.joinToString(
            prefix = "<tr rr=\"1\">$priorityCell",
            postfix = "<td><button>취소</button></td></tr>",
        ) { "<td>$it</td>" }
    }

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
