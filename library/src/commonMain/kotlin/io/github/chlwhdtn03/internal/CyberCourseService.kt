package io.github.chlwhdtn03.internal

import io.github.chlwhdtn03.data.Cyber.CyberEvaluation
import io.github.chlwhdtn03.data.Cyber.CyberEvaluationType
import io.github.chlwhdtn03.data.Cyber.CyberLecture
import io.github.chlwhdtn03.data.Cyber.CyberSubject
import io.github.chlwhdtn03.data.Cyber.CyberWeek
import io.github.chlwhdtn03.decodeHtmlEntities
import io.github.chlwhdtn03.stripHtmlTags
import io.ktor.client.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 사이버대학교 LMS(`lms.kcu.ac`)의 수강과목/수강일람/학습평가 조회와 HTML 파싱을 담당합니다.
 *
 * LMS 서버는 마지막으로 진입한 강의실(과목)을 세션에 저장하고, 학습평가 목록 등 일부 페이지는
 * 과목 파라미터 없이 그 값을 기준으로 응답합니다. 그래서 강의실을 바꾸는 요청과 그에 의존하는
 * 요청은 [classroomMutex]로 묶어 동시 호출 시 다른 과목의 결과가 섞이지 않게 합니다.
 */
internal class CyberCourseService(
    private val client: HttpClient,
) {
    private val classroomMutex = Mutex()

    suspend fun getSubjects(): List<CyberSubject> {
        val response = client.submitForm(
            url = "$LMS_BASE_URL/atnlcSubj/list",
            formParameters = parameters {
                append("menuGrpCd", "new_SSJU")
            },
        )
        return parseSubjects(response.bodyAsText())
    }

    suspend fun getWeeklyLectures(subject: CyberSubject): List<CyberWeek> {
        val html = classroomMutex.withLock { enterClassroom(subject) }
        return parseWeeklyLectures(html)
    }

    suspend fun getEvaluations(subject: CyberSubject): List<CyberEvaluation> {
        val html = classroomMutex.withLock {
            enterClassroom(subject)
            client.submitForm(
                url = "$LMS_BASE_URL/atnlcSubj/lrnEvlApyexm/list",
                formParameters = parameters {
                    append("currSub", "05059")
                    append("prgmId", "05059")
                    append("subjType", "atnlcSubj")
                    append("authrtSeCd", "")
                },
            ).bodyAsText()
        }
        checkClassroom(html, subject)
        return parseEvaluations(html)
    }

    /** 수강일람 페이지를 요청해 서버 세션의 현재 강의실을 [subject]로 바꾸고, 그 응답 HTML을 반환합니다. */
    private suspend fun enterClassroom(subject: CyberSubject): String {
        val response = client.submitForm(
            url = "$LMS_BASE_URL/atnlcSubj/atnlcApe/list",
            formParameters = parameters {
                append("menuCd", "05082")
                append("currSub", "05082")
                append("prgmId", "LRN_LM_S_018")
                append("subjType", "atnlcSubj")
                append("authrtSeCd", "")
                append("shyr", subject.year)
                append("smstCd", subject.semesterCode)
                append("coseCd", subject.courseCode)
                append("dertCd", subject.deptCode)
            },
        )
        return response.bodyAsText()
    }

    /** 응답 페이지의 강의실 정보(hidden `shyr`/`smstCd`/`coseCd`)가 요청한 과목과 다르면 예외를 던집니다. */
    private fun checkClassroom(html: String, subject: CyberSubject) {
        val courseCode = hiddenInputValue(html, "coseCd")
        val year = hiddenInputValue(html, "shyr")
        val semesterCode = hiddenInputValue(html, "smstCd")
        if (courseCode != subject.courseCode || year != subject.year || semesterCode != subject.semesterCode) {
            throw IllegalStateException(
                "학습평가 목록의 강의실(${year}/${semesterCode}/${courseCode})이 " +
                    "요청한 과목(${subject.year}/${subject.semesterCode}/${subject.courseCode})과 다릅니다.",
            )
        }
    }

    /**
     * 학습평가 목록을 파싱합니다. 출석 항목과, 제목이나 기간(시작/마감일시)이 비어 있는
     * 퀴즈/과제(아직 등록되지 않은 자리표시 행)는 제외합니다.
     */
    internal fun parseEvaluations(html: String): List<CyberEvaluation> {
        val tbody = EVALUATION_TBODY_REGEX.find(html)?.groupValues?.get(1) ?: return emptyList()
        return EVALUATION_ROW_REGEX.findAll(tbody).map { match ->
            val attributes = match.groupValues[1]
            val row = match.groupValues[2]
            val cells = TD_REGEX.findAll(row).map { it.groupValues[1] }.toList()
            fun cell(index: Int): String = cells.getOrNull(index)?.let(::cellText).orEmpty()

            val typeCode = attrValue(attributes, "data-evl-type").orEmpty()
            CyberEvaluation(
                type = CyberEvaluationType.fromCode(typeCode),
                typeCode = typeCode,
                typeName = cell(0),
                round = cell(1).toIntOrNull(),
                week = cell(2),
                title = cell(3),
                startAt = dateTimeText(cell(4)),
                endAt = dateTimeText(cell(5)),
                timeLimit = cell(6),
                applyText = cell(7),
                submitStatus = cell(8),
                ratio = cell(9),
                isInPeriod = attrValue(attributes, "data-peri") == "1",
                isResubmission = attrValue(attributes, "data-is-add") == "1",
                rawDeadlineFlag = attrValue(attributes, "data-ddln").orEmpty(),
                hasApplyButton = APPLY_BUTTON_REGEX.containsMatchIn(row),
            )
        }.filter(::isCollectable).toList()
    }

    private fun isCollectable(evaluation: CyberEvaluation): Boolean {
        return when (evaluation.type) {
            CyberEvaluationType.ATTENDANCE -> false
            CyberEvaluationType.QUIZ, CyberEvaluationType.ASSIGNMENT ->
                evaluation.title.isNotEmpty() && evaluation.startAt.isNotEmpty() && evaluation.endAt.isNotEmpty()
            else -> true
        }
    }

    /** 셀 텍스트에서 첫 번째 `yyyy-MM-dd HH:mm` 일시만 꺼냅니다. `1차 : ` 같은 접두어는 버리고, 없으면 빈 문자열입니다. */
    private fun dateTimeText(text: String): String {
        return DATE_TIME_REGEX.find(text)?.value.orEmpty()
    }

    /** 셀 HTML에서 모바일용 라벨(`span.mTxt`)과 태그를 제거하고, `-`만 있는 칸은 빈 문자열로 바꿉니다. */
    private fun cellText(cellHtml: String): String {
        val text = cellHtml.replace(M_TXT_REGEX, "").stripHtmlTags()
        return if (text == "-") "" else text
    }

    private fun hiddenInputValue(html: String, id: String): String? {
        return Regex("""<input\b[^>]*\bid="$id"[^>]*\bvalue="([^"]*)"""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
    }

    internal fun parseSubjects(html: String): List<CyberSubject> {
        return SUBJECT_BLOCK_REGEX.findAll(html).map { match ->
            val year = match.groupValues[1]
            val semesterCode = match.groupValues[2]
            val block = match.groupValues[3]
            CyberSubject(
                name = TIT_STRONG_REGEX.find(block)?.groupValues?.get(1)?.stripHtmlTags().orEmpty(),
                category = R_ITEM_REGEX.find(block)?.groupValues?.get(1)?.stripHtmlTags().orEmpty(),
                professor = infoItemValue(block, "담당교수"),
                credit = infoItemValue(block, "학점"),
                year = year,
                semesterCode = semesterCode,
                courseCode = attrValue(block, "data-cose-cd").orEmpty(),
                deptCode = attrValue(block, "data-dert-cd").orEmpty(),
                userNo = attrValue(block, "data-user").orEmpty(),
                progressPercent = IN_PERCENT_REGEX.find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            )
        }.toList()
    }

    internal fun parseWeeklyLectures(html: String): List<CyberWeek> {
        val weeks = mutableListOf<CyberWeek>()
        var currentWeekNo = 0
        var currentPeriod = ""
        var currentTopic = ""
        var currentAttendance = ""
        var currentLectures = mutableListOf<CyberLecture>()

        fun flush() {
            if (currentWeekNo != 0) {
                weeks += CyberWeek(
                    weekNo = currentWeekNo,
                    attendancePeriod = currentPeriod,
                    topic = currentTopic,
                    attendanceStatus = currentAttendance,
                    lectures = currentLectures,
                )
            }
        }

        for (match in ROW_REGEX.findAll(html)) {
            val rowClass = match.groupValues[1]
            val row = match.value
            if (rowClass == "mAccordion") continue

            if (rowClass == "weekAll") {
                flush()
                currentWeekNo = WEEK_NO_REGEX.find(row)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                currentPeriod = ATTENDANCE_PERIOD_REGEX.find(row)?.groupValues?.get(1)?.stripHtmlTags().orEmpty()
                currentTopic = TOPIC_REGEX.find(row)?.groupValues?.get(1)?.stripHtmlTags().orEmpty()
                currentAttendance = ATTENDANCE_STATUS_REGEX.find(row)?.groupValues?.get(1)?.stripHtmlTags().orEmpty()
                currentLectures = mutableListOf()
            }

            val lectureNo = attrValue(row, "data-lect-no")?.toIntOrNull() ?: continue
            val statusText = LECTURE_STATUS_REGEX.find(row)?.groupValues?.get(1)?.stripHtmlTags().orEmpty()
            val progressPercent = IN_PERCENT_REGEX.find(row)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val times = TIME_REGEX.findAll(row).map { it.groupValues[1] }.toList()
            val studyTime = times.getOrElse(0) { "" }
            val baseTime = times.getOrElse(1) { "" }

            var videoFilePath: String? = null
            var audioFilePath: String? = null
            for (buttonMatch in FILE_BUTTON_REGEX.findAll(row)) {
                val button = buttonMatch.value
                val mediaType = attrValue(button, "data-media-type")
                val filePath = attrValue(button, "data-file-path")
                when (mediaType) {
                    "mp4" -> videoFilePath = filePath
                    "mp3" -> audioFilePath = filePath
                }
            }

            currentLectures += CyberLecture(
                lectureNo = lectureNo,
                statusText = statusText,
                progressPercent = progressPercent,
                studyTime = studyTime,
                baseTime = baseTime,
                videoFilePath = videoFilePath,
                audioFilePath = audioFilePath,
            )
        }
        flush()

        return weeks
    }

    private fun infoItemValue(block: String, label: String): String {
        return Regex(
            """<div class="graybox">$label</div>\s*<p>([^<]*)</p>""",
            RegexOption.IGNORE_CASE,
        ).find(block)?.groupValues?.get(1)?.decodeHtmlEntities()?.trim().orEmpty()
    }

    private fun attrValue(html: String, name: String): String? {
        return Regex("""$name="([^"]*)"""").find(html)?.groupValues?.get(1)
    }

    private companion object {
        const val LMS_BASE_URL = "https://lms.kcu.ac"

        val SUBJECT_BLOCK_REGEX = Regex(
            """<div class="inBoxCont" data-shyr="([^"]*)" data-smst-cd="([^"]*)"[^>]*>([\s\S]*?)<!--//\s*inBoxCont\s*-->""",
            RegexOption.IGNORE_CASE,
        )
        val TIT_STRONG_REGEX = Regex(
            """<div class="inBoxTit">\s*<strong>([\s\S]*?)</strong>""",
            RegexOption.IGNORE_CASE,
        )
        val R_ITEM_REGEX = Regex("""<span class="rItem">([^<]*)</span>""", RegexOption.IGNORE_CASE)
        val IN_PERCENT_REGEX = Regex(
            """class="inPercent">[\s\S]*?<strong>(\d+)</strong>""",
            RegexOption.IGNORE_CASE,
        )

        val ROW_REGEX = Regex(
            """<tr\s+class="(weekAll|week|mAccordion)"[^>]*>([\s\S]*?)</tr>""",
            RegexOption.IGNORE_CASE,
        )
        val WEEK_NO_REGEX = Regex("""<td rowspan="3">(\d+)주</td>""", RegexOption.IGNORE_CASE)
        val ATTENDANCE_PERIOD_REGEX = Regex(
            """<td rowspan="3">\d+주</td>\s*<td rowspan="3">([\s\S]*?)</td>""",
            RegexOption.IGNORE_CASE,
        )
        val TOPIC_REGEX = Regex(
            """class="tit">([\s\S]*?)</span>""",
            RegexOption.IGNORE_CASE,
        )
        val ATTENDANCE_STATUS_REGEX = Regex(
            """class="tdAttend">\s*<p class="attendStatus[^"]*">([^<]*)</p>""",
            RegexOption.IGNORE_CASE,
        )
        val LECTURE_STATUS_REGEX = Regex(
            """<td[^>]*class="txtL[^"]*"[^>]*>([^<]*)</td>""",
            RegexOption.IGNORE_CASE,
        )
        val TIME_REGEX = Regex("""<td>(\d{1,3}:\d{2})</td>""")
        val FILE_BUTTON_REGEX = Regex(
            """<button\b[^>]*class="btnFile btnDwnld"[^>]*>""",
            RegexOption.IGNORE_CASE,
        )

        val EVALUATION_TBODY_REGEX = Regex(
            """<table id="tblEvl">[\s\S]*?<tbody>([\s\S]*?)</tbody>""",
            RegexOption.IGNORE_CASE,
        )
        val EVALUATION_ROW_REGEX = Regex(
            """<tr\s+(data-evl-type="[^"]*"[^>]*)>([\s\S]*?)</tr>""",
            RegexOption.IGNORE_CASE,
        )
        val DATE_TIME_REGEX = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""")
        val TD_REGEX = Regex("""<td\b[^>]*>([\s\S]*?)</td>""", RegexOption.IGNORE_CASE)
        val M_TXT_REGEX = Regex("""<span class="mTxt">[\s\S]*?</span>""", RegexOption.IGNORE_CASE)
        val APPLY_BUTTON_REGEX = Regex("""class="[^"]*\bbtnEvlApyexm\b""", RegexOption.IGNORE_CASE)
    }
}
