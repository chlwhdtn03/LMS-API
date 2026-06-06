package io.github.chlwhdtn03.data.Lms

import kotlinx.serialization.Serializable

/**
 * {
 *     "course_id": 44383,
 *     "activities": {
 *         "total_unread_announcements": 0,
 *         "total_announcements": 0,
 *         "total_unread_resources": 0,
 *         "total_resources": 0,
 *         "total_incompleted_video_conferences": 0,
 *         "total_incompleted_metaverse_conferences": 0,
 *         "total_incompleted_commons_resources": 0,
 *         "total_incompleted_smart_attendances": 0,
 *         "total_incompleted_movies": 0,
 *         "total_unsubmitted_assignments": 1,
 *         "total_unsubmitted_quizzes": 0,
 *         "total_unsubmitted_discussion_topics": 0
 *     },
 *     "todo_list": [
 *         {
 *             "section_id": 0,
 *             "unit_id": 0,
 *             "component_id": 0,
 *             "generated_from_lecture_content": false,
 *             "component_type": "assignment",
 *             "assignment_id": 718158,
 *             "title": "실습과제 1",
 *             "due_date": "2026-03-19T14:59:59Z"
 *         }
 *     ]
 * }
 */
@Serializable
data class Todo(
    val course_id: Int,
    val activities: Activity,
    val todo_list: List<TodoList>,
)
