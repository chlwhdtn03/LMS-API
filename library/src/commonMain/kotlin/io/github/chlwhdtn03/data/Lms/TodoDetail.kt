package io.github.chlwhdtn03.data.Lms

import kotlinx.serialization.Serializable

@Serializable
data class TodoDetail(
    val module_id: Int? = 0,
    val title: String? = "",
    val position: Int? = 0,
    val module_items: List<TodoDetailModuleItem>? = emptyList(),
)

@Serializable
data class TodoDetailModuleItem(
    val module_item_id: Int? = 0,
    val title: String? = "",
    val content_type: String? = "",
    val content_id: Int? = 0,
    val content_data: TodoDetailContentData? = null,
    val position: Int? = 0,
    val completed: Boolean? = false,
)

@Serializable
data class TodoDetailContentData(
    val item_id: Int? = 0,
    val item_content_type: String? = "",
    val item_content_data: TodoDetailItemContentData? = null,
    val title: String? = "",
    val description: String? = "",
    val due_at: String? = "",
    val late_at: String? = "",
)

@Serializable
data class TodoDetailItemContentData(
    val duration: Double? = null,
)
