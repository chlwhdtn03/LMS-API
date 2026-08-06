package io.github.chlwhdtn03

import io.github.chlwhdtn03.data.Lms.*
import io.github.chlwhdtn03.data.notice.ScholarshipNotice
import io.github.chlwhdtn03.data.notice.StartUpNotice
import kotlin.time.ExperimentalTime

data class LmsLoginResult(
    val success: Boolean,
    val errorMessage: String? = null,
)

@OptIn(ExperimentalTime::class)
data class LmsTermsResult(
    val success: Boolean,
    val terms: List<Term> = emptyList(),
    val errorMessage: String? = null,
)

data class LmsLoginInfoResult(
    val success: Boolean,
    val info: Info? = null,
    val errorMessage: String? = null,
)

data class LmsCookiesResult(
    val success: Boolean,
    val lmsSession: LmsSession = LmsSession(),
    val errorMessage: String? = null,
)

data class LmsSubjectsResult(
    val success: Boolean,
    val subjects: List<Subject> = emptyList(),
    val errorMessage: String? = null,
)

data class StartUpNoticesResult(
    val success: Boolean,
    val notices: List<StartUpNotice> = emptyList(),
    val errorMessage: String? = null,
)

data class ScholarshipNoticesResult(
    val success: Boolean,
    val notices: List<ScholarshipNotice> = emptyList(),
    val errorMessage: String? = null,
)

data class LmsTimetableResult(
    val success: Boolean,
    val timetable: Timetable? = null,
    val errorMessage: String? = null,
)

data class LmsGraduateTableResult(
    val success: Boolean,
    val graduateTable: GraduateTable? = null,
    val errorMessage: String? = null,
)

data class LmsTuitionResult(
    val success: Boolean,
    val tuitionTable: TuitionTable? = null,
    val errorMessage: String? = null,
)

data class LmsScholarshipHistoryResult(
    val success: Boolean,
    val scholarshipHistoryTable: ScholarshipHistoryTable? = null,
    val errorMessage: String? = null,
)

data class LmsGradeResult(
    val success: Boolean,
    val gradeTable: GradeTable? = null,
    val errorMessage: String? = null,
)

data class LmsSemesterGradeSummaryResult(
    val success: Boolean,
    val summaryTable: SemesterGradeSummaryTable? = null,
    val errorMessage: String? = null,
)

data class LmsChapelResult(
    val success: Boolean,
    val chapelInformation: ChapelInformation? = null,
    val errorMessage: String? = null,
)

data class LmsPreRegistrationResult(
    val success: Boolean,
    val preRegistrationTable: PreRegistrationTable? = null,
    val errorMessage: String? = null,
)

data class LmsCourseCatalogOptionsResult(
    val success: Boolean,
    val options: CourseCatalogSearchOptions? = null,
    val errorMessage: String? = null,
)

data class LmsCourseCatalogResult(
    val success: Boolean,
    val courseCatalogTable: CourseCatalogTable? = null,
    val errorMessage: String? = null,
)

/** 한 번만 접근할 수 있는 강의계획서 URL 발급 결과입니다. */
data class LmsPlanResult(
    val success: Boolean,
    val plan: String = "",
    val errorMessage: String? = null,
)
