package io.github.chlwhdtn03.data.Lms

import kotlinx.serialization.Serializable

@Serializable
data class ScholarshipHistoryCell(
    val year: String,                      // 학년 (Year)
    val semester: String,                  // 학기 (Semester)
    val scholarshipName: String,           // 장학금명 (Scholarship Name)
    val paymentMethod: String,             // 지급방법 (Payment Method)
    val processStatus: String,             // 처리상태 (Process Status)
    val note: String,                      // 비고 (Note)
    val dropReason: String,                // 탈락사유 (Drop Reason)
    val processDate: String,               // 처리일자 (Process Date)
    val selectedAmount: String,            // 선발금액 (Selected Amount)
    val actualAmount: String,              // 실수혜금액 (Actual Amount)
    val redeemedAmount: String,            // 환수금액 (Redeemed Amount)
    val replacedAmount: String,            // 교체금액 (Replaced Amount)
    val replacedScholarshipName: String,   // 교체장학금명 (Replaced Scholarship Name)
    val workDepartment: String             // 근로부서 (Work Department)
)

@Serializable
data class ScholarshipHistoryTable(
    val items: List<ScholarshipHistoryCell>
)
