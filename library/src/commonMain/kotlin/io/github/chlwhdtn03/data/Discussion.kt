package io.github.chlwhdtn03.data

import kotlinx.serialization.Serializable

/**
 * {
 *     "id": "205809",
 *     "title": "강의계획서 필독 후 구글클래스룸 가입해야 함. ",
 *     "last_reply_at": "2026-03-04T15:26:13Z",
 *     "created_at": "2026-03-04T15:26:13Z",
 *     "delayed_post_at": null,
 *     "posted_at": "2026-03-04T15:26:13Z",
 *     "assignment_id": null,
 *     "root_topic_id": null,
 *     "position": 1,
 *     "podcast_has_student_posts": false,
 *     "discussion_type": "side_comment",
 *     "lock_at": null,
 *     "allow_rating": false,
 *     "only_graders_can_rate": false,
 *     "sort_by_rating": false,
 *     "is_section_specific": false,
 *     "user_name": "홍지만",
 *     "discussion_subentry_count": 0,
 *     "permissions": {
 *         "attach": false,
 *         "update": false,
 *         "reply": false,
 *         "delete": false
 *     },
 *     "require_initial_post": null,
 *     "user_can_see_posts": true,
 *     "podcast_url": null,
 *     "read_state": "unread",
 *     "unread_count": 0,
 *     "subscribed": false,
 *     "attachments": [
 *         {
 *             "id": "4228188",
 *             "uuid": "mcU1P4jDlM2OuqVN2Oep5UT7tcB5bEohgc58yW89",
 *             "folder_id": "763291",
 *             "display_name": "2026_LSP_syllabus_V0.991.pdf",
 *             "filename": "2026_LSP_syllabus_V0.991.pdf",
 *             "upload_status": "success",
 *             "content-type": "application/pdf",
 *             "url": "https://canvas.ssu.ac.kr/files/4228188/download?download_frd=1&verifier=mcU1P4jDlM2OuqVN2Oep5UT7tcB5bEohgc58yW89",
 *             "size": 274360,
 *             "created_at": "2026-03-04T15:26:13Z",
 *             "updated_at": "2026-03-04T15:26:13Z",
 *             "unlock_at": null,
 *             "locked": false,
 *             "hidden": false,
 *             "lock_at": null,
 *             "hidden_for_user": false,
 *             "thumbnail_url": null,
 *             "modified_at": "2026-03-04T15:26:13Z",
 *             "mime_class": "pdf",
 *             "media_entry_id": null,
 *             "locked_for_user": false
 *         }
 *     ],
 *     "published": true,
 *     "can_unpublish": false,
 *     "locked": true,
 *     "can_lock": true,
 *     "comments_disabled": false,
 *     "author": {
 *         "id": "1182",
 *         "display_name": "홍지만",
 *         "avatar_image_url": null,
 *         "html_url": "https://canvas.ssu.ac.kr/courses/45446/users/1182",
 *         "pronouns": null
 *     },
 *     "html_url": "https://canvas.ssu.ac.kr/courses/45446/discussion_topics/205809",
 *     "url": "https://canvas.ssu.ac.kr/courses/45446/discussion_topics/205809",
 *     "pinned": false,
 *     "group_category_id": null,
 *     "can_group": false,
 *     "topic_children": [],
 *     "group_topic_children": [],
 *     "locked_for_user": true,
 *     "lock_info": {
 *         "can_view": true,
 *         "asset_string": "discussion_topic_205809"
 *     },
 *     "lock_explanation": "이 주제는 댓글로 마감되었습니다.",
 *     "message": "<p>강의계획서 필독 후 구글클래스룸 가입해야 함. <br>구글클래스룸을 통해 과제 명세, 공지사항, 강의 요약 등을 게시할 것이며 설계과제도 구글클래스룸을 통해 제출해야 함. </p>",
 *     "subscription_hold": "topic_is_announcement",
 *     "user_count": 49,
 *     "todo_date": null
 * }
 */
@Serializable
data class Discussion(
    val id: Int,
    val title: String,
    val message: String,
    val url: String,
    val published: Boolean,
    val read_state: String,
    val created_at: String,
    val discussion_type: String,
    val attachments: List<Attachment>,
    val user_name: String?, //작성자

)
