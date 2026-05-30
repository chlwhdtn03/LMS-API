package io.github.chlwhdtn03.data.Lms

import kotlinx.serialization.Serializable

@Serializable
data class Activity(
    val total_unread_announcements: Int = 0,
    val total_announcements: Int = 0,
    val total_unread_resources: Int = 0,
    val total_resources: Int = 0,
    val total_incompleted_video_conferences: Int = 0,
    val total_incompleted_metaverse_conferences: Int = 0,
    val total_incompleted_commons_resources: Int = 0,
    val total_incompleted_smart_attendances: Int = 0,
    val total_incompleted_movies: Int = 0,
    val total_unsubmitted_assignments: Int = 0,
)
