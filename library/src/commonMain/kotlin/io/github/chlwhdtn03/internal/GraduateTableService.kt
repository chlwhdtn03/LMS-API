package io.github.chlwhdtn03.internal

import io.github.chlwhdtn03.data.Lms.GraduateTable
import io.github.chlwhdtn03.data.Lms.GraduateTableCell
import io.github.chlwhdtn03.decodeHtmlEntities
import io.github.chlwhdtn03.stripHtmlTags

/** 졸업사정표 조회와 파싱을 담당합니다. */
internal class GraduateTableService(
    private val webDynpro: WebDynproService,
) {
    suspend fun getGraduateTable(): GraduateTable {
        return parseGraduateTable(webDynpro.fetchHtml(URL, APP_NAME))
    }

    fun parseGraduateTable(html: String): GraduateTable {
        val decodedHtml = html.decodeHtmlEntities()
        val rowRegex = Regex("""<tr\b[^>]*>([\s\S]*?)</tr>""", RegexOption.IGNORE_CASE)
        val cellRegex = Regex("""<td\b[^>]*>([\s\S]*?)</td>""", RegexOption.IGNORE_CASE)
        val result = mutableListOf<GraduateTableCell>()
        var currentClassification = ""

        for (rowMatch in rowRegex.findAll(decodedHtml)) {
            val cells = cellRegex.findAll(rowMatch.groupValues[1]).toList()
            if (cells.size == 6) {
                val classification = cells[0].groupValues[1].stripHtmlTags()
                val requirement = cells[1].groupValues[1].stripHtmlTags()
                if (
                    classification.isNotBlank() &&
                    classification != "졸업사정일자" &&
                    classification != "이수구분" &&
                    requirement.isNotBlank()
                ) {
                    currentClassification = classification
                    result += GraduateTableCell(
                        classification = classification,
                        requirement = requirement,
                        standardValue = cells[2].groupValues[1].stripHtmlTags(),
                        calculatedValue = cells[3].groupValues[1].stripHtmlTags(),
                        difference = cells[4].groupValues[1].stripHtmlTags(),
                        result = cells[5].groupValues[1].stripHtmlTags(),
                    )
                }
            } else if (cells.size == 5) {
                val requirement = cells[0].groupValues[1].stripHtmlTags()
                if (currentClassification.isNotBlank() && requirement.isNotBlank()) {
                    result += GraduateTableCell(
                        classification = currentClassification,
                        requirement = requirement,
                        standardValue = cells[1].groupValues[1].stripHtmlTags(),
                        calculatedValue = cells[2].groupValues[1].stripHtmlTags(),
                        difference = cells[3].groupValues[1].stripHtmlTags(),
                        result = cells[4].groupValues[1].stripHtmlTags(),
                    )
                }
            }
        }
        return GraduateTable(items = result)
    }

    private companion object {
        const val APP_NAME = "ZCMW8015"
        const val URL = "https://ecc.ssu.ac.kr:8443/sap/bc/webdynpro/SAP/$APP_NAME"
    }
}
