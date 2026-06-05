package io.github.chlwhdtn03.data.Lms

import kotlinx.serialization.Serializable

@Serializable
data class LearnStatuses(
    val learnstatuses: List<LearnStatus>? = emptyList(),
    val total_count: Int? = 0,
)
