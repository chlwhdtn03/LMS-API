package io.github.chlwhdtn03.data.Lms

import kotlinx.serialization.Serializable

@Serializable
enum class DayOfWeek(val koreanName: String) {
    MONDAY("월요일"),
    TUESDAY("화요일"),
    WEDNESDAY("수요일"),
    THURSDAY("목요일"),
    FRIDAY("금요일"),
    SATURDAY("토요일"),
    SUNDAY("일요일");

    companion object {
        fun fromKoreanName(name: String): DayOfWeek? {
            val cleanName = name.trim()
            return entries.firstOrNull {
                it.koreanName == cleanName ||
                it.koreanName.startsWith(cleanName) ||
                cleanName.startsWith(it.koreanName)
            }
        }
    }
}

@Serializable
data class TimetableCell(
    val dayOfWeek: DayOfWeek, // 요일
    val period: String, // 교시 (e.g., "1 교시", "2 교시")
    val periodTime: String, // 교시 시간 범위 (e.g., "(08:00-08:50)")
    val subject: String, // 과목명
    val professor: String, // 교수명
    val time: String, // 실제 강의 시간 (e.g., "09:00-10:15")
    val classroom: String // 강의실
)

@Serializable
data class Timetable(
    val year: String, // 학년도 (e.g., "2026학년도")
    val semester: String, // 학기 (e.g., "1학기")
    val items: List<TimetableCell>
)
