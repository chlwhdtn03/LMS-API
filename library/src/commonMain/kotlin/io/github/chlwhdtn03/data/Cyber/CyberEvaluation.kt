package io.github.chlwhdtn03.data.Cyber

import kotlinx.serialization.Serializable

/**
 * 학습평가 항목 종류. `/atnlcSubj/lrnEvlApyexm/list` 목록의 `tr[data-evl-type]` 값과 대응한다.
 */
@Serializable
enum class CyberEvaluationType(val code: String) {
    ATTENDANCE("01"),
    QUIZ("02"),
    ASSIGNMENT("03"),
    DISCUSSION("04"),
    EXAM("06"),
    ETC("99"),
    UNKNOWN(""),
    ;

    companion object {
        fun fromCode(code: String): CyberEvaluationType {
            return entries.firstOrNull { it.code == code && it != UNKNOWN } ?: UNKNOWN
        }
    }
}

/**
 * 학습평가응시 목록의 평가 한 건(퀴즈, 과제, 시험, 토론 등).
 *
 * 값이 없거나 `-`로 표시된 칸은 빈 문자열/null로 정규화한다. [endAt]은 과제의 경우
 * `1차 : 2026-11-02 23:59`처럼 차수 접두어가 붙은 원문 그대로다.
 * [isInPeriod]는 `data-peri`(현재 응시/제출 기간 안인지), [isResubmission]은 `data-is-add`(과제 재제출 행),
 * [rawDeadlineFlag]는 의미가 확인되지 않은 `data-ddln` 원본 값이다.
 * [hasApplyButton]은 응시/제출 버튼이 HTML에 존재하는지만 나타내며, 과제의 경우 사이트 JS가
 * 본제출/재제출 행 중 하나의 버튼만 보여주므로 실제 제출 가능 여부와 다를 수 있다.
 */
@Serializable
data class CyberEvaluation(
    val type: CyberEvaluationType,
    val typeCode: String,
    val typeName: String,
    val round: Int?,
    val week: String,
    val title: String,
    val startAt: String,
    val endAt: String,
    val timeLimit: String,
    val applyText: String,
    val submitStatus: String,
    val ratio: String,
    val isInPeriod: Boolean,
    val isResubmission: Boolean,
    val rawDeadlineFlag: String,
    val hasApplyButton: Boolean,
)
