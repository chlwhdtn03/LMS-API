package io.github.chlwhdtn03.data.Lms

import kotlinx.serialization.Serializable

@Serializable
data class TodoList(
    val section_id: Int? = -1,
    val unit_id: Int? = -1,
    val component_id: Int? = -1,
    val generated_from_lecture_content: Boolean? = false,
    val component_type: String = "", // commons : 동영상 , assignment : 과제 , quiz : 퀴즈
    val assignment_id: Int? = -1,
    val title: String = "",
    val due_date: String = "",
    val late_at: String? = "",
    val unlock_at: String? = "",
    val description: String? = "",
    val url: String? = "",
    val moduleItemId: Int? = 0,
    val durationOfVideo: Double? = -1.0, // 영상 강의인 경우에만
    val attachments: List<Attachment>? = emptyList(), // 제출한 파일 항목
    val due_at: String? = "", // 일반 마감 (과제에서는 due_date와 동일)
    val lock_at: String? = "", // LMS 원본 잠금 시각
    val submission_deadline: String? = "", // 실제 종료 기준; 종료값이 없으면 일반 마감
)
