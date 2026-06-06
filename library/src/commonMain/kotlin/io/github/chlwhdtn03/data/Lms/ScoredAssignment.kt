package io.github.chlwhdtn03.data.Lms

import kotlinx.serialization.Serializable

@Serializable
data class ScoredAssignment(
    val groupName: String,
    val name: String,
    val score: Double,
    val maxScore: Double,
)
