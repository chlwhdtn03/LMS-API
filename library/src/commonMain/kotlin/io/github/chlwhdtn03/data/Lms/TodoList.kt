package io.github.chlwhdtn03.data.Lms

import kotlinx.serialization.Serializable

@Serializable
data class TodoList(
    val section_id: Int? = -1,
    val unit_id: Int? = -1,
    val component_id: Int? = -1,
    val generated_from_lecture_content: Boolean? = false,
    val component_type: String? = "", // commons : 동영상 , assignment : 과제 , quiz : 퀴즈
    val assignment_id: Int? = -1,
    val title: String? = "",
    val due_date: String? = "",
    val late_at: String? = "",
    val description: String? = "",
)
