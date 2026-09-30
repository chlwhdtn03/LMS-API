package io.github.chlwhdtn03

import io.github.chlwhdtn03.data.Cyber.CyberEvaluation
import io.github.chlwhdtn03.data.Cyber.CyberSubject
import io.github.chlwhdtn03.data.Cyber.CyberWeek

data class CyberLoginResult(
    val success: Boolean,
    val errorMessage: String? = null,
)

data class CyberSubjectsResult(
    val success: Boolean,
    val subjects: List<CyberSubject> = emptyList(),
    val errorMessage: String? = null,
)

data class CyberWeeklyLecturesResult(
    val success: Boolean,
    val weeks: List<CyberWeek> = emptyList(),
    val errorMessage: String? = null,
)

data class CyberEvaluationsResult(
    val success: Boolean,
    val evaluations: List<CyberEvaluation> = emptyList(),
    val errorMessage: String? = null,
)
