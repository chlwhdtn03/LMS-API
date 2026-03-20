package io.github.chlwhdtn03.data

import kotlinx.serialization.Serializable

/**
 * [
 *     {
 *         "id": 45446, // LMS상 과목 ID
 *         "term_id": 46, // LMS상 학기 ID (26-1학기)
 *         "name": "리눅스시스템프로그래밍 (2150652502)", // 과목명(분반)
 *         "course_format": "on_campus", // 강의형식
 *         "course_format_custom_name": "오프라인", // 강의형식(한글)
 *         "professors": "홍지만", // 교수님
 *         "total_students": 48, // 총 수강생
 *         "is_observer": false, // ???
 *         "use_purecanvas": true, // ?????
 *         "enrolled_status": "active", // ?????
 *         "ended": false // 수강 종료 여부
 *     }, ...
 * ]
 */
@Serializable
data class Lecture(
    val id: Int,
    val term_id: Int,
    val name: String,
    val professors: String,
    val total_students: Int,
    val use_purecanvas: Boolean,
    val is_observer: Boolean,
    val enrolled_status: String,
    val ended: Boolean,
    val course_format_custom_name: String,
    val course_format: String,
)
