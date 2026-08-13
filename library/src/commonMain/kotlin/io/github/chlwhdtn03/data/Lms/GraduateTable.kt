package io.github.chlwhdtn03.data.Lms

import kotlinx.serialization.Serializable

@Serializable
data class GraduateTableCell(
    val classification: String,       // 이수구분
    val requirement: String,          // 졸업요건
    val standardValue: String,        // 기준값
    val calculatedValue: String,      // 계산값
    val difference: String,           // 계산값-기준값
    val result: String,               // 결과
    val usedSubjects: List<String> = emptyList() // 과목사용함
)

@Serializable
data class GraduateTable(
    val items: List<GraduateTableCell>
)
