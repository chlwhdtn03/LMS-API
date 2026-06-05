package io.github.chlwhdtn03.data.Lms

import kotlinx.serialization.Serializable

@Serializable
data class Assignment(
    val id: Int,
    val points_possible: Double?,
    val name: String? = "",
    val due_at: String? = "",
)
