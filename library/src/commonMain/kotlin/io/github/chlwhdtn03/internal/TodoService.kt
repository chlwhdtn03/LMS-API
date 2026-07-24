package io.github.chlwhdtn03.internal

import io.github.chlwhdtn03.LmsApi
import io.github.chlwhdtn03.TODO_SNAPSHOT_SAMPLE_RATE
import io.github.chlwhdtn03.data.Lms.*
import io.github.chlwhdtn03.shouldSendTodoSnapshot
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Todo 조회, 미제출 통계 계산, Todo 동기화 분석 전송을 담당합니다.
 *
 * 일별 전송 이력 캐시는 [LmsApi][io.github.chlwhdtn03.LmsApi]가 소유한 Map을 사용합니다.
 */
@OptIn(ExperimentalTime::class)
internal class TodoService(
    private val client: HttpClient,
    private val courseClient: LmsCourseClient,
    private val backgroundScope: CoroutineScope,
    private val trackedSnapshotDates: MutableMap<String, String>,
    private val ensureLoggedIn: () -> Unit,
) {
    suspend fun getTodoList(
        term: Term,
        loadingState: (Float) -> Unit = {},
        postHogDistinctId: String? = null,
    ): List<Subject> {
        ensureLoggedIn()

        val lectures = courseClient.fetchLectures(term)
        loadingState(0.1f)
        loadingState(0.2f)
        loadingState(0.3f)

        val weight = if (lectures.isEmpty()) 0f else 0.7f / lectures.size
        var progress = 0.3f
        val now = Clock.System.now()
        val subjects = mutableListOf<Subject>()
        var totalCount = 0
        var unsubmittedCount = 0
        val shouldTrackPostHog = !postHogDistinctId.isNullOrBlank()
        val trackingItems = mutableListOf<TodoTrackingItem>()

        for (lecture in lectures) {
            progress += weight
            loadingState(progress)

            val (submissions, permissionFailed) = courseClient.fetchSubmissions(lecture.id)
            courseClient.fetchAndApplyAssignmentMetadata(lecture.id, submissions)
            val includeCommons = lecture.activities.mayHaveCommonsTodos()

            val submissionTrackingItems = submissions.toSubmissionTrackingItems(
                courseId = lecture.id,
                now = now,
            )
            val submissionStats = submissionTrackingItems.toUnsubmittedStats()
            totalCount += submissionStats.totalCount
            unsubmittedCount += submissionStats.unsubmittedCount
            if (shouldTrackPostHog) {
                trackingItems += submissionTrackingItems
            }

            if (
                !lecture.activities.mayHaveTodoAssignments() &&
                !includeCommons &&
                submissions.isEmpty()
            ) {
                continue
            }

            val todoResult = buildCourseTodo(
                courseId = lecture.id,
                submissions = submissions,
                includeCommons = includeCommons,
                includeCommonsForTracking = shouldTrackPostHog,
            )
            totalCount += todoResult.commonsStats.totalCount
            unsubmittedCount += todoResult.commonsStats.unsubmittedCount
            if (shouldTrackPostHog) {
                trackingItems += todoResult.commonsTrackingItems
            }

            subjects += Subject(
                id = lecture.id,
                termId = lecture.term_id,
                termName = term.name ?: "학기정보 없음",
                name = lecture.name,
                professor = lecture.professors,
                totalStudents = lecture.total_students,
                todoList = todoResult.todoList,
                attendances = emptyList(),
                discussions = emptyList(),
                submissions = submissions + todoResult.completedCommonsSubmissions,
                scoredAssignments = emptyList(),
                permissionFailed = permissionFailed,
            )
        }

        trackTodoSync(
            stats = LmsApi.UnsubmittedStats(
                totalCount = totalCount,
                unsubmittedCount = unsubmittedCount,
            ),
            items = trackingItems,
            postHogDistinctId = postHogDistinctId,
        )
        return subjects
    }

    suspend fun getUnsubmittedRatioStats(
        term: Term,
        loadingState: (Float) -> Unit = {},
    ): LmsApi.UnsubmittedStats {
        ensureLoggedIn()
        val lectures = courseClient.fetchLectures(term)
        loadingState(0.1f)

        val weight = if (lectures.isEmpty()) 0f else 0.9f / lectures.size
        var progress = 0.1f
        val now = Clock.System.now()
        var totalCount = 0
        var unsubmittedCount = 0

        for (lecture in lectures) {
            progress += weight
            loadingState(progress)

            val submissionStats = courseClient
                .fetchSubmissions(lecture.id)
                .first
                .toUnsubmittedStats(now)
            totalCount += submissionStats.totalCount
            unsubmittedCount += submissionStats.unsubmittedCount

            val commonsStats = courseClient
                .fetchTodoDetails(lecture.id)
                .toCommonsUnsubmittedStats(now)
            totalCount += commonsStats.totalCount
            unsubmittedCount += commonsStats.unsubmittedCount
        }

        loadingState(1f)
        return LmsApi.UnsubmittedStats(
            totalCount = totalCount,
            unsubmittedCount = unsubmittedCount,
        )
    }

    suspend fun buildCourseTodo(
        courseId: Int,
        submissions: List<Submission>,
        includeCommons: Boolean,
        includeCommonsForTracking: Boolean = false,
    ): TodoBuildResult {
        val now = Clock.System.now()
        val seenAssignmentIds = mutableSetOf<Int>()
        val todoList = mutableListOf<TodoList>()
        var commonsStats = LmsApi.UnsubmittedStats()
        var commonsTrackingItems = emptyList<TodoTrackingItem>()
        var completedCommonsSubmissions = emptyList<Submission>()

        for (submission in submissions) {
            val assignmentId = submission.assignment_id?.takeIf { it > 0 } ?: continue
            if (!seenAssignmentIds.add(assignmentId)) continue
            if (submission.isCompletedForTodo()) continue
            if (!submission.cached_due_date.isFutureInstant(now)) continue

            val detail = courseClient.fetchAssignmentDetail(courseId, assignmentId)
            val dueDate = submission.cached_due_date.orFallback(detail.due_at.orEmpty())
            todoList += TodoList(
                section_id = 0,
                unit_id = 0,
                component_id = 0,
                generated_from_lecture_content = false,
                component_type = when (detail.submission_types?.first().orEmpty()) {
                    "online_quiz" -> "quiz"
                    else -> "assignment"
                },
                assignment_id = assignmentId,
                title = detail.name.orFallback(submission.name),
                due_date = dueDate,
                late_at = detail.late_at.orFallback(detail.lock_at.orEmpty()),
                description = detail.description,
            )
        }

        if (includeCommons || includeCommonsForTracking) {
            val todoDetails = courseClient.fetchTodoDetails(courseId)
            commonsTrackingItems = todoDetails.toCommonsTrackingItems(courseId, now)
            commonsStats = commonsTrackingItems.toUnsubmittedStats()
            completedCommonsSubmissions = todoDetails.toCompletedCommonsSubmissions()
            if (includeCommons) {
                todoList += todoDetails.toCommonsTodoList(now)
            }
        }

        return TodoBuildResult(
            todoList = todoList.sortedBy { it.due_date },
            commonsStats = commonsStats,
            commonsTrackingItems = commonsTrackingItems,
            completedCommonsSubmissions = completedCommonsSubmissions,
        )
    }

    private fun Submission.isCompletedForTodo(): Boolean {
        return !submitted_at.isNullOrBlank() ||
            workflow_state == "submitted" ||
            workflow_state == "graded"
    }

    private fun Submission.isOverdueUnsubmitted(now: Instant): Boolean {
        if (workflow_state != "unsubmitted") return false
        if (late == true) return true
        return cached_due_date.isPastOrCurrentInstant(now)
    }

    private fun List<Submission>.toUnsubmittedStats(now: Instant): LmsApi.UnsubmittedStats {
        val seenAssignmentIds = mutableSetOf<Int>()
        var totalCount = 0
        var unsubmittedCount = 0
        for (submission in this) {
            val assignmentId = submission.assignment_id?.takeIf { it > 0 } ?: continue
            if (!seenAssignmentIds.add(assignmentId)) continue
            totalCount += 1
            if (submission.isOverdueUnsubmitted(now)) {
                unsubmittedCount += 1
            }
        }
        return LmsApi.UnsubmittedStats(totalCount, unsubmittedCount)
    }

    private fun List<Submission>.toSubmissionTrackingItems(
        courseId: Int,
        now: Instant,
    ): List<TodoTrackingItem> {
        val seenAssignmentIds = mutableSetOf<Int>()
        val result = mutableListOf<TodoTrackingItem>()
        for (submission in this) {
            val assignmentId = submission.assignment_id?.takeIf { it > 0 } ?: continue
            if (!seenAssignmentIds.add(assignmentId)) continue
            result += TodoTrackingItem(
                itemKey = "submission:$courseId:$assignmentId",
                itemType = "submission",
                courseId = courseId,
                dueAt = submission.cached_due_date.orEmpty(),
                isCompleted = submission.isCompletedForTodo(),
                isOverdueUnsubmitted = submission.isOverdueUnsubmitted(now),
                workflowState = submission.workflow_state,
                late = submission.late,
            )
        }
        return result
    }

    private fun List<TodoTrackingItem>.toUnsubmittedStats(): LmsApi.UnsubmittedStats {
        return LmsApi.UnsubmittedStats(
            totalCount = size,
            unsubmittedCount = count { it.isOverdueUnsubmitted },
        )
    }

    private fun Activity?.mayHaveTodoAssignments(): Boolean {
        return this == null || total_unsubmitted_assignments > 0
    }

    private fun Activity?.mayHaveCommonsTodos(): Boolean {
        return this == null ||
            total_incompleted_commons_resources > 0 ||
            total_incompleted_movies > 0 ||
            total_incompleted_video_conferences > 0 ||
            total_incompleted_metaverse_conferences > 0
    }

    private fun String?.isFutureInstant(now: Instant): Boolean {
        val value = takeUnless { it.isNullOrBlank() } ?: return false
        val dueDate = runCatching { Instant.parse(value) }.getOrNull() ?: return false
        return dueDate > now
    }

    private fun String?.isPastOrCurrentInstant(now: Instant): Boolean {
        val value = takeUnless { it.isNullOrBlank() } ?: return false
        val dueDate = runCatching { Instant.parse(value) }.getOrNull() ?: return false
        return dueDate <= now
    }

    private fun String?.orFallback(fallback: String): String {
        return takeUnless { it.isNullOrBlank() } ?: fallback
    }

    private fun List<TodoDetail>.toCommonsTodoList(now: Instant): List<TodoList> {
        val result = mutableListOf<TodoList>()
        for (module in this) {
            for (item in module.module_items.orEmpty()) {
                val contentData = item.content_data ?: continue
                val contentType = contentData.item_content_type ?: continue
                if (contentType != "commons") continue
                if (contentData.item_content_data?.duration == null) continue
                if (contentData.use_attendance == false) continue
                if (item.completed == true) continue
                if (!contentData.due_at.isFutureInstant(now)) continue

                result += TodoList(
                    section_id = 0,
                    unit_id = 0,
                    component_id = contentData.item_id ?: item.content_id ?: 0,
                    generated_from_lecture_content = false,
                    component_type = contentType,
                    assignment_id = -1,
                    title = contentData.title.orFallback(item.title.orEmpty()),
                    due_date = contentData.due_at.orEmpty(),
                    late_at = contentData.late_at.orEmpty(),
                    description = contentData.description,
                )
            }
        }
        return result
    }

    private fun List<TodoDetail>.toCommonsUnsubmittedStats(
        now: Instant,
    ): LmsApi.UnsubmittedStats {
        return toCommonsTrackingItems(courseId = 0, now = now).toUnsubmittedStats()
    }

    private fun List<TodoDetail>.toCommonsTrackingItems(
        courseId: Int,
        now: Instant,
    ): List<TodoTrackingItem> {
        val seenItemIds = mutableSetOf<Int>()
        val result = mutableListOf<TodoTrackingItem>()
        for (module in this) {
            for (item in module.module_items.orEmpty()) {
                val contentData = item.content_data ?: continue
                val contentType = contentData.item_content_type ?: continue
                if (contentType != "commons") continue
                if (contentData.item_content_data?.duration == null) continue
                if (contentData.use_attendance == false) continue

                val itemId = contentData.item_id?.takeIf { it > 0 }
                    ?: item.content_id?.takeIf { it > 0 }
                    ?: item.module_item_id?.takeIf { it > 0 }
                    ?: continue
                if (!seenItemIds.add(itemId)) continue

                result += TodoTrackingItem(
                    itemKey = "commons:$courseId:$itemId",
                    itemType = "commons",
                    courseId = courseId,
                    dueAt = contentData.due_at.orEmpty(),
                    isCompleted = item.completed == true,
                    isOverdueUnsubmitted =
                        item.completed != true && contentData.due_at.isPastOrCurrentInstant(now),
                )
            }
        }
        return result
    }

    private fun List<TodoDetail>.toCompletedCommonsSubmissions(): List<Submission> {
        val seenItemIds = mutableSetOf<Int>()
        val result = mutableListOf<Submission>()
        for (module in this) {
            for (item in module.module_items.orEmpty()) {
                val contentData = item.content_data ?: continue
                val contentType = contentData.item_content_type ?: continue
                if (contentType != "commons") continue
                if (contentData.item_content_data?.duration == null) continue
                if (contentData.use_attendance == false) continue
                if (item.completed != true) continue

                val itemId = contentData.item_id?.takeIf { it > 0 }
                    ?: item.content_id?.takeIf { it > 0 }
                    ?: item.module_item_id?.takeIf { it > 0 }
                    ?: continue
                if (!seenItemIds.add(itemId)) continue

                result += Submission(
                    assignment_id = itemId,
                    cached_due_date = contentData.due_at,
                    late = false,
                    submitted_at = "",
                    submission_type = contentType,
                    workflow_state = "submitted",
                ).apply {
                    name = contentData.title.orFallback(item.title.orEmpty())
                    groupName = module.title.orFallback("동영상")
                }
            }
        }
        return result
    }

    private fun trackTodoSync(
        stats: LmsApi.UnsubmittedStats,
        items: List<TodoTrackingItem>,
        postHogDistinctId: String?,
    ) {
        val distinctId = postHogDistinctId?.trim()?.takeIf { it.isNotBlank() } ?: return
        val now = Clock.System.now().toString()
        val today = now.substringBefore('T')
        if (trackedSnapshotDates[distinctId] == today) return
        trackedSnapshotDates[distinctId] = today

        val syncId = "todo_sync:${Random.nextLong()}:$now"
        if (!shouldSendTodoSnapshot()) return

        val events = listOf(
            PostHogBatchEvent(
                event = POSTHOG_IDENTIFY_EVENT,
                properties = buildJsonObject {
                    put("distinct_id", distinctId)
                    put("\$set", buildJsonObject {
                        put("last_todo_sync_at", now)
                    })
                    put("\$set_once", buildJsonObject {
                        put("initial_at", now)
                        put("initial_todo_sync_at", now)
                        put("initial_total_count", stats.totalCount)
                        put("initial_unsubmitted_count", stats.unsubmittedCount)
                        put("initial_unsubmitted_ratio", stats.ratio)
                    })
                },
                timestamp = now,
            ),
            PostHogBatchEvent(
                event = POSTHOG_TODO_SNAPSHOT_EVENT,
                properties = buildJsonObject {
                    put("distinct_id", distinctId)
                    put("sync_id", syncId)
                    put("synced_at", now)
                    put("snapshot_sample_rate", TODO_SNAPSHOT_SAMPLE_RATE)
                    put("snapshot_total_count", stats.totalCount)
                    put("snapshot_unsubmitted_count", stats.unsubmittedCount)
                    put("snapshot_unsubmitted_ratio", stats.ratio)
                    put("item_keys", buildJsonArray {
                        items.forEach { add(JsonPrimitive(it.itemKey)) }
                    })
                    put("overdue_unsubmitted_item_keys", buildJsonArray {
                        items.filter { it.isOverdueUnsubmitted }
                            .forEach { add(JsonPrimitive(it.itemKey)) }
                    })
                    put("items", buildJsonArray {
                        for (item in items) {
                            add(buildJsonObject {
                                put("item_key", item.itemKey)
                                put("item_type", item.itemType)
                                put("course_id", item.courseId)
                                put("due_at", item.dueAt)
                                put("is_completed", item.isCompleted)
                                put("is_overdue_unsubmitted", item.isOverdueUnsubmitted)
                                item.workflowState?.let { put("workflow_state", it) }
                                item.late?.let { put("late", it) }
                            })
                        }
                    })
                },
                timestamp = now,
            ),
        )

        backgroundScope.launch {
            runCatching {
                client.post(POSTHOG_BATCH_URL) {
                    contentType(ContentType.Application.Json)
                    setBody(PostHogBatchRequest(POSTHOG_PROJECT_API_KEY, events))
                }
            }
        }
    }

    @Serializable
    private data class PostHogBatchRequest(
        @SerialName("api_key")
        val apiKey: String,
        val batch: List<PostHogBatchEvent>,
    )

    @Serializable
    private data class PostHogBatchEvent(
        val event: String,
        val properties: JsonObject,
        val timestamp: String,
    )

    private companion object {
        const val POSTHOG_PROJECT_API_KEY =
            "phc_o6q2pUmTRryWQ6Np5HkqLA2q4d6jdR6mVhGf5bqaKgtT"
        const val POSTHOG_BATCH_URL = "https://us.i.posthog.com/batch/"
        const val POSTHOG_IDENTIFY_EVENT = "\$identify"
        const val POSTHOG_TODO_SNAPSHOT_EVENT = "todo_snapshot"
    }
}

internal data class TodoTrackingItem(
    val itemKey: String,
    val itemType: String,
    val courseId: Int,
    val dueAt: String,
    val isCompleted: Boolean,
    val isOverdueUnsubmitted: Boolean,
    val workflowState: String? = null,
    val late: Boolean? = null,
)

internal data class TodoBuildResult(
    val todoList: List<TodoList>,
    val commonsStats: LmsApi.UnsubmittedStats = LmsApi.UnsubmittedStats(),
    val commonsTrackingItems: List<TodoTrackingItem> = emptyList(),
    val completedCommonsSubmissions: List<Submission> = emptyList(),
)
