package io.github.chlwhdtn03.internal

import io.github.chlwhdtn03.data.Lms.*
import kotlin.time.ExperimentalTime

/** 전체 수강 과목 정보와 출석·공지·점수 조합을 담당합니다. */
@OptIn(ExperimentalTime::class)
internal class LmsCourseService(
    private val courseClient: LmsCourseClient,
    private val todoService: TodoService,
    private val ensureLoggedIn: () -> Unit,
) {
    suspend fun getSubjects(
        term: Term,
        loadingState: (Float) -> Unit = {},
    ): List<Subject> {
        ensureLoggedIn()

        val lectures = courseClient.fetchLectures(term)
        loadingState(0.1f)
        val learnStatuses = courseClient.fetchLearnStatuses(term)
        loadingState(0.2f)
        loadingState(0.3f)

        val learnStatusByCourseId = learnStatuses
            .learnstatuses
            .associateFirstById { it.course.id }
        val weight = if (lectures.isEmpty()) 0f else 0.7f / lectures.size
        var progress = 0.3f
        val subjects = mutableListOf<Subject>()

        for (lecture in lectures) {
            progress += weight
            loadingState(progress)

            val (submissions, permissionFailed) = courseClient.fetchSubmissions(lecture.id)
            val assignmentMetadataById = courseClient.fetchAndApplyAssignmentMetadata(
                courseId = lecture.id,
                submissions = submissions,
            )
            val todoResult = todoService.buildCourseTodo(
                courseId = lecture.id,
                submissions = submissions,
                includeCommons = true,
            )

            subjects += Subject(
                id = lecture.id,
                termId = lecture.term_id,
                termName = term.name ?: "학기정보 없음",
                name = lecture.name,
                professor = lecture.professors,
                totalStudents = lecture.total_students,
                todoList = todoResult.todoList,
                attendances = if (!permissionFailed) {
                    learnStatusByCourseId[lecture.id]?.sections?.map { section ->
                        section.subsections.map { subsection ->
                            when (subsection.status) {
                                "attendance" -> AttendanceType.ATTENDANCE
                                "absent" -> AttendanceType.ABSENT
                                "late" -> AttendanceType.LATE
                                else -> AttendanceType.NONE
                            }
                        }
                    } ?: emptyList()
                } else {
                    emptyList()
                },
                discussions = if (!permissionFailed) {
                    courseClient.fetchDiscussions(lecture.id)
                } else {
                    emptyList()
                },
                submissions = submissions + todoResult.completedCommonsSubmissions,
                scoredAssignments = if (!permissionFailed) {
                    buildScoredAssignments(submissions, assignmentMetadataById)
                } else {
                    emptyList()
                },
                permissionFailed = permissionFailed,
            )
        }
        return subjects
    }

    private inline fun <T> Iterable<T>.associateFirstById(
        keySelector: (T) -> Int,
    ): Map<Int, T> {
        val result = mutableMapOf<Int, T>()
        for (item in this) {
            val key = keySelector(item)
            if (!result.containsKey(key)) {
                result[key] = item
            }
        }
        return result
    }

    private fun buildScoredAssignments(
        submissions: List<Submission>,
        metadataById: Map<Int, CourseAssignmentMetadata>,
    ): List<ScoredAssignment> {
        val result = mutableListOf<ScoredAssignment>()
        for (submission in submissions) {
            if (submission.score != null && !(submission.score > Double.NEGATIVE_INFINITY)) {
                continue
            }
            val metadata = metadataById[submission.assignment_id]
            result += ScoredAssignment(
                groupName = metadata?.groupName ?: "알 수 없음",
                name = metadata?.name ?: "알 수 없음",
                score = submission.score ?: 0.0,
                maxScore = metadata?.maxScore ?: 0.0,
            )
        }
        return result
    }
}
