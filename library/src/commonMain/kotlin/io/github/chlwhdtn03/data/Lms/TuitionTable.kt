package io.github.chlwhdtn03.data.Lms

import kotlinx.serialization.Serializable

@Serializable
data class TuitionCell(
    val year: String,               // 학년도
    val semester: String,           // 학기
    val grade: String,              // 학년(기)
    val registrationType: String,   // 등록구분
    val registrationDate: String,   // 등록일자
    val amount: String,             // 등록금액
    val reduction: String,          // 사전감면
    val paymentAmount: String       // 납부금액
)

@Serializable
data class TuitionTable(
    val items: List<TuitionCell>
)
