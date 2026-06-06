package io.github.chlwhdtn03.data.Lms

import kotlinx.serialization.Serializable

@Serializable
data class LearnStatuses(
    val learnstatuses: List<LearnStatus>,
    val total_count: Int,
)
