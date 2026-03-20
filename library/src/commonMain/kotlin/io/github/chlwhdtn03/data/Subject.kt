package io.github.chlwhdtn03.data

import kotlinx.serialization.Serializable

@Serializable
data class Subject(
    val id: Int, // 과목 ID
    val termId: Int, // 학기 ID
    val termName: String, // 학기명(한글)
    val name: String, // 과목 이름(한글)
    val professor: String, // 교수명
    val totalStudents: Int, // 수강인원

    val todoList: List<TodoList>, // 과제정보
    val attendances: List<List<AttendanceType>> // 주간 출석정보 attendances[n+1주차][n+1번째 수업]
)
