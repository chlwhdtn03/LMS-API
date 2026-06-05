package io.github.chlwhdtn03.data.Lms

import kotlinx.serialization.Serializable

/**
 * {
 *     "id": "152510",
 *     "name": "기말고사",
 *     "position": 3,
 *     "group_weight": 45,
 *     "sis_source_id": "lms_final_exam",
 *     "integration_data": {},
 *     "rules": {},
 *     "assignments": [
 *         {
 *             "id": "710394",
 *             "due_at": "2025-12-06T08:10:00Z",
 *             "unlock_at": "2025-12-06T06:00:00Z",
 *             "lock_at": "2025-12-06T09:55:00Z",
 *             "points_possible": 100,
 *             "grading_type": "points",
 *             "assignment_group_id": "152510",
 *             "grading_standard_id": null,
 *             "created_at": "2025-12-06T02:13:20Z",
 *             "updated_at": "2026-01-05T06:05:09Z",
 *             "peer_reviews": false,
 *             "automatic_peer_reviews": false,
 *             "position": 2,
 *             "grade_group_students_individually": false,
 *             "anonymous_peer_reviews": false,
 *             "group_category_id": null,
 *             "post_to_sis": false,
 *             "moderated_grading": false,
 *             "omit_from_final_grade": false,
 *             "intra_group_peer_reviews": false,
 *             "anonymous_instructor_annotations": false,
 *             "anonymous_grading": false,
 *             "graders_anonymous_to_graders": false,
 *             "grader_count": 0,
 *             "grader_comments_visible_to_graders": true,
 *             "final_grader_id": null,
 *             "grader_names_visible_to_final_grader": true,
 *             "allowed_attempts": -1,
 *             "lock_info": {
 *                 "lock_at": "2025-12-06T09:55:00Z",
 *                 "can_view": true,
 *                 "asset_string": "assignment_710394"
 *             },
 *             "secure_params": "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJsdGlfYXNzaWdubWVudF9pZCI6ImJmYmQ2ODgxLTE1NjEtNGU5Yi05YjhmLWNjNmI5Mzc3ODIwZiJ9.vT9vO9MGzbnAtlo__3Bq8xtfkal2CSjBR0zBHFMRjRA",
 *             "course_id": "41100",
 *             "name": "기말고사",
 *             "submission_types": [
 *                 "online_upload"
 *             ],
 *             "has_submitted_submissions": true,
 *             "due_date_required": false,
 *             "max_name_length": 255,
 *             "is_quiz_assignment": false,
 *             "can_duplicate": true,
 *             "original_course_id": null,
 *             "original_assignment_id": null,
 *             "original_assignment_name": null,
 *             "original_quiz_id": null,
 *             "workflow_state": "published",
 *             "muted": false,
 *             "html_url": "https://canvas.ssu.ac.kr/courses/41100/assignments/710394",
 *             "published": true,
 *             "only_visible_to_overrides": false,
 *             "locked_for_user": true,
 *             "lock_explanation": "이 과제는 2025년 12월 6일 오후  6:55에 잠겨있습니다.",
 *             "submissions_download_url": "https://canvas.ssu.ac.kr/courses/41100/assignments/710394/submissions?zip=1",
 *             "post_manually": true,
 *             "anonymize_students": false,
 *             "require_lockdown_browser": false,
 *             "in_closed_grading_period": false
 *         }
 *     ],
 *     "any_assignment_in_closed_grading_period": false
 * }
 */
@Serializable
data class AssignmentGroup(
    val name: String? = "",
    val assignments: List<Assignment> = emptyList(),
)
