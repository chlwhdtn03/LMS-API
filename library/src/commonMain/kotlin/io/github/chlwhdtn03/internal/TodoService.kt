package io.github.chlwhdtn03.internal

import io.github.chlwhdtn03.LmsApi
import io.github.chlwhdtn03.data.Lms.*
import io.ktor.client.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
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
    private val requestMutex = Mutex()

    suspend fun getTodoList(
        term: Term,
        loadingState: (Float) -> Unit = {},
        postHogDistinctId: String? = null,
    ): List<Subject> = requestMutex.withLock {
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

            val submissionTrackingItems = if (shouldTrackPostHog) {
                submissions.toSubmissionTrackingItems(
                    courseId = lecture.id,
                    now = now,
                )
            } else {
                emptyList()
            }
            val submissionStats = if (shouldTrackPostHog) {
                submissionTrackingItems.toUnsubmittedStats()
            } else {
                submissions.toUnsubmittedStats(now)
            }
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
                discussions = if (!permissionFailed) {
                    courseClient.fetchDiscussions(lecture.id)
                } else {
                    emptyList()
                },
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

    /**
     * 로그인 이후 과목별 Todo 요청을 병렬로 수행하는 [getTodoList]의 비교용 경로입니다.
     * 출석과 공지까지 포함하며, 반환 순서는 LMS의 수강 과목 순서를 유지합니다.
     */
    suspend fun getTodoListParallel(
        term: Term,
        loadingState: (Float) -> Unit = {},
        postHogDistinctId: String? = null,
    ): List<Subject> = requestMutex.withLock {
        withContext(Dispatchers.Default) {
            ensureLoggedIn()

            loadingState(0.1f)
            val lecturesDeferred = async { courseClient.fetchLectures(term) }
            val learnStatusesDeferred = async { courseClient.fetchLearnStatuses(term) }
            val lectures = lecturesDeferred.await()
            loadingState(0.2f)
            val initialRequests = lectures.map { lecture ->
                prefetchCourseRequests(courseClient, lecture)
            }
            val learnStatusByCourseId = learnStatusesDeferred.await()
                .learnstatuses
                .associateFirstById { it.course.id }

            loadingState(0.3f)
            val weight = if (lectures.isEmpty()) 0f else 0.7f / lectures.size
            val now = Clock.System.now()
            val shouldTrackPostHog = !postHogDistinctId.isNullOrBlank()
            val progressMutex = Mutex()
            var completedCourses = 0

            val courseResults = initialRequests.map { initial ->
                async {
                    val lecture = initial.lecture
                    val (submissions, permissionFailed) = initial.submissions.await()
                    courseClient.applyAssignmentMetadata(submissions, initial.metadata.await())
                    val includeCommons = lecture.activities.mayHaveCommonsTodos()
                    val submissionTrackingItems = if (shouldTrackPostHog) {
                        submissions.toSubmissionTrackingItems(
                            courseId = lecture.id,
                            now = now,
                        )
                    } else {
                        emptyList()
                    }
                    val submissionStats = if (shouldTrackPostHog) {
                        submissionTrackingItems.toUnsubmittedStats()
                    } else {
                        submissions.toUnsubmittedStats(now)
                    }
                    val includeSubject =
                        lecture.activities.mayHaveTodoAssignments() || includeCommons || submissions.isNotEmpty()
                    val todoResult = if (!includeSubject) {
                        TodoBuildResult(todoList = emptyList())
                    } else {
                        buildCourseTodoParallel(
                            courseId = lecture.id,
                            submissions = submissions,
                            includeCommons = includeCommons,
                            includeCommonsForTracking = shouldTrackPostHog,
                            todoDetails = initial.todoDetails.await(),
                        )
                    }

                    val result = ParallelTodoCourseResult(
                        subject = if (includeSubject) Subject(
                            id = lecture.id,
                            termId = lecture.term_id,
                            termName = term.name ?: "학기정보 없음",
                            name = lecture.name,
                            professor = lecture.professors,
                            totalStudents = lecture.total_students,
                            todoList = todoResult.todoList,
                            attendances = learnStatusByCourseId[lecture.id]
                                .toAttendances(permissionFailed),
                            discussions = if (!permissionFailed) {
                                initial.discussions.await().getOrDefault(emptyList())
                            } else {
                                emptyList()
                            },
                            submissions = submissions + todoResult.completedCommonsSubmissions,
                            scoredAssignments = emptyList(),
                            permissionFailed = permissionFailed,
                        ) else null,
                        stats = LmsApi.UnsubmittedStats(
                            totalCount = submissionStats.totalCount + todoResult.commonsStats.totalCount,
                            unsubmittedCount = submissionStats.unsubmittedCount + todoResult.commonsStats.unsubmittedCount,
                        ),
                        trackingItems = if (shouldTrackPostHog) {
                            submissionTrackingItems + todoResult.commonsTrackingItems
                        } else {
                            emptyList()
                        },
                    )

                    val currentCompleted = progressMutex.withLock { ++completedCourses }
                    loadingState(0.3f + weight * currentCompleted)
                    result
                }
            }.awaitAll()

            trackTodoSync(
                stats = LmsApi.UnsubmittedStats(
                    totalCount = courseResults.sumOf { it.stats.totalCount },
                    unsubmittedCount = courseResults.sumOf { it.stats.unsubmittedCount },
                ),
                items = courseResults.flatMap { it.trackingItems },
                postHogDistinctId = postHogDistinctId,
            )
            courseResults.mapNotNull { it.subject }
        }
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
            val detail = courseClient.fetchAssignmentDetail(courseId, assignmentId)
            submission.toAssignmentTodo(detail, now)?.let { todoList += it }
        }

        if (includeCommons || includeCommonsForTracking) {
            val todoDetails = courseClient.fetchTodoDetails(courseId)
            commonsTrackingItems = if (includeCommonsForTracking) {
                todoDetails.toCommonsTrackingItems(courseId, now)
            } else {
                emptyList()
            }
            commonsStats = if (includeCommonsForTracking) {
                commonsTrackingItems.toUnsubmittedStats()
            } else {
                todoDetails.toCommonsUnsubmittedStats(now)
            }
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

    suspend fun buildCourseTodoParallel(
        courseId: Int,
        submissions: List<Submission>,
        includeCommons: Boolean,
        includeCommonsForTracking: Boolean = false,
        todoDetails: List<TodoDetail>,
    ): TodoBuildResult = coroutineScope {
        val now = Clock.System.now()
        val assignmentRequests = submissions
            .asSequence()
            .filter { it.assignment_id?.takeIf { id -> id > 0 } != null }
            .distinctBy { it.assignment_id }
            .filterNot { it.isCompletedForTodo() }
            .map { submission ->
                async {
                    val assignmentId = requireNotNull(submission.assignment_id)
                    val detail = courseClient.fetchAssignmentDetail(courseId, assignmentId)
                    submission.toAssignmentTodo(detail, now)
                }
            }
            .toList()

        val todoList = assignmentRequests.awaitAll().filterNotNull().toMutableList()
        val relevantTodoDetails = if (includeCommons || includeCommonsForTracking) {
            todoDetails
        } else {
            emptyList()
        }
        val commonsTrackingItems = if (includeCommonsForTracking) {
            relevantTodoDetails.toCommonsTrackingItems(courseId, now)
        } else {
            emptyList()
        }
        if (includeCommons) {
            todoList += relevantTodoDetails.toCommonsTodoList(now)
        }

        TodoBuildResult(
            todoList = todoList.sortedBy { it.due_date },
            commonsStats = if (includeCommonsForTracking) {
                commonsTrackingItems.toUnsubmittedStats()
            } else {
                relevantTodoDetails.toCommonsUnsubmittedStats(now)
            },
            commonsTrackingItems = commonsTrackingItems,
            completedCommonsSubmissions = relevantTodoDetails.toCompletedCommonsSubmissions(),
        )
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

    private fun String?.isUnlocked(now: Instant): Boolean {
        val value = takeUnless { it.isNullOrBlank() } ?: return true
        val unlockDate = runCatching { Instant.parse(value) }.getOrNull() ?: return true
        return now >= unlockDate
    }

    private fun String?.orFallback(fallback: String): String {
        return takeUnless { it.isNullOrBlank() } ?: fallback
    }

    private fun List<TodoDetail>.toCommonsTodoList(now: Instant): List<TodoList> {
        val result = mutableListOf<TodoList>()
        for (module in this) {
            if (!module.unlock_at.isUnlocked(now)) continue
            for (item in module.module_items.orEmpty()) {
                val contentData = item.content_data ?: continue
                val contentType = contentData.item_content_type ?: continue
                if (contentType != "commons") continue
                if (contentData.item_content_data?.duration == null) continue
                if (contentData.use_attendance == false) continue
                if (item.completed == true) continue
                val submissionDeadline = contentData.late_at?.takeIf { it.isNotBlank() }
                    ?: contentData.due_at
                if (!submissionDeadline.isFutureInstant(now)) continue
                if (!contentData.unlock_at.isUnlocked(now)) continue

                val itemUnlockAt = contentData.unlock_at.takeUnless { it.isNullOrBlank() }
                    ?: module.unlock_at.orEmpty()

                result += TodoList(
                    section_id = 0,
                    unit_id = 0,
                    component_id = contentData.item_id ?: item.content_id ?: 0,
                    generated_from_lecture_content = false,
                    component_type = contentType,
                    assignment_id = -1,
                    title = contentData.title.orFallback(item.title.orEmpty()),
                    due_date = contentData.due_at.orEmpty(),
                    due_at = contentData.due_at.orEmpty(),
                    submission_deadline = submissionDeadline.orEmpty(),
                    late_at = contentData.late_at.orEmpty(),
                    unlock_at = itemUnlockAt,
                    description = contentData.description,
                    url = contentData.item_content_data.view_url.orEmpty(),
                    moduleItemId = item.module_item_id,
                    durationOfVideo = contentData.item_content_data.duration,
                    attachments = emptyList() // common 타입에는 제출한 과제가 없을겁니다. 그냥 empty로 둡니다
                )
            }
        }
        return result
    }

    private fun List<TodoDetail>.toCommonsUnsubmittedStats(
        now: Instant,
    ): LmsApi.UnsubmittedStats {
        val seenItemIds = mutableSetOf<Int>()
        var totalCount = 0
        var unsubmittedCount = 0
        for (module in this) {
            for (item in module.module_items.orEmpty()) {
                val contentData = item.content_data ?: continue
                if (contentData.item_content_type != "commons") continue
                if (contentData.item_content_data?.duration == null) continue
                if (contentData.use_attendance == false) continue

                val itemId = contentData.item_id?.takeIf { it > 0 }
                    ?: item.content_id?.takeIf { it > 0 }
                    ?: item.module_item_id?.takeIf { it > 0 }
                    ?: continue
                if (!seenItemIds.add(itemId)) continue

                totalCount += 1
                if (item.completed != true && contentData.due_at.isPastOrCurrentInstant(now)) {
                    unsubmittedCount += 1
                }
            }
        }
        return LmsApi.UnsubmittedStats(totalCount, unsubmittedCount)
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

    private val snapshotTracker = TodoSnapshotTracker(client, backgroundScope, trackedSnapshotDates)

    private fun trackTodoSync(
        stats: LmsApi.UnsubmittedStats,
        items: List<TodoTrackingItem>,
        postHogDistinctId: String?,
    ) {
        snapshotTracker.track(stats, items.map {
            TodoSnapshotItem(
                itemKey = it.itemKey,
                itemType = it.itemType,
                courseId = JsonPrimitive(it.courseId),
                dueAt = it.dueAt,
                isCompleted = it.isCompleted,
                isOverdueUnsubmitted = it.isOverdueUnsubmitted,
                workflowState = it.workflowState,
                late = it.late,
            )
        }, postHogDistinctId)
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

private data class ParallelTodoCourseResult(
    val subject: Subject?,
    val stats: LmsApi.UnsubmittedStats,
    val trackingItems: List<TodoTrackingItem>,
)
