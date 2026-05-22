package io.github.chlwhdtn03.data.Lms

import kotlinx.serialization.Serializable

@Serializable
data class Activity(
    val total_unread_announcements: Int,
    val total_announcements: Int,
    val total_unread_resources: Int,
    val total_resources: Int,
    val total_incompleted_video_conferences: Int,
    val total_incompleted_metaverse_conferences: Int,
    val total_incompleted_commons_resources: Int,
    val total_incompleted_smart_attendances: Int,
    val total_incompleted_movies: Int,
    val total_unsubmitted_assignments: Int,
)
