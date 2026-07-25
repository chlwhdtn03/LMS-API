package io.github.chlwhdtn03.internal

import io.github.chlwhdtn03.data.Lms.ScholarshipHistoryCell
import io.github.chlwhdtn03.data.Lms.ScholarshipHistoryTable
import io.github.chlwhdtn03.decodeHtmlEntities
import io.github.chlwhdtn03.stripHtmlTags

/** 장학 수혜 이력 조회와 파싱을 담당합니다. */
internal class ScholarshipHistoryService(
    private val webDynpro: WebDynproService,
) {
    suspend fun getScholarshipHistoryTable(): ScholarshipHistoryTable {
        return parseScholarshipHistoryTable(webDynpro.openSession(URL, APP_NAME).html)
    }

    fun parseScholarshipHistoryTable(html: String): ScholarshipHistoryTable {
        val decodedHtml = html.decodeHtmlEntities()
        val rowRegex = Regex("""<tr\b[^>]*>([\s\S]*?)</tr>""", RegexOption.IGNORE_CASE)
        val cellRegex = Regex("""<td\b[^>]*>([\s\S]*?)</td>""", RegexOption.IGNORE_CASE)
        val result = mutableListOf<ScholarshipHistoryCell>()

        for (rowMatch in rowRegex.findAll(decodedHtml)) {
            val cells = cellRegex.findAll(rowMatch.groupValues[1]).toList()
            if (cells.size != 15) continue

            val year = cells[1].groupValues[1].stripHtmlTags()
            if (year.isBlank() || year.firstOrNull()?.isDigit() != true) continue

            result += ScholarshipHistoryCell(
                year = year,
                semester = cells[2].groupValues[1].stripHtmlTags(),
                scholarshipName = cells[3].groupValues[1].stripHtmlTags(),
                paymentMethod = cells[4].groupValues[1].stripHtmlTags(),
                processStatus = cells[5].groupValues[1].stripHtmlTags(),
                note = cells[6].groupValues[1].stripHtmlTags(),
                dropReason = cells[7].groupValues[1].stripHtmlTags(),
                processDate = cells[8].groupValues[1].stripHtmlTags(),
                selectedAmount = cells[9].groupValues[1].stripHtmlTags(),
                actualAmount = cells[10].groupValues[1].stripHtmlTags(),
                redeemedAmount = cells[11].groupValues[1].stripHtmlTags(),
                replacedAmount = cells[12].groupValues[1].stripHtmlTags(),
                replacedScholarshipName = cells[13].groupValues[1].stripHtmlTags(),
                workDepartment = cells[14].groupValues[1].stripHtmlTags(),
            )
        }
        return ScholarshipHistoryTable(items = result)
    }

    private companion object {
        const val APP_NAME = "ZCMW7530N"
        const val URL = "https://ecc.ssu.ac.kr:8443/sap/bc/webdynpro/SAP/$APP_NAME"
    }
}
