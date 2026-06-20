package io.github.chlwhdtn03.data.Lms

import kotlinx.serialization.Serializable

@Serializable
data class GradeCell(
    val subjectCode: String,      // 과목코드
    val subjectName: String,      // 과목명
    val classification: String,   // 이수구분
    val credits: String,          // 학점
    val grade: String,            // 등급 (A+, A0 등)
    val gradePoint: String,       // 평점
    val professor: String         // 교수명
)

@Serializable
data class GradeTable(
    val items: List<GradeCell>
)

@Serializable
data class SemesterGradeSummaryCell(
    val year: String,               // 학년도 (예: "2025")
    val semester: Semester?,        // 학기 (FIRST, SUMMER, SECOND, WINTER)
    val attemptedCredits: String,    // 신청학점 (예: "22.5")
    val earnedCredits: String,       // 취득학점 (예: "22.5")
    val pfCredits: String,           // P/F학점 (예: "4.5")
    val gpa: String,                 // 평점평균 (예: "3.83")
    val gpaSum: String,              // 평점계 (예: "69.00")
    val arithmeticMean: String,      // 산술평균 (예: "91.60")
    val semesterRank: String,        // 학기별석차 (예: "51/140")
    val totalRank: String,           // 전체석차 (예: "51/141")
    val academicWarning: String,     // 학사경고여부
    val consultationStatus: String,  // 상담여부
    val failedYearStatus: String     // 유급
)

@Serializable
data class SemesterGradeSummaryTable(
    val items: List<SemesterGradeSummaryCell>
)
