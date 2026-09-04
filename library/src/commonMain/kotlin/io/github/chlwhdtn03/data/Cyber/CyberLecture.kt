package io.github.chlwhdtn03.data.Cyber

import kotlinx.serialization.Serializable

/**
 * 강의 한 편(주차 내 1강, 2강 ...)의 학습 정보.
 *
 * `lectures_list.htm`에는 완료 여부를 나타내는 명시적인 boolean 필드가 없어,
 * [progressPercent]가 100 이상이면 완료로 간주하는 [isCompleted]를 계산 프로퍼티로 둔다.
 */
@Serializable
data class CyberLecture(
    val lectureNo: Int,
    val statusText: String,
    val progressPercent: Int,
    val studyTime: String,
    val baseTime: String,
    val videoFilePath: String?,
    val audioFilePath: String?,
) {
    val isCompleted: Boolean
        get() = progressPercent >= 100
}

/**
 * 한 주차의 출석/학습 정보. `weekAll` 행(주차, 출석인정기간, 주제, 출석)과
 * 뒤따르는 `week` 행들(강의별 정보)을 합쳐서 만든다.
 */
@Serializable
data class CyberWeek(
    val weekNo: Int,
    val attendancePeriod: String,
    val topic: String,
    val attendanceStatus: String,
    val lectures: List<CyberLecture>,
)
