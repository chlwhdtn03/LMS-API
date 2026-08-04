package io.github.chlwhdtn03.data.Lms

import kotlinx.serialization.Serializable

/** 예비수강신청 장바구니에 담긴 과목 한 건입니다. */
@Serializable
data class PreRegistrationCourse(
    val priority: String,
    val plan: String,
    val classification: String,
    val multiMajorClassification: String,
    val engineeringCertification: String,
    val curriculumArea: String,
    val subjectCode: String,
    val subjectName: String,
    val section: String,
    val professor: String,
    val hoursCredits: String,
    val schedule: String,
    val applicationDate: String,
    val note: String,
    val savedStudentCount: String,
)

/** 유세인트 예비수강신청 장바구니 조회 결과입니다. */
@Serializable
data class PreRegistrationTable(
    val period: String,
    val reservationStatus: String,
    val totalCourseCount: String,
    val totalCredits: String,
    val availableCredits: String,
    val items: List<PreRegistrationCourse>,
)
