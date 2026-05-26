package io.github.chlwhdtn03

import io.github.chlwhdtn03.data.Lms.Info
import io.github.chlwhdtn03.data.Lms.LmsSession
import io.github.chlwhdtn03.data.Lms.Subject
import io.github.chlwhdtn03.data.Lms.Term
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
