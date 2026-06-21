package io.github.chlwhdtn03.data.Lms

import kotlinx.serialization.Serializable

@Serializable
data class ChapelSeatStatusCell(
    val classGroup: String,      // 분반
    val timetable: String,       // 시간표
    val classroom: String,       // 강의실
    val seatNo: String,          // 좌석번호
    val absenceCount: String,    // 결석현황
    val gradeResult: String,     // 성적결과
    val rawValues: Map<String, String>
)

@Serializable
data class ChapelSeatStatusTable(
    val items: List<ChapelSeatStatusCell>
)

@Serializable
data class ChapelAttendanceCell(
    val classGroup: String,      // 분반
    val date: String,            // 수업일자
    val lectureType: String,     // 강의구분
    val status: String,          // 출결상황
    val rawValues: Map<String, String>
)

@Serializable
data class ChapelAttendanceTable(
    val items: List<ChapelAttendanceCell>
)

@Serializable
data class ChapelAbsenceCell(
    val year: String,            // 학년도
    val semester: String,        // 학기
    val detail: String,          // 결구분상세
    val rawValues: Map<String, String>
)

@Serializable
data class ChapelAbsenceTable(
    val items: List<ChapelAbsenceCell>
)

@Serializable
data class ChapelInformation(
    val year: String,
    val semester: Semester,
    val seatStatusTable: ChapelSeatStatusTable,
    val attendanceTable: ChapelAttendanceTable,
    val absenceTable: ChapelAbsenceTable
)
