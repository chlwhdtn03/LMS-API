package io.github.chlwhdtn03.data.Lms

import kotlinx.serialization.Serializable

@Serializable
data class Todos(
    val to_dos: List<Todo>,
    val total_count: Int,
    val total_unread_messages: Int,
)
