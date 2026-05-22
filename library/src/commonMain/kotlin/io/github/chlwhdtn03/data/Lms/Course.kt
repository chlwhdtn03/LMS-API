package io.github.chlwhdtn03.data.Lms

import kotlinx.serialization.Serializable

@Serializable
data class Course(
    val id: Int,
    val term_id: Int,
)
