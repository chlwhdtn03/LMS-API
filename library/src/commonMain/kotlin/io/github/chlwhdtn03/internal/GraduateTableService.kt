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
        val cellRegex = Regex("""<td\b[^>]*>([\s\S]*?)</td>""", RegexOption.IGNORE_CASE)
        val rows = rowRegex.findAll(decodedHtml).map { rowMatch ->
            cellRegex.findAll(rowMatch.groupValues[1]).toList()
        }.toList()
        val hasUsedSubjectsColumn = decodedHtml.contains("과목사용")
        val result = mutableListOf<GraduateTableCell>()
        var currentClassification = ""

        for (cells in rows) {
            val isClassificationRow = cells.size == if (hasUsedSubjectsColumn) 7 else 6
            val isContinuationRow = cells.size == if (hasUsedSubjectsColumn) 6 else 5
            if (isClassificationRow) {
                val classification = cells[0].groupValues[1].stripHtmlTags()
                val requirement = cells[1].groupValues[1].stripHtmlTags()
                val evaluation = cells[5].groupValues[1].stripHtmlTags()
                if (
                    classification.isNotBlank() &&
                    classification != "졸업사정일자" &&
                    classification != "이수구분" &&
                    requirement.isNotBlank() &&
                    evaluation.isGraduateEvaluation()
                ) {
                    currentClassification = classification
                    result += GraduateTableCell(
                        classification = classification,
                        requirement = requirement,
                        standardValue = cells[2].groupValues[1].stripHtmlTags(),
                        calculatedValue = cells[3].groupValues[1].stripHtmlTags(),
                        difference = cells[4].groupValues[1].stripHtmlTags(),
                        result = evaluation,
                        usedSubjects = if (hasUsedSubjectsColumn) {
                            cells[6].groupValues[1].toSubjectList()
                        } else {
                            emptyList()
                        },
                    )
                }
            } else if (isContinuationRow) {
                val requirement = cells[0].groupValues[1].stripHtmlTags()
                val evaluation = cells[4].groupValues[1].stripHtmlTags()
                if (
                    currentClassification.isNotBlank() &&
                    requirement.isNotBlank() &&
                    evaluation.isGraduateEvaluation()
                ) {
                    result += GraduateTableCell(
                        classification = currentClassification,
                        requirement = requirement,
                        standardValue = cells[1].groupValues[1].stripHtmlTags(),
                        calculatedValue = cells[2].groupValues[1].stripHtmlTags(),
                        difference = cells[3].groupValues[1].stripHtmlTags(),
                        result = evaluation,
                        usedSubjects = if (hasUsedSubjectsColumn) {
                            cells[5].groupValues[1].toSubjectList()
                        } else {
                            emptyList()
                        },
                    )
                }
            }
        }
        return GraduateTable(items = result)
    }

    private fun String.toSubjectList(): List<String> {
        return stripHtmlTags()
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
    }

    private fun String.isGraduateEvaluation(): Boolean {
        return this == "충족" || this == "부족"
    }

    private companion object {
        const val APP_NAME = "ZCMW8015"
        const val ECC_ORIGIN = "https://ecc.ssu.ac.kr:8443"
        const val URL = "$ECC_ORIGIN/sap/bc/webdynpro/SAP/$APP_NAME"
    }
}
