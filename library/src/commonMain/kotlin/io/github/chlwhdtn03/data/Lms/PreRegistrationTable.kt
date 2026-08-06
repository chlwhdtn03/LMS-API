package io.github.chlwhdtn03.data.Lms

import io.github.chlwhdtn03.LmsApi
import kotlinx.serialization.Serializable

/** 예비수강신청 장바구니에 담긴 과목 한 건입니다. */
@Serializable
data class PreRegistrationCourse(
    val priority: String,
    /** 화면 HTML에 실제 링크가 포함된 경우의 값이며, 버튼형 계획서는 빈 문자열입니다. */
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
) {
    /**
     * Android와 iOS에서 지원합니다. 호출할 때만 이 장바구니 과목의 OZ Viewer를 로드하고
     * PDF 바이트를 반환하므로 완료까지 시간이 걸릴 수 있습니다.
     */
    @Throws(Exception::class)
    suspend fun loadPlan(): ByteArray = LmsApi.loadPreRegistrationPlan(this)
}

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
