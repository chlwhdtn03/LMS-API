package io.github.chlwhdtn03.data

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
    val name: String = "",
    val assignments: List<Assignment> = emptyList(),
)

@Serializable
data class Assignment(
    val id: Int,
    val points_possible: Double,
    val name: String,
    val due_at: String? = "",
)

/**
 * {
 *   "id": "24147765",
 *   "body": null,
 *   "url": null,
 *   "grade": "80",
 *   "score": 80,
 *   "submitted_at": "2025-12-06T07:58:06Z",
 *   "assignment_id": "710394",
 *   "user_id": "37571",
 *   "submission_type": "online_upload",
 *   "workflow_state": "graded",
 *   "grade_matches_current_submission": true,
 *   "graded_at": "2025-12-12T08:13:54Z",
 *   "grader_id": "431",
 *   "attempt": 1,
 *   "cached_due_date": "2025-12-06T08:10:00Z",
 *   "excused": false,
 *   "late_policy_status": null,
 *   "points_deducted": null,
 *   "grading_period_id": null,
 *   "extra_attempts": null,
 *   "posted_at": "2025-12-12T08:19:52Z",
 *   "late": false,
 *   "missing": false,
 *   "seconds_late": 0,
 *   "entered_grade": "80",
 *   "entered_score": 80,
 *   "preview_url": "https://canvas.ssu.ac.kr/courses/41100/assignments/710394/submissions/37571?preview=1&version=1",
 *   "has_originality_report": true,
 *   "turnitin_data": {
 *     "eula_agreement_timestamp": "1765007879915",
 *     "attachment_4129085": {
 *       "similarity_score": null,
 *       "state": "error",
 *       "report_url": "https://canvas.copykiller.co.kr/view/",
 *       "status": "error",
 *       "error_message": "검사불가[텍스트 추출이 불가능한 문서입니다. 파일일 경우 다른 확장자로 변환 후 검사해 주세요. , code: -256]"
 *     }
 *   },
 *   "attachments": [
 *     {
 *       "id": "4129085",
 *       "uuid": "PtXfRECrKjuqmTc8F57iE03mfcdmPo7G9gngLIVW",
 *       "folder_id": "746894",
 *       "display_name": "FinalChoiJongSu20222908.zip",
 *       "filename": "1765007885_465__FinalChoiJongSu20222908.zip",
 *       "upload_status": "success",
 *       "content-type": "application/x-zip-compressed",
 *       "url": "https://canvas.ssu.ac.kr/files/4129085/download?download_frd=1&verifier=PtXfRECrKjuqmTc8F57iE03mfcdmPo7G9gngLIVW",
 *       "size": 13784098,
 *       "created_at": "2025-12-06T07:58:00Z",
 *       "updated_at": "2025-12-06T07:58:06Z",
 *       "unlock_at": null,
 *       "locked": false,
 *       "hidden": false,
 *       "lock_at": null,
 *       "hidden_for_user": false,
 *       "thumbnail_url": null,
 *       "modified_at": "2025-12-06T07:58:00Z",
 *       "mime_class": "zip",
 *       "media_entry_id": null,
 *       "locked_for_user": false,
 *       "preview_url": null
 *     }
 *   ]
 * }
 */
@Serializable
data class Submission(
    val assignment_id: Int,
    val score: Double = Double.MIN_VALUE,
)

@Serializable
data class ScoredAssignment(
    val groupName: String,
    val name: String,
    val score: Double,
    val maxScore: Double,
)