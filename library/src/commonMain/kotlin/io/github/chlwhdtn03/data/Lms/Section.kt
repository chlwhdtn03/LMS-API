package io.github.chlwhdtn03.data.Lms

import kotlinx.serialization.Serializable

@Serializable
data class Section(
    val section_id: Int,
    val section_title: String? = "",
    val position: Int,
    val is_upcoming: Boolean,
    val subsections: List<SubSection>,
)
