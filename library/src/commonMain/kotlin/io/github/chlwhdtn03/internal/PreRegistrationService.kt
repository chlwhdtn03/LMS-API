package io.github.chlwhdtn03.internal

import io.github.chlwhdtn03.data.Lms.PreRegistrationCourse
import io.github.chlwhdtn03.data.Lms.PreRegistrationTable
import io.github.chlwhdtn03.decodeHtmlEntities
import io.github.chlwhdtn03.stripHtmlTags

/** 예비수강신청 장바구니 조회와 응답 테이블 파싱을 담당합니다. */
internal class PreRegistrationService(
    private val webDynpro: WebDynproService,
) {
    suspend fun getPreRegistrationTable(): PreRegistrationTable {
        return parsePreRegistrationTable(webDynpro.openSession(URL, APP_NAME).html)
    }

    fun parsePreRegistrationTable(html: String): PreRegistrationTable {
        val decodedHtml = html.decodeHtmlEntities()
        val tableHtml = findPreRegistrationTable(decodedHtml)
        if (tableHtml == null) {
            return PreRegistrationTable("", "", "", "", "", emptyList())
        }

        val items = rowStarts(tableHtml)
            .mapNotNull { rowStart -> parseCourse(tableHtml, rowStart) }
        val summaryValues = summaryValues(decodedHtml)
        return PreRegistrationTable(
            period = findPeriod(decodedHtml),
            reservationStatus = findReservationStatus(decodedHtml),
            totalCourseCount = summaryValues.getOrElse(0) { "" },
            totalCredits = summaryValues.getOrElse(1) { "" },
            availableCredits = summaryValues.getOrElse(2) { "" },
            items = items,
        )
    }

    private fun findPreRegistrationTable(html: String): String? {
        val panelIndex = html.indexOf("예비수강 신청 내역")
        if (panelIndex == -1) return null
        val tableRegex = Regex("""<table\b[^>]*\bct="ST"[^>]*>""", RegexOption.IGNORE_CASE)
        for (match in tableRegex.findAll(html, panelIndex)) {
            val tableEnd = findElementEnd(html, match.range.first, "table") ?: continue
            val table = html.substring(match.range.first, tableEnd)
            val headers = directElements(table, "th")
                .map { it.stripHtmlTags() }
                .filter { it.isNotBlank() }
            if (
                headers.any { it.contains("우선순위") } &&
                headers.any { it.contains("과목번호") } &&
                headers.any { it.contains("담은 인원") }
            ) {
                return table
            }
        }
        return null
    }

    private fun rowStarts(tableHtml: String): List<Int> {
        return Regex("""<tr\b[^>]*\brr="[1-9]\d*"[^>]*>""", RegexOption.IGNORE_CASE)
            .findAll(tableHtml)
            .map { it.range.first }
            .toList()
    }

    private fun parseCourse(tableHtml: String, rowStart: Int): PreRegistrationCourse? {
        val rowEnd = findElementEnd(tableHtml, rowStart, "tr") ?: return null
        val cells = directElements(tableHtml.substring(rowStart, rowEnd), "td")
        if (cells.size != COURSE_COLUMN_COUNT) return null
        val values = cells.mapIndexed { index, cell ->
            if (index == 0) {
                Regex("""<input\b[^>]*\bvalue="([^"]*)""", RegexOption.IGNORE_CASE)
                    .find(cell)
                    ?.groupValues
                    ?.get(1)
                    ?.decodeHtmlEntities()
                    .orEmpty()
            } else {
                cell.stripHtmlTags()
            }
        }
        return PreRegistrationCourse(
            priority = values[0],
            plan = values[1],
            classification = values[2],
            multiMajorClassification = values[3],
            engineeringCertification = values[4],
            curriculumArea = values[5],
            subjectCode = values[6],
            subjectName = values[7],
            section = values[8],
            professor = values[9],
            hoursCredits = values[10],
            schedule = values[11],
            applicationDate = values[12],
            note = values[13],
            savedStudentCount = values[14],
        )
    }

    private fun findPeriod(html: String): String {
        return Regex(""">([^<]*예비수강신청[^<]*)<""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.stripHtmlTags()
            .orEmpty()
    }

    private fun findReservationStatus(html: String): String {
        return Regex("""title="(예약\s*상태\s*:[^"]+)""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.trim()
            .orEmpty()
    }

    private fun summaryValues(html: String): List<String> {
        val summaryIndex = html.indexOf("총 신청 과목수")
        if (summaryIndex == -1) return emptyList()
        val summaryTableStart = html.lastIndexOf("<table", summaryIndex, ignoreCase = true)
        if (summaryTableStart == -1) return emptyList()
        val summaryTableEnd = findElementEnd(html, summaryTableStart, "table") ?: return emptyList()
        return Regex("""<span\b[^>]*\bct="TV"[^>]*>([\s\S]*?)</span>""", RegexOption.IGNORE_CASE)
            .findAll(html.substring(summaryTableStart, summaryTableEnd))
            .map { it.groupValues[1].stripHtmlTags() }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun directElements(html: String, tag: String): List<String> {
        val result = mutableListOf<String>()
        val startRegex = Regex("""<$tag\b[^>]*>""", RegexOption.IGNORE_CASE)
        var searchStart = 0
        while (true) {
            val match = startRegex.find(html, searchStart) ?: break
            val end = findElementEnd(html, match.range.first, tag) ?: break
            result += html.substring(match.range.first, end)
            searchStart = end
        }
        return result
    }

    private fun findElementEnd(html: String, start: Int, tag: String): Int? {
        val tagRegex = Regex("""</?$tag\b[^>]*>""", RegexOption.IGNORE_CASE)
        var depth = 0
        for (match in tagRegex.findAll(html, start)) {
            if (match.value.startsWith("</")) {
                depth--
                if (depth == 0) return match.range.last + 1
            } else {
                depth++
            }
        }
        return null
    }

    private companion object {
        const val APP_NAME = "ZCMW2240"
        const val URL = "https://ecc.ssu.ac.kr:8443/sap/bc/webdynpro/SAP/$APP_NAME"
        const val COURSE_COLUMN_COUNT = 16
    }
}
