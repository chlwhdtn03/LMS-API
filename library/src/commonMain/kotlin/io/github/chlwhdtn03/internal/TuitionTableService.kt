package io.github.chlwhdtn03.internal

import io.github.chlwhdtn03.data.Lms.TuitionCell
import io.github.chlwhdtn03.data.Lms.TuitionTable
import io.github.chlwhdtn03.decodeHtmlEntities
import io.github.chlwhdtn03.stripHtmlTags

/** 등록금 납부 이력 조회와 파싱을 담당합니다. */
internal class TuitionTableService(
    private val webDynpro: WebDynproService,
) {
    suspend fun getTuitionTable(): TuitionTable {
        return parseTuitionTable(webDynpro.fetchHtml(URL, APP_NAME))
    }

    fun parseTuitionTable(html: String): TuitionTable {
        val decodedHtml = html.decodeHtmlEntities()
        val rowRegex = Regex("""<tr\b[^>]*>([\s\S]*?)</tr>""", RegexOption.IGNORE_CASE)
        val cellRegex = Regex("""<td\b[^>]*>([\s\S]*?)</td>""", RegexOption.IGNORE_CASE)
        val result = mutableListOf<TuitionCell>()

        for (rowMatch in rowRegex.findAll(decodedHtml)) {
            val cells = cellRegex.findAll(rowMatch.groupValues[1]).toList()
            if (cells.size != 14) continue

            val year = cells[1].groupValues[1].stripHtmlTags()
            val semester = cells[2].groupValues[1].stripHtmlTags()
            if (!year.contains("학년도") || semester.isBlank()) continue

            result += TuitionCell(
                year = year,
                semester = semester,
                grade = cells[3].groupValues[1].stripHtmlTags(),
                registrationType = cells[4].groupValues[1].stripHtmlTags(),
                registrationDate = cells[5].groupValues[1].stripHtmlTags(),
                amount = cells[6].groupValues[1].stripHtmlTags(),
                reduction = cells[7].groupValues[1].stripHtmlTags(),
                paymentAmount = cells[8].groupValues[1].stripHtmlTags(),
            )
        }
        return TuitionTable(items = result)
    }

    private companion object {
        const val APP_NAME = "ZCMW6520n"
        const val URL = "https://ecc.ssu.ac.kr:8443/sap/bc/webdynpro/SAP/$APP_NAME"
    }
}
