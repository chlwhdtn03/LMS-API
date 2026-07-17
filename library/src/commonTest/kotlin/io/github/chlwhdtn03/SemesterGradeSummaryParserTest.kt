package io.github.chlwhdtn03

import kotlin.test.Test
import kotlin.test.assertEquals

class SemesterGradeSummaryParserTest {
    @Test
    fun parsesOldestSemesterWhenMoreThanEightSemesterRowsExist() {
        val semesters = listOf(
            "2026" to "1학기",
            "2025" to "2학기",
            "2025" to "1학기",
            "2024" to "2학기",
            "2024" to "1학기",
            "2023" to "2학기",
            "2023" to "1학기",
            "2022" to "2학기",
            "2022" to "1학기",
        )
        val html = semesters.joinToString(prefix = "<table>", postfix = "</table>") { (year, semester) ->
            """
                <tr>
                    <td></td><td>$year</td><td>$semester</td>
                    <td>18</td><td>18</td><td>3</td><td>4.0</td><td>60</td>
                    <td>90</td><td>1/100</td><td>1/100</td><td></td><td></td><td></td>
                </tr>
            """.trimIndent()
        }

        val result = LmsApi.parseSemesterGradeSummaryTable(html)

        assertEquals(9, result.items.size)
        assertEquals("2022", result.items.last().year)
        assertEquals("1학기", result.items.last().semester?.nameKor)
    }

    @Test
    fun findsSummaryTableControlId() {
        val html = """
            <div id="OTHER_TABLE" ct="ST"><table><tr><td>not grades</td></tr></table></div>
            <div ct="ST" id="SUMMARY_TABLE">
                <table>
                    ${semesterRow("2022", "1학기")}
                </table>
            </div>
        """.trimIndent()

        assertEquals("SUMMARY_TABLE", LmsApi.findSemesterGradeSummaryTableId(html))
    }

    @Test
    fun mergesOverlappingScrollWindowsWithoutDuplicates() {
        val first = LmsApi.parseSemesterGradeSummaryTable(
            "<table>${semesterRow("2023", "1학기")}${semesterRow("2022", "2학기")}</table>",
        )
        val second = LmsApi.parseSemesterGradeSummaryTable(
            "<table>${semesterRow("2022", "2학기")}${semesterRow("2022", "1학기")}</table>",
        )

        val result = LmsApi.mergeSemesterGradeSummaryTables(first, second)

        assertEquals(listOf("2023-1학기", "2022-2학기", "2022-1학기"), result.items.map { "${it.year}-${it.semester?.nameKor}" })
    }

    private fun semesterRow(year: String, semester: String) = """
        <tr>
            <td></td><td>$year</td><td>$semester</td>
            <td>18</td><td>18</td><td>3</td><td>4.0</td><td>60</td>
            <td>90</td><td>1/100</td><td>1/100</td><td></td><td></td><td></td>
        </tr>
    """.trimIndent()
}
