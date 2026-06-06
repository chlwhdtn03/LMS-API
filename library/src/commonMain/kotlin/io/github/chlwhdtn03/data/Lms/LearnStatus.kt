package io.github.chlwhdtn03.data.Lms

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
data class LearnStatus(
    val course: Course,
    val sections: List<Section>,
)
