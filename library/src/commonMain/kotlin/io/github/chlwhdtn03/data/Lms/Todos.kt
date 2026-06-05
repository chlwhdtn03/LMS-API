package io.github.chlwhdtn03.data.Lms

import kotlinx.serialization.Serializable

@Serializable
data class Todos(
    val to_dos: List<Todo>? = emptyList(),
    val total_count: Int? = 0,
    val total_unread_messages: Int? = 0,
)
