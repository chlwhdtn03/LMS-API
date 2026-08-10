package io.github.chlwhdtn03.internal

import io.github.chlwhdtn03.data.Lms.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

internal data class ParallelCourseRequests(
    val lecture: Lecture,
    val submissions: Deferred<Pair<List<Submission>, Boolean>>,
    val metadata: Deferred<Map<Int, CourseAssignmentMetadata>>,
    val todoDetails: Deferred<List<TodoDetail>>,
    val discussions: Deferred<Result<List<Discussion>>>,
)

internal fun CoroutineScope.prefetchCourseRequests(
    courseClient: LmsCourseClient,
    lecture: Lecture,
): ParallelCourseRequests {
    return ParallelCourseRequests(
        lecture = lecture,
        submissions = async { courseClient.fetchSubmissions(lecture.id) },
        metadata = async { courseClient.fetchAssignmentMetadata(lecture.id) },
        todoDetails = async { courseClient.fetchTodoDetails(lecture.id) },
        discussions = async { runCatching { courseClient.fetchDiscussions(lecture.id) } },
    )
}

internal inline fun <T> Iterable<T>.associateFirstById(
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

internal fun LearnStatus?.toAttendances(permissionFailed: Boolean): List<List<AttendanceType>> {
    if (permissionFailed) return emptyList()
    return this?.sections?.map { section ->
        section.subsections.map { subsection ->
            when (subsection.status) {
                "attendance" -> AttendanceType.ATTENDANCE
                "absent" -> AttendanceType.ABSENT
                "late" -> AttendanceType.LATE
                else -> AttendanceType.NONE
            }
        }
    } ?: emptyList()
}
