package io.github.chlwhdtn03.data.Lms

import kotlinx.serialization.Serializable

@Serializable
data class SubSection(
    val subsection_id: Int,
    val subsection_title: String? = "",
    val position: Int,
    val status: String,
)
