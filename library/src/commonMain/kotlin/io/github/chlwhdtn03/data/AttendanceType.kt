package io.github.chlwhdtn03.data

import kotlinx.serialization.Serializable

@Serializable
enum class AttendanceType(
    val kor: String
) {
    ATTENDANCE("출석"), ABSENT("결석"), LATE("지각"), NONE("정보 없음")
}
