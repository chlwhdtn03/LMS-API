package com.yourssu.lms.data

import kotlinx.serialization.Serializable

/**
 * {
 *   "course": {
 *     "id": 44383,
 *     "term_id": 46
 *   },
 *   "sections": [
 *     {
 *       "section_id": 1,
 *       "section_title": null,
 *       "position": 1,
 *       "is_upcoming": false,
 *       "subsections": [
 *         {
 *           "subsection_id": 1000001,
 *           "subsection_title": null,
 *           "position": 0,
 *           "status": "attendance"
 *         },
 *         {
 *           "subsection_id": 1000002,
 *           "subsection_title": null,
 *           "position": 1,
 *           "status": "attendance"
 *         }
 *       ]
 *     }
 *   ]
 * }
 */

@Serializable
data class LearnStatuses(
    val learnstatuses: List<LearnStatus>,
    val total_count: Int,
)

@Serializable
data class LearnStatus(
    val course: Course,
    val sections: List<Section>,
)
@Serializable
data class Section(
    val section_id: Int,
    val section_title: String? = "",
    val position: Int,
    val is_upcoming : Boolean,
    val subsections: List<SubSection>,
)
@Serializable
data class SubSection(
    val subsection_id: Int,
    val subsection_title: String? = "",
    val position: Int,
    val status: String,
)
@Serializable
data class Course(
    val id: Int,
    val term_id: Int,
)
