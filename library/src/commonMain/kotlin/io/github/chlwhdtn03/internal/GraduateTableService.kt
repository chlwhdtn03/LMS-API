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
        val initialContext = webDynpro.openSession(URL, APP_NAME)
        val detailButtonId = findDetailButtonId(initialContext.html)
        val context = webDynpro.refreshSession(
            context = initialContext,
            eventQueue = graduateTableEventQueue(URL, detailButtonId),
            requestHeaders = mapOf(
                "Origin" to ECC_ORIGIN,
                "Referer" to URL,
                "X-XHR-Logon" to "accept",
            ),
        )
        return parseGraduateTable(context.html)
    }

    // Web Dynpro 컨트롤 ID는 세션과 진입 경로에 따라 달라지므로 캡처된 WDB0를 고정하지 않습니다.
    private fun findDetailButtonId(html: String): String {
        val captionIndex = html.indexOf("과목상세 보기")
        if (captionIndex < 0) {
            throw IllegalStateException("졸업사정표의 과목상세 보기 버튼을 찾지 못했습니다.")
        }
        return Regex("""id="([^"]+)"[^>]*\bct="B""", RegexOption.IGNORE_CASE)
            .findAll(html.substring(0, captionIndex))
            .lastOrNull()
            ?.groupValues
            ?.get(1)
            ?: throw IllegalStateException("졸업사정표의 과목상세 보기 버튼 ID를 찾지 못했습니다.")
    }

    private fun graduateTableEventQueue(pageUrl: String, buttonId: String): String {
        val clientInspectorData = webDynpro.escape(
            "CssMatchesHtmlVersion:TRUE;ClientURL:$pageUrl#",
        )
        return "ClientInspector_Notify~E002Id~E004WD01~E005Data~E004$clientInspectorData" +
            "~E003~E002ResponseData~E004delta~E005EnqueueCardinality~E004single~E003~E002" +
            "~E003~E001Button_Press~E002Id~E004$buttonId~E003~E002ResponseData~E004delta" +
            "~E005ClientAction~E004submit~E003~E002~E003~E001Form_Request~E002Id" +
            "~E004sap.client.SsrClient.form~E005Async~E004false~E005FocusInfo~E004" +
            "~0040~007B~0022sFocussedId~0022~003A~0022$buttonId~0022~007D~E005Hash" +
            "~E004~E005DomChanged~E004false~E005IsDirty~E004false~E003~E002ResponseData" +
            "~E004delta~E003~E002~E003"
    }

    fun parseGraduateTable(html: String): GraduateTable {
        val decodedHtml = html.decodeHtmlEntities()
        val rowRegex = Regex("""<tr\b[^>]*>([\s\S]*?)</tr>""", RegexOption.IGNORE_CASE)
        val cellRegex = Regex("""<(?:td|th)\b[^>]*>([\s\S]*?)</(?:td|th)>""", RegexOption.IGNORE_CASE)
        val rows = rowRegex.findAll(decodedHtml).map { rowMatch ->
            cellRegex.findAll(rowMatch.groupValues[1]).map { it.groupValues[1].stripHtmlTags().trim() }.toList()
        }.filter { it.isNotEmpty() }.toList()

        val hasUsedSubjectsColumn = decodedHtml.contains("과목사용")
        val headers = extractHeaders(rows).ifEmpty {
            if (hasUsedSubjectsColumn) {
                DEFAULT_HEADERS_WITH_USED_SUBJECTS
            } else {
                DEFAULT_HEADERS_WITHOUT_USED_SUBJECTS
            }
        }

        val columnMap = headers.mapIndexed { index, column -> column to index }.toMap()
        val classificationIndex = columnMap[GraduateColumn.CLASSIFICATION] ?: 0

        val result = mutableListOf<GraduateTableCell>()
        var currentClassification = ""

        for (cells in rows) {
            val isClassificationRow = cells.size == headers.size
            val isContinuationRow = cells.size == headers.size - 1

            if (!isClassificationRow && !isContinuationRow) continue

            fun getCell(column: GraduateColumn): String {
                val originalIndex = columnMap[column] ?: return ""
                val actualIndex = if (isClassificationRow) {
                    originalIndex
                } else {
                    if (originalIndex > classificationIndex) originalIndex - 1 else originalIndex
                }
                return cells.getOrNull(actualIndex).orEmpty()
            }

            val classification = if (isClassificationRow) {
                getCell(GraduateColumn.CLASSIFICATION)
            } else {
                currentClassification
            }
            val requirement = getCell(GraduateColumn.REQUIREMENT)
            val evaluation = getCell(GraduateColumn.RESULT)
            val standardValue = getCell(GraduateColumn.STANDARD_VALUE)
            val calculatedValue = getCell(GraduateColumn.CALCULATED_VALUE)
            val difference = getCell(GraduateColumn.DIFFERENCE)
            val usedSubjects = if (GraduateColumn.USED_SUBJECTS in columnMap) {
                getCell(GraduateColumn.USED_SUBJECTS).toSubjectList()
            } else {
                emptyList()
            }

            if (
                classification.isNotBlank() &&
                classification != "졸업사정일자" &&
                classification != "이수구분" &&
                classification != "- 담당부서 :" &&
                requirement.isNotBlank() &&
                requirement != "졸업요건" &&
                evaluation.isGraduateEvaluation()
            ) {
                currentClassification = classification
                result += GraduateTableCell(
                    classification = classification,
                    requirement = requirement,
                    standardValue = standardValue,
                    calculatedValue = calculatedValue,
                    difference = difference,
                    result = evaluation,
                    usedSubjects = usedSubjects,
                )
            }
        }
        return GraduateTable(items = result)
    }

    private fun extractHeaders(rows: List<List<String>>): List<GraduateColumn> {
        for (row in rows) {
            val matched = row.mapNotNull { parseHeader(it) }
            if (matched.size >= 4 && GraduateColumn.CLASSIFICATION in matched && GraduateColumn.RESULT in matched) {
                return matched
            }
        }

        val sequentialHeaders = mutableListOf<GraduateColumn>()
        for (row in rows) {
            val singleCellText = row.singleOrNull() ?: continue
            val header = parseHeader(singleCellText) ?: continue
            if (header !in sequentialHeaders) {
                sequentialHeaders.add(header)
            }
        }
        if (sequentialHeaders.size >= 4 && GraduateColumn.CLASSIFICATION in sequentialHeaders && GraduateColumn.RESULT in sequentialHeaders) {
            return sequentialHeaders
        }

        return emptyList()
    }

    private fun parseHeader(headerText: String): GraduateColumn? {
        val text = headerText.trim()
        return when {
            text == "이수구분" -> GraduateColumn.CLASSIFICATION
            text == "졸업요건" -> GraduateColumn.REQUIREMENT
            text == "기준값" -> GraduateColumn.STANDARD_VALUE
            text == "계산값 - 기준값" || text == "차이" -> GraduateColumn.DIFFERENCE
            text == "결과" -> GraduateColumn.RESULT
            text == "과목사용" -> GraduateColumn.USED_SUBJECTS
            text == "계산값" -> GraduateColumn.CALCULATED_VALUE
            else -> null
        }
    }

    private fun String.toSubjectList(): List<String> {
        return stripHtmlTags()
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
    }

    private fun String.isGraduateEvaluation(): Boolean {
        return this == "충족" || this == "부족" || this == "면제" || this == "해당없음" || this == "이수" || this == "미이수" || this == "통과"
    }

    private enum class GraduateColumn {
        CLASSIFICATION,
        REQUIREMENT,
        STANDARD_VALUE,
        CALCULATED_VALUE,
        DIFFERENCE,
        RESULT,
        USED_SUBJECTS,
    }

    private companion object {
        const val APP_NAME = "ZCMW8015"
        const val ECC_ORIGIN = "https://ecc.ssu.ac.kr:8443"
        const val URL = "$ECC_ORIGIN/sap/bc/webdynpro/SAP/$APP_NAME"

        val DEFAULT_HEADERS_WITHOUT_USED_SUBJECTS = listOf(
            GraduateColumn.CLASSIFICATION,
            GraduateColumn.REQUIREMENT,
            GraduateColumn.STANDARD_VALUE,
            GraduateColumn.CALCULATED_VALUE,
            GraduateColumn.DIFFERENCE,
            GraduateColumn.RESULT,
        )

        val DEFAULT_HEADERS_WITH_USED_SUBJECTS = listOf(
            GraduateColumn.CLASSIFICATION,
            GraduateColumn.REQUIREMENT,
            GraduateColumn.STANDARD_VALUE,
            GraduateColumn.CALCULATED_VALUE,
            GraduateColumn.DIFFERENCE,
            GraduateColumn.RESULT,
            GraduateColumn.USED_SUBJECTS,
        )
    }
}
