package io.github.chlwhdtn03.internal

import io.github.chlwhdtn03.data.Cyber.CyberLecture
import io.github.chlwhdtn03.data.Cyber.CyberSubject
import io.github.chlwhdtn03.data.Cyber.CyberWeek
import io.github.chlwhdtn03.decodeHtmlEntities
import io.github.chlwhdtn03.stripHtmlTags
import io.ktor.client.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*

/** 사이버대학교 LMS(`lms.kcu.ac`)의 수강과목/수강일람 조회와 HTML 파싱을 담당합니다. */
internal class CyberCourseService(
    private val client: HttpClient,
) {
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
        return parseWeeklyLectures(response.bodyAsText())
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
    }
}
