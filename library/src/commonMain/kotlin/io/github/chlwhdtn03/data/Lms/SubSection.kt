package io.github.chlwhdtn03.data.Lms

import kotlinx.serialization.Serializable

@Serializable
data class SubSection(
    val subsection_id: Int? = -1,
    val subsection_title: String? = "",
    val position: Int? = 0,
    val status: String? = "",
)
