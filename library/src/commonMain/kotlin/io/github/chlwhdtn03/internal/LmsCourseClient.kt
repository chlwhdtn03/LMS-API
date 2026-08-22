package io.github.chlwhdtn03.internal

import io.github.chlwhdtn03.data.Lms.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlin.time.ExperimentalTime

/**
 * 수강 과목과 Todo 서비스가 공유하는 Canvas/LearningX HTTP 요청을 모읍니다.
 *
 * 상태와 비즈니스 로직은 갖지 않으며, 응답을 도메인 모델로 변환하는 역할만 담당합니다.
 */
@OptIn(ExperimentalTime::class)
internal class LmsCourseClient(
    private val client: HttpClient,
    private val bearerToken: () -> String,
) {
    suspend fun fetchLectures(term: Term): List<Lecture> {
        return client.get(
            canvasUrl("/learningx/api/v1/learn_activities/courses?term_ids[]=${term.id}"),
        ) {
            appendBearerToken()
        }.body()
    }

    suspend fun fetchLearnStatuses(term: Term): LearnStatuses {
        return client.get(
            canvasUrl(
                "/learningx/api/v1/learn_activities/learnstatus" +
                    "?term_ids=${term.id}&type=subsection",
            ),
        ) {
            appendBearerToken()
        }.body()
    }

    suspend fun fetchAndApplyAssignmentMetadata(
        courseId: Int,
        submissions: List<Submission>,
    ): Map<Int, CourseAssignmentMetadata> {
        if (submissions.isEmpty()) {
            return emptyMap()
        }

        val metadataById = fetchAssignmentMetadata(courseId)
        applyAssignmentMetadata(submissions, metadataById)
        return metadataById
    }

    suspend fun fetchAssignmentMetadata(
        courseId: Int,
    ): Map<Int, CourseAssignmentMetadata> {
        val groups = try {
            client.get(canvasUrl("/api/v1/courses/$courseId/assignment_groups")) {
                url {
                    parameters.append("exclude_response_fields[]", "description")
                    parameters.append("exclude_response_fields[]", "rubric")
                    parameters.append("include[]", "assignments")
                    parameters.append("include[]", "discussion_topic")
                    parameters.append("override_assignment_dates", "true")
                    parameters.append("per_page", "50")
                }
            }.body<List<AssignmentGroup>>()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }

        val metadataById = mutableMapOf<Int, CourseAssignmentMetadata>()
        for (group in groups) {
            for (assignment in group.assignments) {
                if (!metadataById.containsKey(assignment.id)) {
                    metadataById[assignment.id] = CourseAssignmentMetadata(
                        groupName = group.name,
                        name = assignment.name,
                        maxScore = assignment.points_possible ?: 0.0,
                    )
                }
            }
        }
        return metadataById
    }

    fun applyAssignmentMetadata(
        submissions: List<Submission>,
        metadataById: Map<Int, CourseAssignmentMetadata>,
    ) {
        for (submission in submissions) {
            val metadata = metadataById[submission.assignment_id] ?: continue
            submission.name = metadata.name
            submission.groupName = metadata.groupName
        }
    }

    suspend fun fetchAssignmentDetail(
        courseId: Int,
        assignmentId: Int,
    ): AssignmentDetail {
        return client
            .get(canvasUrl("/api/v1/courses/$courseId/assignments/$assignmentId"))
            .body()
    }

    suspend fun fetchTodoDetails(courseId: Int): List<TodoDetail> {
        return try {
            client.get(
                canvasUrl("/learningx/api/v1/courses/$courseId/modules?include_detail=true"),
            ) {
                appendBearerToken()
            }.body()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun fetchSubmissions(courseId: Int): Pair<List<Submission>, Boolean> {
        return try {
            val response = client.get(canvasUrl("/api/v1/courses/$courseId/students/submissions")) {
                parameter("per_page", "100")
            }
            response.body<List<Submission>>() to false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList<Submission>() to true
        }
    }

    suspend fun fetchDiscussions(courseId: Int): List<Discussion> {
        return try {
            client.get(
                canvasUrl(
                    "/api/v1/courses/$courseId/discussion_topics" +
                        "?only_announcements=true&per_page=40&page=1&filter_by=all" +
                        "&no_avatar_fallback=1&include[]=sections_user_count&include[]=sections",
                ),
            ) {
                headers {
                    append(HttpHeaders.Referrer, canvasUrl("/courses/$courseId/announcements"))
                }
            }.body()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun HttpRequestBuilder.appendBearerToken() {
        val token = bearerToken()
        if (token.isNotBlank()) {
            headers.append(HttpHeaders.Authorization, "Bearer $token")
        }
    }

    private fun canvasUrl(path: String): String {
        return if (path.startsWith("/")) "$CANVAS_BASE_URL$path" else "$CANVAS_BASE_URL/$path"
    }

    private companion object {
        const val CANVAS_BASE_URL = "https://canvas.ssu.ac.kr"
    }
}

internal data class CourseAssignmentMetadata(
    val groupName: String,
    val name: String,
    val maxScore: Double,
)
