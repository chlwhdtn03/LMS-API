package com.yourssu.lms.data

import kotlinx.serialization.Serializable

/**
 * {
 *     "id": "4228188",
 *     "uuid": "mcU1P4jDlM2OuqVN2Oep5UT7tcB5bEohgc58yW89",
 *     "folder_id": "763291",
 *     "display_name": "2026_LSP_syllabus_V0.991.pdf",
 *     "filename": "2026_LSP_syllabus_V0.991.pdf",
 *     "upload_status": "success",
 *     "content-type": "application/pdf",
 *     "url": "https://canvas.ssu.ac.kr/files/4228188/download?download_frd=1&verifier=mcU1P4jDlM2OuqVN2Oep5UT7tcB5bEohgc58yW89",
 *     "size": 274360,
 *     "created_at": "2026-03-04T15:26:13Z",
 *     "updated_at": "2026-03-04T15:26:13Z",
 *     "unlock_at": null,
 *     "locked": false,
 *     "hidden": false,
 *     "lock_at": null,
 *     "hidden_for_user": false,
 *     "thumbnail_url": null,
 *     "modified_at": "2026-03-04T15:26:13Z",
 *     "mime_class": "pdf",
 *     "media_entry_id": null,
 *     "locked_for_user": false
 * }
 */
@Serializable
data class Attachment(
    val id: Int,
    val uuid: String,
    val folder_id: String,
    val display_name: String,
    val file_name: String? = "",
    val content_type: String? = "",
    val url: String,
    val size: Long,
    val thumbnail_url: String? = "",
    val created_at: String,
    val updated_at: String,
    val modified_at: String,
    val mime_class: String,

    )
