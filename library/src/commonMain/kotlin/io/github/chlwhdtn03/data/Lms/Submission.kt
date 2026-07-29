package io.github.chlwhdtn03.data.Lms

import kotlinx.serialization.Serializable

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
 * }서
 */
@Serializable
data class Submission(
    val assignment_id: Int? = 0,
    val attachments: List<Attachment>? = emptyList(),
    val attempt: Int? = -1, // 제출 횟수
    val cached_due_date: String? = "", // LMS 캐시 상 마감일
    val late: Boolean? = false, // 지각 여부
    val preview_url: String? = "", // 제출한 파일 미리보기 주소
    val submitted_at: String? = "",
    val submission_type: String? = "",
    val workflow_state: String? = "",
    val score: Double? = Double.NEGATIVE_INFINITY,
    val url: String? = "",
) {
    var name: String = "알 수 없음" // 과제 이름
    var groupName: String = "알 수 없음" // 과제 대분류명
}
