package io.github.chlwhdtn03.data.Lms

import kotlinx.serialization.Serializable

@Serializable
data class ScoredAssignment(
    val groupName: String? = "",
    val name: String? = "",
    val score: Double? = 0.0,
    val maxScore: Double? = 0.0,
)
