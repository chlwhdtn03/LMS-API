package io.github.chlwhdtn03

import io.github.chlwhdtn03.data.Lms.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

private val lmsJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

private class ResettableCookiesStorage : CookiesStorage {
    private val mutex = Mutex()
    private var delegate: CookiesStorage = AcceptAllCookiesStorage()

    override suspend fun get(requestUrl: Url): List<Cookie> {
        mutex.lock()
        return try {
            delegate.get(requestUrl)
        } finally {
            mutex.unlock()
        }
    }

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) {
        mutex.lock()
        try {
            delegate.addCookie(requestUrl, cookie)
        } finally {
            mutex.unlock()
        }
    }

    suspend fun clear() {
        mutex.lock()
        try {
            delegate.close()
            delegate = AcceptAllCookiesStorage()
        } finally {
            mutex.unlock()
        }
    }

    override fun close() {
        delegate.close()
    }
}

private val lmsCookiesStorage = ResettableCookiesStorage()

internal val client = HttpClient() {
    install(HttpCookies) {
        storage = lmsCookiesStorage
    }
    install(ContentNegotiation) {
        json(lmsJson)
    }
    followRedirects = true
}

private val apiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
private const val XSSI_PREFIX = "while(1);"
private val LMS_COOKIE_URLS = listOf(
    "https://canvas.ssu.ac.kr",
    "https://lms.ssu.ac.kr",
    "https://smartid.ssu.ac.kr",
)
private const val POSTHOG_PROJECT_API_KEY = "phc_o6q2pUmTRryWQ6Np5HkqLA2q4d6jdR6mVhGf5bqaKgtT"
private const val POSTHOG_BATCH_URL = "https://us.i.posthog.com/batch/"
private const val POSTHOG_IDENTIFY_EVENT = "\$identify"
private const val POSTHOG_TODO_SNAPSHOT_EVENT = "todo_snapshot"
internal const val TODO_SNAPSHOT_SAMPLE_RATE = 0.2

private fun String.withoutXssiPrefix(): String {
    val body = dropWhile { it == '\uFEFF' || it.isWhitespace() }
    return if (body.startsWith(XSSI_PREFIX)) {
        body.drop(XSSI_PREFIX.length).dropWhile { it.isWhitespace() }
    } else {
        body
    }
}

internal fun shouldSendTodoSnapshot(sample: Double = Random.nextDouble()): Boolean {
    return sample < TODO_SNAPSHOT_SAMPLE_RATE
}

object LmsApi {
    private const val CANVAS_BASE_URL = "https://canvas.ssu.ac.kr"
    private const val LMS_BASE_URL = "https://lms.ssu.ac.kr"
    private const val SAINT_BASE_URL = "https://saint.ssu.ac.kr"
    private const val LMS_LOGIN_URL = "https://smartid.ssu.ac.kr/Symtra_sso/smln_pcs.asp"
    private const val LMS_CERT_URL = "https://lms.ssu.ac.kr/xn-sso/gw-cb.php"
    var isLoggined = false
        private set
    private var lmsId = ""
    private var apiBearerToken = ""
    private val webDynproCache = mutableMapOf<String, Pair<String, String>>()
    private var cachedLatestGradeYear: String? = null
    private var cachedLatestGradeSemester: Semester? = null
    private var cachedLatestChapelYear: String? = null
    private var cachedLatestChapelSemester: Semester? = null
    private var cachedChapelPeryrId: String? = null
    private var cachedChapelPeridId: String? = null
    private var cachedChapelBtnSearchId: String? = null
    private var cachedChapelInformation: ChapelInformation? = null
    private val sessionMutex = Mutex()

    private data class AssignmentMetadata(
        val groupName: String,
        val name: String,
        val maxScore: Double,
    )

    @Serializable
    private data class TodoAssignmentDetail(
        val name: String? = "",
        val description: String? = "",
        val submission_types: List<String>? = emptyList(),
        val due_at: String? = "",
        val lock_at: String? = "",
        val late_at: String? = "",
    )

    internal data class UnsubmittedStats(
        val totalCount: Int = 0,
        val unsubmittedCount: Int = 0,
    ) {
        val ratio: Double
            get() = if (totalCount == 0) 0.0 else unsubmittedCount.toDouble() / totalCount
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

    private data class TodoTrackingItem(
        val itemKey: String,
        val itemType: String,
        val courseId: Int,
        val dueAt: String,
        val isCompleted: Boolean,
        val isOverdueUnsubmitted: Boolean,
        val workflowState: String? = null,
        val late: Boolean? = null,
    )

    private enum class SubjectLoadMode {
        Full,
        TodoOnly,
    }

    private data class TodoBuildResult(
        val todoList: List<TodoList>,
        val commonsStats: UnsubmittedStats = UnsubmittedStats(),
        val commonsTrackingItems: List<TodoTrackingItem> = emptyList(),
        val completedCommonsSubmissions: List<Submission> = emptyList(),
    )

    private val trackedTodoSnapshotDatesByDistinctId = mutableMapOf<String, String>()

    private fun checkLoggedIn() {
        if (!isLoggined || lmsId.isBlank()) {
            throw IllegalStateException("LMS 로그인이 되어있지 않습니다.")
        }
    }

    private fun clearCachedUserData() {
        webDynproCache.clear()
        cachedLatestGradeYear = null
        cachedLatestGradeSemester = null
        cachedLatestChapelYear = null
        cachedLatestChapelSemester = null
        cachedChapelPeryrId = null
        cachedChapelPeridId = null
        cachedChapelBtnSearchId = null
        cachedChapelInformation = null
    }

    private suspend fun resetSession() {
        isLoggined = false
        lmsId = ""
        apiBearerToken = ""
        clearCachedUserData()
        lmsCookiesStorage.clear()
    }

    private fun canvasUrl(path: String): String {
        return if (path.startsWith("/")) "$CANVAS_BASE_URL$path" else "$CANVAS_BASE_URL/$path"
    }

    private fun lmsUrl(path: String): String {
        return if (path.startsWith("/")) "$LMS_BASE_URL$path" else "$LMS_BASE_URL/$path"
    }

    private fun saintUrl(path: String): String {
        return if (path.startsWith("/")) "$SAINT_BASE_URL$path" else "$SAINT_BASE_URL/$path"
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun fetchTerms(): List<Term> {
        val response = client.get(canvasUrl("/learningx/api/v1/users/${lmsId}/terms?include_invited_course_contained=true")) {
            headers { 
                if (apiBearerToken.isNotBlank()) append("Authorization", "Bearer $apiBearerToken")
            }
        }
        val bodyText = response.bodyAsText()
        if (response.status.value != 200) {
            throw IllegalStateException("LMS 학기 조회 실패 (${response.status.value}): $bodyText")
        }
        try {
            return lmsJson.decodeFromString<Terms>(bodyText).enrollment_terms
        } catch (e: Exception) {
            try {
                return lmsJson.decodeFromString<List<Term>>(bodyText)
            } catch (e2: Exception) {
                throw IllegalStateException("LMS 학기 파싱 실패. 응답: $bodyText", e)
            }
        }
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun fetchLectures(term: Term): List<Lecture> {
        return client.get(canvasUrl("/learningx/api/v1/learn_activities/courses?term_ids[]=${term.id}")) {
            headers { 
                if (apiBearerToken.isNotBlank()) append("Authorization", "Bearer $apiBearerToken")
            }
        }.body<List<Lecture>>()
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun fetchLoginInfo(userId: String = lmsId): Info {
        return client.get(lmsUrl("/api/v1/users/${userId}")) {
        }.body<Info>()
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun fetchLearnStatuses(term: Term): LearnStatuses {
        return client.get(canvasUrl("/learningx/api/v1/learn_activities/learnstatus?term_ids=${term.id}&type=subsection")) {
            headers { 
                if (apiBearerToken.isNotBlank()) append("Authorization", "Bearer $apiBearerToken")
            }
        }.body<LearnStatuses>()
    }

    private suspend fun fetchLmsSession(): LmsSessionResponse {
        val cookiesByKey = linkedMapOf<String, LmsSessionCookie>()

        for (urlString in LMS_COOKIE_URLS) {
            val url = Url(urlString)
            for (cookie in client.cookies(url)) {
                val sessionCookie = LmsSessionCookie(
                    name = cookie.name,
                    value = cookie.value,
                    domain = ".ssu.ac.kr",
                    path = cookie.path ?: "/",
                )
                val key = "${sessionCookie.domain}|${sessionCookie.path}|${sessionCookie.name}"
                cookiesByKey[key] = sessionCookie
            }
        }

        return LmsSessionResponse(
            lmsSession = LmsSession(cookies = cookiesByKey.values.toList()),
        )
    }

    private suspend fun fetchAssignmentGroups(courseId: Int): List<AssignmentGroup> {
        return client.get(canvasUrl("/api/v1/courses/${courseId}/assignment_groups")) {
            url {
                parameters.append("exclude_response_fields[]", "description")
                parameters.append("exclude_response_fields[]", "rubric")
                parameters.append("include[]", "assignments")
                parameters.append("include[]", "discussion_topic")
                parameters.append("override_assignment_dates", "true")
                parameters.append("per_page", "50")
            }
        }.body<List<AssignmentGroup>>()
    }

    private suspend fun fetchAssignmentDetails(courseId: Int, assignmentId: Int): TodoAssignmentDetail {
        return client
            .get(canvasUrl("/api/v1/courses/$courseId/assignments/$assignmentId"))
            .body<TodoAssignmentDetail>()
    }

    private suspend fun fetchTodoDetail(courseId: Int): List<TodoDetail> {
        return client
            .get(canvasUrl("/learningx/api/v1/courses/${courseId}/modules?include_detail=true")) {
                headers { 
                    if (apiBearerToken.isNotBlank()) append("Authorization", "Bearer $apiBearerToken")
                }
            }
            .body<List<TodoDetail>>()
    }


    private suspend fun fetchSubmissions(courseId: Int): Pair<List<Submission>, Boolean> {
        val response = client.get(canvasUrl("/api/v1/courses/${courseId}/students/submissions")) {
            url {
                parameters.append("per_page", "100")
            }
        }
        return try {
            Pair(response.body<List<Submission>>(), false)
        } catch(e: JsonConvertException) {
            println("과목ID ${courseId}에서 권한 실패로 전체 과제 목록을 조회하지 못했습니다.")
            println("조회 결과에 문제가 발생할 수 있습니다.")
            return Pair(emptyList(), true)
        }
    }

    private suspend fun fetchDiscussions(courseId: Int): List<Discussion> {
        return client.get(canvasUrl("/api/v1/courses/${courseId}/discussion_topics?only_announcements=true&per_page=40&page=1&filter_by=all&no_avatar_fallback=1&include[]=sections_user_count&include[]=sections")) {
            headers {
                append("Referer", canvasUrl("/courses/${courseId}/announcements"))
            }
        }.body<List<Discussion>>()
    }

    private inline fun <T> Iterable<T>.associateFirstById(keySelector: (T) -> Int): Map<Int, T> {
        val result = mutableMapOf<Int, T>()
        for (item in this) {
            val key = keySelector(item)
            if (!result.containsKey(key)) {
                result[key] = item
            }
        }
        return result
    }

    private fun List<AssignmentGroup>.toAssignmentMetadataById(): Map<Int, AssignmentMetadata> {
        val result = mutableMapOf<Int, AssignmentMetadata>()
        for (group in this) {
            for (assignment in group.assignments) {
                if (!result.containsKey(assignment.id)) {
                    result[assignment.id] = AssignmentMetadata(
                        groupName = group.name,
                        name = assignment.name,
                        maxScore = assignment.points_possible ?: 0.0,
                    )
                }
            }
        }
        return result
    }

    private fun applyAssignmentMetadata(
        submissions: List<Submission>,
        assignmentMetadataById: Map<Int, AssignmentMetadata>,
    ) {
        for (submission in submissions) {
            val metadata = assignmentMetadataById[submission.assignment_id] ?: continue
            submission.name = metadata.name
            submission.groupName = metadata.groupName
        }
    }

    private fun Submission.isCompletedForTodo(): Boolean {
        return !submitted_at.isNullOrBlank() || workflow_state == "submitted" || workflow_state == "graded"
    }

    @OptIn(ExperimentalTime::class)
    private fun Submission.isOverdueUnsubmitted(now: Instant): Boolean {
        if (workflow_state != "unsubmitted") return false
        if (late == true) return true

        return cached_due_date.isPastOrCurrentInstant(now)
    }

    private fun List<Submission>.toUnsubmittedStats(now: Instant): UnsubmittedStats {
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

        return UnsubmittedStats(
            totalCount = totalCount,
            unsubmittedCount = unsubmittedCount,
        )
    }

    private fun List<Submission>.toSubmissionTrackingItems(
        courseId: Int,
        now: Instant,
    ): List<TodoTrackingItem> {
        val seenAssignmentIds = mutableSetOf<Int>()
        val items = mutableListOf<TodoTrackingItem>()

        for (submission in this) {
            val assignmentId = submission.assignment_id?.takeIf { it > 0 } ?: continue
            if (!seenAssignmentIds.add(assignmentId)) continue

            items += TodoTrackingItem(
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

        return items
    }

    private fun List<TodoTrackingItem>.toUnsubmittedStats(): UnsubmittedStats {
        return UnsubmittedStats(
            totalCount = size,
            unsubmittedCount = count { it.isOverdueUnsubmitted },
        )
    }

    private fun trackTodoSync(
        stats: UnsubmittedStats,
        items: List<TodoTrackingItem>,
        postHogDistinctId: String?,
    ) {
        val distinctId = postHogDistinctId?.trim()?.takeIf { it.isNotBlank() } ?: return
        val now = Clock.System.now().toString()
        val today = now.substringBefore('T')
        if (trackedTodoSnapshotDatesByDistinctId[distinctId] == today) return
        trackedTodoSnapshotDatesByDistinctId[distinctId] = today

        val syncId = "todo_sync:${Random.nextLong()}:$now"
        val shouldSendSnapshot = shouldSendTodoSnapshot()
        if (shouldSendSnapshot) {
            val events = buildList<PostHogBatchEvent> {
                add(
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
                    )
                )
                add(
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
                                for (item in items) {
                                    add(JsonPrimitive(item.itemKey))
                                }
                            })
                            put("overdue_unsubmitted_item_keys", buildJsonArray {
                                for (item in items) {
                                    if (item.isOverdueUnsubmitted) {
                                        add(JsonPrimitive(item.itemKey))
                                    }
                                }
                            })
                            put("items", buildJsonArray {
                                for (item in items) {
                                    add(
                                        buildJsonObject {
                                            put("item_key", item.itemKey)
                                            put("item_type", item.itemType)
                                            put("course_id", item.courseId)
                                            put("due_at", item.dueAt)
                                            put("is_completed", item.isCompleted)
                                            put("is_overdue_unsubmitted", item.isOverdueUnsubmitted)
                                            item.workflowState?.let { put("workflow_state", it) }
                                            item.late?.let { put("late", it) }
                                        }
                                    )
                                }
                            })
                        },
                        timestamp = now,
                    )
                )
            }
            apiScope.launch {
                runCatching {
                    client.post(POSTHOG_BATCH_URL) {
                        contentType(ContentType.Application.Json)
                        setBody(
                            PostHogBatchRequest(
                                apiKey = POSTHOG_PROJECT_API_KEY,
                                batch = events,
                            )
                        )
                    }
                }
            }
        }
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

    @OptIn(ExperimentalTime::class)
    private fun String?.isFutureInstant(now: Instant): Boolean {
        val value = takeUnless { it.isNullOrBlank() } ?: return false
        val dueDate = runCatching { Instant.parse(value) }.getOrNull() ?: return false
        return dueDate > now
    }

    @OptIn(ExperimentalTime::class)
    private fun String?.isPastOrCurrentInstant(now: Instant): Boolean {
        val value = takeUnless { it.isNullOrBlank() } ?: return false
        val dueDate = runCatching { Instant.parse(value) }.getOrNull() ?: return false
        return dueDate <= now
    }

    private fun String?.orFallback(fallback: String): String {
        return takeUnless { it.isNullOrBlank() } ?: fallback
    }

    @OptIn(ExperimentalTime::class)
    private fun List<TodoDetail>.toCommonsTodoList(now: Instant): List<TodoList> {
        val todoList = mutableListOf<TodoList>()

        for (module in this) {
            for (item in module.module_items.orEmpty()) {
                val contentData = item.content_data ?: continue
                val itemContentType = contentData.item_content_type ?: continue
                if (itemContentType != "commons") continue
                if (contentData.item_content_data?.duration == null) continue
                if (contentData.use_attendance == false) continue
                if (item.completed == true) continue
                if (!contentData.due_at.isFutureInstant(now)) continue

                todoList += TodoList(
                    section_id = 0,
                    unit_id = 0,
                    component_id = contentData.item_id ?: item.content_id ?: 0,
                    generated_from_lecture_content = false,
                    component_type = itemContentType,
                    assignment_id = -1,
                    title = contentData.title.orFallback(item.title.orEmpty()),
                    due_date = contentData.due_at.orEmpty(),
                    late_at = contentData.late_at.orEmpty(),
                    description = contentData.description,
                )
            }
        }

        return todoList
    }

    private fun List<TodoDetail>.toCommonsUnsubmittedStats(now: Instant): UnsubmittedStats {
        return toCommonsTrackingItems(
            courseId = 0,
            now = now,
        ).toUnsubmittedStats()
    }

    private fun List<TodoDetail>.toCommonsTrackingItems(
        courseId: Int,
        now: Instant,
    ): List<TodoTrackingItem> {
        val seenItemIds = mutableSetOf<Int>()
        val items = mutableListOf<TodoTrackingItem>()

        for (module in this) {
            for (item in module.module_items.orEmpty()) {
                val contentData = item.content_data ?: continue
                val itemContentType = contentData.item_content_type ?: continue
                if (itemContentType != "commons") continue
                if (contentData.item_content_data?.duration == null) continue
                if (contentData.use_attendance == false) continue

                val itemId = contentData.item_id?.takeIf { it > 0 }
                    ?: item.content_id?.takeIf { it > 0 }
                    ?: item.module_item_id?.takeIf { it > 0 }
                    ?: continue
                if (!seenItemIds.add(itemId)) continue

                items += TodoTrackingItem(
                    itemKey = "commons:$courseId:$itemId",
                    itemType = "commons",
                    courseId = courseId,
                    dueAt = contentData.due_at.orEmpty(),
                    isCompleted = item.completed == true,
                    isOverdueUnsubmitted = item.completed != true && contentData.due_at.isPastOrCurrentInstant(now),
                )
            }
        }

        return items
    }

    private fun List<TodoDetail>.toCompletedCommonsSubmissions(): List<Submission> {
        val seenItemIds = mutableSetOf<Int>()
        val submissions = mutableListOf<Submission>()

        for (module in this) {
            for (item in module.module_items.orEmpty()) {
                val contentData = item.content_data ?: continue
                val itemContentType = contentData.item_content_type ?: continue
                if (itemContentType != "commons") continue
                if (contentData.item_content_data?.duration == null) continue
                if (contentData.use_attendance == false) continue
                if (item.completed != true) continue

                val itemId = contentData.item_id?.takeIf { it > 0 }
                    ?: item.content_id?.takeIf { it > 0 }
                    ?: item.module_item_id?.takeIf { it > 0 }
                    ?: continue
                if (!seenItemIds.add(itemId)) continue

                submissions += Submission(
                    assignment_id = itemId,
                    cached_due_date = contentData.due_at,
                    late = false,
                    submitted_at = "",
                    submission_type = itemContentType,
                    workflow_state = "submitted",
                ).apply {
                    name = contentData.title.orFallback(item.title.orEmpty())
                    groupName = module.title.orFallback("동영상")
                }
            }
        }

        return submissions
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun buildTodoListFromSubmissions(
        courseId: Int,
        submissions: List<Submission>,
        includeCommons: Boolean,
        includeCommonsForTracking: Boolean = false,
    ): TodoBuildResult {
        val now = Clock.System.now()
        val seenAssignmentIds = mutableSetOf<Int>()
        val todoList = mutableListOf<TodoList>()
        var commonsStats = UnsubmittedStats()
        var commonsTrackingItems = emptyList<TodoTrackingItem>()
        var completedCommonsSubmissions = emptyList<Submission>()

        for (submission in submissions) {
            val assignmentId = submission.assignment_id?.takeIf { it > 0 } ?: continue
            if (!seenAssignmentIds.add(assignmentId)) continue
            if (submission.isCompletedForTodo()) continue
            if (!submission.cached_due_date.isFutureInstant(now)) continue

            val assignmentDetail = fetchAssignmentDetails(courseId, assignmentId)
            val dueDate = submission.cached_due_date.orFallback(assignmentDetail.due_at.orEmpty())

            todoList += TodoList(
                section_id = 0,
                unit_id = 0,
                component_id = 0,
                generated_from_lecture_content = false,
                component_type = when(assignmentDetail.submission_types?.first() ?: "") {
                    "online_quiz" -> "quiz"
                    else -> "assignment"
                },
                assignment_id = assignmentId,
                title = assignmentDetail.name.orFallback(submission.name),
                due_date = dueDate,
                late_at = assignmentDetail.late_at.orFallback(assignmentDetail.lock_at.orEmpty()),
                description = assignmentDetail.description,
            )
        }

        if (includeCommons || includeCommonsForTracking) {
            val todoDetails = fetchTodoDetail(courseId)
            commonsTrackingItems = todoDetails.toCommonsTrackingItems(
                courseId = courseId,
                now = now,
            )
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

    private fun Throwable.toResultMessage(): String {
        return message ?: "알 수 없는 오류가 발생했습니다."
    }

    private fun launchLoginResult(
        completion: (LmsLoginResult) -> Unit,
        block: suspend () -> Boolean,
    ) {
        apiScope.launch {
            val result = try {
                LmsLoginResult(success = block())
            } catch (throwable: Throwable) {
                LmsLoginResult(success = false, errorMessage = throwable.toResultMessage())
            }
            completion(result)
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun launchTermsResult(
        completion: (LmsTermsResult) -> Unit,
        block: suspend () -> List<Term>,
    ) {
        apiScope.launch {
            val result = try {
                LmsTermsResult(success = true, terms = block())
            } catch (throwable: Throwable) {
                LmsTermsResult(success = false, errorMessage = throwable.toResultMessage())
            }
            completion(result)
        }
    }

    private fun launchLoginInfoResult(
        completion: (LmsLoginInfoResult) -> Unit,
        block: suspend () -> Info,
    ) {
        apiScope.launch {
            val result = try {
                LmsLoginInfoResult(success = true, info = block())
            } catch (throwable: Throwable) {
                LmsLoginInfoResult(success = false, errorMessage = throwable.toResultMessage())
            }
            completion(result)
        }
    }

    private fun launchCookiesResult(
        completion: (LmsCookiesResult) -> Unit,
        block: suspend () -> LmsSessionResponse,
    ) {
        apiScope.launch {
            val result = try {
                LmsCookiesResult(success = true, lmsSession = block().lmsSession)
            } catch (throwable: Throwable) {
                LmsCookiesResult(success = false, errorMessage = throwable.toResultMessage())
            }
            completion(result)
        }
    }

    private fun launchSubjectsResult(
        completion: (LmsSubjectsResult) -> Unit,
        block: suspend () -> List<Subject>,
    ) {
        apiScope.launch {
            val result = try {
                LmsSubjectsResult(success = true, subjects = block())
            } catch (throwable: Throwable) {
                LmsSubjectsResult(success = false, errorMessage = throwable.toResultMessage())
            }
            completion(result)
        }
    }

    private fun buildScoredAssignments(
        submissions: List<Submission>,
        assignmentMetadataById: Map<Int, AssignmentMetadata>,
    ): List<ScoredAssignment> {
        val result = mutableListOf<ScoredAssignment>()
        for (submission in submissions) {
            if(submission.score != null)
                if (!(submission.score > Double.NEGATIVE_INFINITY)) continue

            val metadata = assignmentMetadataById[submission.assignment_id]
            result += ScoredAssignment(
                groupName = metadata?.groupName ?: "알 수 없음",
                name = metadata?.name ?: "알 수 없음",
                score = submission.score ?: 0.0,
                maxScore = metadata?.maxScore ?: 0.0,
            )
        }
        return result
    }

    /**
     * LMS에 로그인합니다. 로그인에 성공하면 학번 정보와 토큰 정보가 캐싱되어 이후 요청들에 사용됩니다.
     *
     * @param id LMS 아이디
     * @param password LMS 비밀번호
     * @return LMS로그인에 성공하면 true, 실패하면 false를 반환합니다.
     */
    @Throws(Exception::class)
    internal suspend fun loginLMS(id: String, password: String): Boolean {
        sessionMutex.lock()
        try {
            resetSession()
            return try {
                performLogin(id, password)
            } catch (throwable: Throwable) {
                withContext(NonCancellable) {
                    resetSession()
                }
                throw throwable
            }
        } finally {
            sessionMutex.unlock()
        }
    }

    private suspend fun performLogin(id: String, password: String): Boolean {
        val loginResponse = client.submitForm(
            url = LMS_LOGIN_URL,
            formParameters = parameters {
                append("userid", id)
                append("pwd", password)
            }
        )
        val bodyText = loginResponse.bodyAsText()
        var token = ""
        if (bodyText.contains("sToken=")) {
            val rawToken = bodyText.substringAfter("sToken=").substringBefore("&").substringBefore("'")
            val regex = Regex("""x(2B|2F|3D|5F|78|79|7A)""")
            token = regex.replace(rawToken) { matchResult ->
                val hex = matchResult.groupValues[1]
                hex.toIntOrNull(16)?.toChar()?.toString() ?: matchResult.value
            }
        }

        if (token.isBlank()) {
            val cookies = loginResponse.headers.getAll("Set-Cookie")
            token = cookies?.find { it.contains("sToken") }
                ?.substringAfter("sToken=")
                ?.substringBefore(";") ?: ""
        }
        println("sToken received: ${token.isNotBlank()}")

        if (token.isBlank())
            throw IllegalArgumentException("아이디 또는 비밀번호가 일치하지 않습니다.")

        val certResponse = client.get(LMS_CERT_URL) {
            url {
                parameters.append("sToken", token)
                parameters.append("sIdno", id)
            }
        }
        println("Cert Response Status: ${certResponse.status}")
        val certBody = certResponse.bodyAsText()
        println("Cert Body Length: ${certBody.length}")

        var redirectURL = certBody
            .substringAfter("iframe.src=\"")
            .substringBefore("\";")
        if (!redirectURL.startsWith("http")) {
            redirectURL = "$CANVAS_BASE_URL$redirectURL"
        }

        val apiToken = client.get(redirectURL)
        println("Api Token Response Status: ${apiToken.status}")

        val extractedApiToken = redirectURL
            .takeIf { it.contains("api_token=") }
            ?.substringAfter("api_token=")
            ?.substringBefore("&")
            .orEmpty()
        val canvasCookies = client.cookies(CANVAS_BASE_URL)
        val lmsCookies = client.cookies(LMS_BASE_URL)
        val cookieToken = (canvasCookies + lmsCookies).find { it.name == "xn_api_token" }?.value ?: ""

        apiBearerToken = if (extractedApiToken.isNotBlank()) {
            extractedApiToken
        } else if (cookieToken.isNotBlank()) {
            cookieToken
        } else {
            apiToken.headers.getAll("Set-Cookie")?.find { it.contains("xn_api_token") }
                ?.substringAfter("xn_api_token=")
                ?.substringBefore(";") ?: ""
        }
        if (apiBearerToken.isBlank()) {
            throw RuntimeException("API 토큰값을 불러오지 못했습니다. 다시 시도해주세요.")
        }

        val body = apiToken.bodyAsText()
        val pem = body
            .substringAfter("window.loginCryption(\"")
            .substringBefore("\")")
            .substringAfter(", \"")

        val raw_pw = body
            .substringAfter("window.loginCryption(\"")
            .substringBefore("\"")

        val authenticityToken = Regex(
            """<input\b(?=[^>]*\bname=["']authenticity_token["'])(?=[^>]*\bvalue=["']([^"']+)["'])[^>]*>""",
            RegexOption.IGNORE_CASE,
        ).find(body)?.groupValues?.get(1)?.decodeHtmlEntities().orEmpty()

        val decryptedPassword = pemToString(rawPem = pem, rawPw = raw_pw)

        client.submitForm(
            url = canvasUrl("/login/canvas"),
            formParameters = parameters {
                append("utf8", "✓")
                if (authenticityToken.isNotBlank()) {
                    append("authenticity_token", authenticityToken)
                }
                append("redirect_to_ssl", "1")
                append("after_login_url", "")
                append("pseudonym_session[unique_id]", id)
                append("pseudonym_session[password]", decryptedPassword)
                append("pseudonym_session[remember_me]", "0")
            }
        ) {
            headers {
                append(HttpHeaders.Origin, "https://canvas.ssu.ac.kr")
                append(HttpHeaders.Referrer, redirectURL)
                append(HttpHeaders.CacheControl, "max-age=0")
                append(
                    HttpHeaders.Accept,
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
                )
            }
        }

        val canvasConfirmationResponse = client.get(canvasUrl("/?login_success=1")) {
            headers {
                append(HttpHeaders.Referrer, redirectURL)
            }
        }
        println("Canvas login confirmation status: ${canvasConfirmationResponse.status}")

        val saintSsoResponse = client.get(saintUrl("/webSSO/sso.jsp")) {
            headers {
                append(HttpHeaders.UserAgent, "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36")
                append(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
            }
        }
        println("U-Saint SSO status: ${saintSsoResponse.status}")

        val loginInfo = fetchLoginInfo(id)
        if (loginInfo.user_login != id) {
            throw IllegalStateException("로그인 사용자 검증에 실패했습니다.")
        }

        lmsId = id
        isLoggined = true
        println("LMS login succeeded.")
        return true
    }

    /**
     * 로그인된 사용자의 수강 학기 목록을 가져옵니다.
     *
     * @return 학기 목록
     */
    @Throws(Exception::class)
    @OptIn(ExperimentalTime::class)
    internal suspend fun getTerms(): List<Term> {
        checkLoggedIn()
        return fetchTerms()
    }

    /**
     * 로그인된 사용자의 개인 정보(이름, 학과 등)를 가져옵니다.
     *
     * @return 사용자 정보
     */
    @Throws(Exception::class)
    internal suspend fun getLoginInfo(): Info {
        checkLoggedIn()

        return fetchLoginInfo()
    }

    /**
     * 현재 로그인 세션의 LMS 쿠키 목록을 가져옵니다. 외부 세션 연동 시 사용됩니다.
     *
     * @return LMS 쿠키를 담은 세션 응답
     */
    @Throws(Exception::class)
    internal suspend fun getCookies(): LmsSessionResponse {
        checkLoggedIn()

        return fetchLmsSession()
    }

    internal suspend fun logout() {
        sessionMutex.lock()
        try {
            resetSession()
        } finally {
            sessionMutex.unlock()
        }
    }

    /**
     * LMS 로그인을 비동기 방식으로 수행하고 그 결과를 completion 콜백으로 전달합니다.
     *
     * @param id LMS 아이디
     * @param password LMS 비밀번호
     * @param completion 결과 수신 콜백
     */
    fun loginLMS(id: String, password: String, completion: (LmsLoginResult) -> Unit) {
        launchLoginResult(completion) {
            loginLMS(id, password)
        }
    }

    /**
     * 로그인된 사용자의 학기 목록을 비동기 방식으로 조회하고 그 결과를 completion 콜백으로 전달합니다.
     *
     * @param completion 결과 수신 콜백
     */
    @OptIn(ExperimentalTime::class)
    fun getTerms(completion: (LmsTermsResult) -> Unit) {
        launchTermsResult(completion) {
            getTerms()
        }
    }

    /**
     * 로그인된 사용자의 개인 정보 조회를 비동기 방식으로 수행하고 그 결과를 completion 콜백으로 전달합니다.
     *
     * @param completion 결과 수신 콜백
     */
    fun getLoginInfo(completion: (LmsLoginInfoResult) -> Unit) {
        launchLoginInfoResult(completion) {
            getLoginInfo()
        }
    }

    /**
     * 현재 로그인 세션의 LMS 쿠키 목록 조회를 비동기 방식으로 수행하고 그 결과를 completion 콜백으로 전달합니다.
     *
     * @param completion 결과 수신 콜백
     */
    fun getCookies(completion: (LmsCookiesResult) -> Unit) {
        launchCookiesResult(completion) {
            getCookies()
        }
    }

    /**
     * 현재 로그인 세션과 사용자별 캐시를 제거한 뒤 completion 콜백을 호출합니다.
     *
     * @param completion 로그아웃 완료 콜백
     */
    fun logout(completion: () -> Unit) {
        apiScope.launch {
            logout()
            completion()
        }
    }

    /**
     * 특정 학기의 수강 과목 상세 정보(할 일, 출석, 공지, 과제 등 전체 정보)를 비동기 방식으로 조회하고 그 결과를 completion 콜백으로 전달합니다.
     *
     * @param term 학기 정보
     * @param loadingState 조회 진행률 콜백 (0.0f ~ 1.0f)
     * @param completion 결과 수신 콜백
     */
    @ExperimentalTime
    fun getSubjects(
        term: Term,
        loadingState: (Float) -> Unit = {},
        completion: (LmsSubjectsResult) -> Unit,
    ) {
        launchSubjectsResult(completion) {
            loadSubjects(term, loadingState, SubjectLoadMode.Full)
        }
    }

    /**
     * 제출해야 할 과제, 동영상 시청 정보 등 할 일 중심 정보를 비동기 방식으로 빠르게 조회하고 그 결과를 completion 콜백으로 전달합니다.
     *
     * @param term 학기 정보
     * @param loadingState 조회 진행률 콜백 (0.0f ~ 1.0f)
     * @param completion 결과 수신 콜백
     */
    @ExperimentalTime
    fun getTodoList(
        term: Term,
        loadingState: (Float) -> Unit = {},
        completion: (LmsSubjectsResult) -> Unit,
    ) {
        getTodoList(
            term = term,
            loadingState = loadingState,
            postHogDistinctId = null,
            completion = completion,
        )
    }

    /**
     * 제출해야 할 과제, 동영상 시청 정보 등 할 일 중심 정보를 분석 식별자 정보와 함께 비동기 방식으로 조회하고 그 결과를 completion 콜백으로 전달합니다.
     *
     * @param term 학기 정보
     * @param loadingState 조회 진행률 콜백 (0.0f ~ 1.0f)
     * @param postHogDistinctId 분석용 식별자
     * @param completion 결과 수신 콜백
     */
    @ExperimentalTime
    fun getTodoList(
        term: Term,
        loadingState: (Float) -> Unit = {},
        postHogDistinctId: String?,
        completion: (LmsSubjectsResult) -> Unit,
    ) {
        launchSubjectsResult(completion) {
            loadSubjects(
                term = term,
                loadingState = loadingState,
                mode = SubjectLoadMode.TodoOnly,
                postHogDistinctId = postHogDistinctId,
            )
        }
    }

    /**
     * 특정 학기의 수강 과목 상세 정보(할 일, 출석, 공지, 과제 등 전체 정보)를 가져옵니다.
     *
     * @param term 학기 정보
     * @param loadingState 진행률 콜백 (0.0f ~ 1.0f)
     * @return 수강 과목 목록
     */
    @Throws(Exception::class)
    @ExperimentalTime
    internal suspend fun getSubjects(term: Term, loadingState: (Float) -> Unit = {}): List<Subject> {
        return loadSubjects(term, loadingState, SubjectLoadMode.Full)
    }

    /**
     * 제출해야 할 과제, 동영상 시청 정보 등 할 일 정보만 빠르게 가져옵니다. (SSU-Time 전용)
     *
     * @param term 학기 정보
     * @param loadingState 진행률 콜백 (0.0f ~ 1.0f)
     * @param postHogDistinctId 분석용 식별자
     * @return 할 일 정보가 포함된 수강 과목 목록
     */
    @Throws(Exception::class)
    @ExperimentalTime
    internal suspend fun getTodoList(
        term: Term,
        loadingState: (Float) -> Unit = {},
        postHogDistinctId: String? = null,
    ): List<Subject> {
        return loadSubjects(
            term = term,
            loadingState = loadingState,
            mode = SubjectLoadMode.TodoOnly,
            postHogDistinctId = postHogDistinctId,
        )
    }

    /**
     * 미제출 과제/동영상 비율 통계를 계산하여 가져옵니다.
     *
     * @param term 학기 정보
     * @param loadingState 진행률 콜백 (0.0f ~ 1.0f)
     * @return 미제출 통계 정보
     */
    @Throws(Exception::class)
    @ExperimentalTime
    internal suspend fun getUnsubmittedRatioStats(
        term: Term,
        loadingState: (Float) -> Unit = {},
    ): UnsubmittedStats {
        checkLoggedIn()

        val lectures = fetchLectures(term)
        loadingState(0.1f)

        val weight = if (lectures.isEmpty()) 0f else 0.9f / lectures.size
        var nowProgress = 0.1f
        val now = Clock.System.now()
        var totalCount = 0
        var unsubmittedCount = 0

        for (lecture in lectures) {
            nowProgress += weight
            loadingState(nowProgress)

            val assignmentStats = fetchSubmissions(lecture.id).first.toUnsubmittedStats(now)
            totalCount += assignmentStats.totalCount
            unsubmittedCount += assignmentStats.unsubmittedCount

            val commonsStats = fetchTodoDetail(lecture.id).toCommonsUnsubmittedStats(now)
            totalCount += commonsStats.totalCount
            unsubmittedCount += commonsStats.unsubmittedCount
        }

        loadingState(1f)
        return UnsubmittedStats(
            totalCount = totalCount,
            unsubmittedCount = unsubmittedCount,
        )
    }

    @ExperimentalTime
    private suspend fun loadSubjects(
        term: Term,
        loadingState: (Float) -> Unit = {},
        mode: SubjectLoadMode,
        postHogDistinctId: String? = null,
    ): List<Subject> {
        checkLoggedIn()

        val lectures = fetchLectures(term)
        loadingState(0.1f)

        val learnStatuses = if (mode == SubjectLoadMode.Full) fetchLearnStatuses(term) else null
        loadingState(0.2f)

        loadingState(0.3f)
        val learnStatusByCourseId = learnStatuses?.learnstatuses?.associateFirstById { it.course.id }.orEmpty()
        val weight = if (lectures.isEmpty()) 0f else 0.7f / lectures.size
        var nowProgress = 0.3f
        val now = Clock.System.now()
        val subjects = mutableListOf<Subject>()
        var totalCount = 0
        var unsubmittedCount = 0
        val shouldTrackPostHog = mode == SubjectLoadMode.TodoOnly && !postHogDistinctId.isNullOrBlank()
        val trackingItems = mutableListOf<TodoTrackingItem>()

        for (lecture in lectures) {
            nowProgress += weight
            loadingState(nowProgress)
            var permissionFailed = false
            val submissions: List<Submission> = fetchSubmissions(lecture.id).let {
                permissionFailed = it.second
                it.first
            }

            val assignmentMetadataById = if (submissions.isEmpty()) {
                emptyMap()
            } else {
                fetchAssignmentGroups(lecture.id).toAssignmentMetadataById()
            }
            applyAssignmentMetadata(submissions, assignmentMetadataById)

            val todoSubmissions = submissions
            val includeCommons: Boolean

            if (mode == SubjectLoadMode.Full) {
                includeCommons = true
            } else {
                includeCommons = lecture.activities.mayHaveCommonsTodos()

                val submissionTrackingItems = todoSubmissions.toSubmissionTrackingItems(
                    courseId = lecture.id,
                    now = now,
                )
                val stats = submissionTrackingItems.toUnsubmittedStats()
                totalCount += stats.totalCount
                unsubmittedCount += stats.unsubmittedCount
                if (shouldTrackPostHog) {
                    trackingItems += submissionTrackingItems
                }

                if (!lecture.activities.mayHaveTodoAssignments() && !includeCommons && todoSubmissions.isEmpty()) continue
            }

            val todoBuildResult = buildTodoListFromSubmissions(
                courseId = lecture.id,
                submissions = todoSubmissions,
                includeCommons = includeCommons,
                includeCommonsForTracking = shouldTrackPostHog,
            )
            if (mode == SubjectLoadMode.TodoOnly) {
                totalCount += todoBuildResult.commonsStats.totalCount
                unsubmittedCount += todoBuildResult.commonsStats.unsubmittedCount
                if (shouldTrackPostHog) {
                    trackingItems += todoBuildResult.commonsTrackingItems
                }
            }

            println(lecture.name)
            val todoList = todoBuildResult.todoList
            val subjectSubmissions = submissions + todoBuildResult.completedCommonsSubmissions

            subjects += Subject(
                id = lecture.id,
                termId = lecture.term_id,
                termName = term.name ?: "학기정보 없음",
                name = lecture.name,
                professor = lecture.professors,
                totalStudents = lecture.total_students,
                todoList = todoList,
                attendances = if (mode == SubjectLoadMode.Full && !permissionFailed) learnStatusByCourseId[lecture.id]?.sections?.map { section ->
                    section.subsections.map { sub ->
                        when (sub.status) {
                            "attendance" -> AttendanceType.ATTENDANCE
                            "absent" -> AttendanceType.ABSENT
                            "late" -> AttendanceType.LATE
                            else -> AttendanceType.NONE
                        }
                    }
                } ?: emptyList() else emptyList(),
                discussions = if (mode == SubjectLoadMode.Full && !permissionFailed) fetchDiscussions(lecture.id) else emptyList(),
                submissions = subjectSubmissions,
                scoredAssignments = if (mode == SubjectLoadMode.Full && !permissionFailed) {
                    buildScoredAssignments(submissions, assignmentMetadataById)
                } else {
                    emptyList()
                },
                permissionFailed = permissionFailed
            )
        }

        if (mode == SubjectLoadMode.TodoOnly) {
            trackTodoSync(
                UnsubmittedStats(
                    totalCount = totalCount,
                    unsubmittedCount = unsubmittedCount,
                ),
                trackingItems,
                postHogDistinctId,
            )
        }

        return subjects
    }

    private fun escapeStr(text: String): String {
        val owned = StringBuilder()
        for (char in text) {
            val code = char.code
            val special = !(char in '0'..'9' || char in 'a'..'z' || char in 'A'..'Z' || char == '-' || char == '.' || char == '_')
            if (special) {
                owned.append("~")
                val hex = code.toString(16).uppercase()
                when (hex.length) {
                    1 -> owned.append("000").append(hex)
                    2 -> owned.append("00").append(hex)
                    3 -> owned.append("0").append(hex)
                    else -> owned.append(hex)
                }
            } else {
                owned.append(char)
            }
        }
        return owned.toString()
    }

    private suspend fun getTimetable(url: String): Timetable {
        val html = fetchWebDynproHtml(url, "ZCMW2102")
        return parseTimetable(html)
    }

    @Throws(Exception::class)
    suspend fun getTimetable(): Timetable = getTimetable(null, null)

    @Throws(Exception::class)
    suspend fun getTimetable(year: String?, semester: Semester?): Timetable {
        checkLoggedIn()

        val currentHtml = fetchWebDynproHtml("https://ecc.ssu.ac.kr:8443/sap/bc/webdynpro/SAP/ZCMW2102", "ZCMW2102")

        val cached = webDynproCache["ZCMW2102"] ?: throw IllegalStateException("시간표 페이지 세션을 초기화하지 못했습니다.")
        val secureId = cached.first
        val formAction = cached.second

        val peryrLabelMatch = Regex("""<label\b[^>]*\bfor="([^"]+)"[^>]*>(?:(?!</?label\b).)*?학년도""", RegexOption.IGNORE_CASE).find(currentHtml)
        val peryrMatch = Regex("""id="([^"]+:VIW_MAIN\.PERYR)"""").find(currentHtml)
        val peryrId = peryrLabelMatch?.groupValues?.get(1)
            ?: peryrMatch?.groupValues?.get(1)
            ?: "ZCMW_PERIOD_RE.ID_0DC742680F42DA9747594D1AE51A0C69:VIW_MAIN.PERYR"

        val peridLabelMatch = Regex("""<label\b[^>]*\bfor="([^"]+)"[^>]*>(?:(?!</label>).)*?학기""", RegexOption.IGNORE_CASE).find(currentHtml)
        val peridMatch = Regex("""id="([^"]+:VIW_MAIN\.PERID)"""").find(currentHtml)
        val peridId = peridLabelMatch?.groupValues?.get(1)
            ?: peridMatch?.groupValues?.get(1)
            ?: "ZCMW_PERIOD_RE.ID_0DC742680F42DA9747594D1AE51A0C69:VIW_MAIN.PERID"

        val btnSearchLabelMatch = Regex("""<(?:div|button)\b[^>]*\bid="([^"]+)"[^>]*ct="B"[^>]*>(?:(?!<(?:div|button)\b).)*?조회""", RegexOption.IGNORE_CASE).find(currentHtml)
        val btnSearchMatch = Regex("""id="([^"]+:VIW_MAIN\.BTN_SEARCH)"""").find(currentHtml)
        val btnSearchId = btnSearchLabelMatch?.groupValues?.get(1)
            ?: btnSearchMatch?.groupValues?.get(1)
            ?: "ZCMW2102.ID_0001:VIW_MAIN.BTN_SEARCH"

        val buttonEvent = "Button_Press~E002Id~E004${btnSearchId}~E003~E002ClientAction~E004submit~E005ResponseData~E004delta~E003~E002~E003"
        val focusInfo = escapeStr("{\"sFocussedId\":\"${btnSearchId}\"}")
        val buttonFormReq = "Form_Request~E002Id~E004sap.client.SsrClient.form~E005Async~E004false~E005FocusInfo~E004${focusInfo}~E005Hash~E004~E005DomChanged~E004false~E005IsDirty~E004false~E003~E002ResponseData~E004delta~E003~E002~E003"

        val eventQueue = if (year != null && semester != null) {
            val yearEvent = "ComboBox_Select~E002Id~E004${peryrId}~E005Key~E004${year}~E005ByEnter~E004false~E003~E002ClientAction~E004submit~E005ResponseData~E004delta~E003~E002~E003"
            val semesterEvent = "ComboBox_Select~E002Id~E004${peridId}~E005Key~E004${semester.code}~E005ByEnter~E004false~E003~E002ClientAction~E004submit~E005ResponseData~E004delta~E003~E002~E003"
            listOf(yearEvent, semesterEvent, buttonEvent, buttonFormReq).joinToString("~E001")
        } else {
            listOf(buttonEvent, buttonFormReq).joinToString("~E001")
        }

        val actionFullUrl = if (formAction.startsWith("http")) formAction else "https://ecc.ssu.ac.kr:8443$formAction"
        val response = client.submitForm(
            url = actionFullUrl,
            formParameters = parameters {
                append("sap-charset", "utf-8")
                append("sap-wd-secure-id", secureId)
                append("fesrAppName", "ZCMW2102")
                append("fesrUseBeacon", "true")
                append("SAPEVENTQUEUE", eventQueue)
            }
        ) {
            headers {
                append(HttpHeaders.UserAgent, "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36")
                append(HttpHeaders.Accept, "*/*")
                append("X-Requested-With", "XMLHttpRequest")
                append(HttpHeaders.ContentType, "application/x-www-form-urlencoded; charset=UTF-8")
            }
        }
        
        val html = response.bodyAsText()
        return parseTimetable(html)
    }

    fun getTimetable(year: String?, semester: Semester?, completion: (LmsTimetableResult) -> Unit) {
        apiScope.launch {
            val result = try {
                LmsTimetableResult(success = true, timetable = getTimetable(year, semester))
            } catch (throwable: Throwable) {
                LmsTimetableResult(success = false, errorMessage = throwable.toResultMessage())
            }
            completion(result)
        }
    }

    fun getTimetable(completion: (LmsTimetableResult) -> Unit) {
        getTimetable(null, null, completion)
    }

    fun parseTimetable(html: String): Timetable {
        // Extract Year and Semester
        val labelRegex = Regex("""<label\b[^>]*for="([^"]+)"[^>]*>(?:(?!</label>)[\s\S])*?학년도""", RegexOption.IGNORE_CASE)
        val inputValRegex = { id: String -> Regex("""id="$id"[^>]*value="([^"]+)"""", RegexOption.IGNORE_CASE) }
        
        var year = ""
        labelRegex.find(html)?.groupValues?.get(1)?.let { id ->
            year = inputValRegex(id).find(html)?.groupValues?.get(1)?.decodeHtmlEntities() ?: ""
        }
        
        val semesterLabelRegex = Regex("""<label\b[^>]*for="([^"]+)"[^>]*>(?:(?!</label>)[\s\S])*?학기""", RegexOption.IGNORE_CASE)
        var semester = ""
        semesterLabelRegex.find(html)?.groupValues?.get(1)?.let { id ->
            semester = inputValRegex(id).find(html)?.groupValues?.get(1)?.decodeHtmlEntities() ?: ""
        }
        
        if (year.isBlank()) {
            year = Regex("""value="([^"]*학년도)"""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.decodeHtmlEntities() ?: ""
        }
        if (semester.isBlank()) {
            semester = Regex("""value="([^"]*학기)"""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.decodeHtmlEntities() ?: ""
        }

        // Now isolate the scroll table that contains the timetable grid
        val targetHeaderIndex = html.indexOf("title=\"월요일\"", ignoreCase = true).let {
            if (it == -1) html.indexOf("title=\"월\"", ignoreCase = true) else it
        }
        
        var tableContent = html
        if (targetHeaderIndex != -1) {
            val ctIndex = html.lastIndexOf("ct=\"ST\"", targetHeaderIndex, ignoreCase = true)
            val searchStart = if (ctIndex != -1) ctIndex else targetHeaderIndex
            val tableStart = html.lastIndexOf("<table", searchStart, ignoreCase = true)
            if (tableStart != -1) {
                // Find closing tag of outer table
                var index = tableStart
                var openCount = 0
                val pattern = Regex("""</?table\b""", RegexOption.IGNORE_CASE)
                var tableEnd = html.length
                while (true) {
                    val match = pattern.find(html, index) ?: break
                    if (match.value.startsWith("</", ignoreCase = true)) {
                        openCount--
                        if (openCount == 0) {
                            tableEnd = match.range.last + 1
                            break
                        }
                    } else {
                        openCount++
                    }
                    index = match.range.last + 1
                }
                tableContent = html.substring(tableStart, tableEnd)
            }
        }

        // Parse dynamic headers to map column index to day of the week
        val thPattern = Regex("""<th\b[^>]*role="columnheader"[^>]*>""", RegexOption.IGNORE_CASE)
        val titlePattern = Regex("""title="([^"]+)"""", RegexOption.IGNORE_CASE)
        val lsdataPattern = Regex("""7:'([^']+)'""")
        val textPattern = Regex("""<span[^>]*ct="CP"[^>]*>([^<]+)</span>""", RegexOption.IGNORE_CASE)

        val ths = thPattern.findAll(tableContent)
        val headers = ths.map { match ->
            val thTag = match.value
            var title = titlePattern.find(thTag)?.groupValues?.get(1)
            if (title != null) return@map title
            
            val startIdx = tableContent.indexOf(thTag)
            val nextThIdx = tableContent.indexOf("<th", startIdx + 1, ignoreCase = true)
            val endIdx = if (nextThIdx != -1) nextThIdx else tableContent.length
            val thFullContent = tableContent.substring(startIdx, endIdx)
            
            title = lsdataPattern.find(thFullContent)?.groupValues?.get(1)
            if (title != null) return@map title
            
            title = textPattern.find(thFullContent)?.groupValues?.get(1)
            if (title != null) return@map title
            
            ""
        }.toList()

        fun getDayOfWeek(colIndex: Int): String {
            if (colIndex in headers.indices && headers[colIndex].isNotBlank()) {
                return headers[colIndex]
            }
            val defaults = listOf("시간", "월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일")
            return if (colIndex in defaults.indices) defaults[colIndex] else "알 수 없음"
        }

        // Extract table body / rows
        val tbodyStart = tableContent.indexOf("<tbody", ignoreCase = true)
        val tbodyContent = if (tbodyStart != -1) tableContent.substring(tbodyStart) else tableContent

        val trRegex = Regex("""<tr\b[^>]*>([\s\S]*?)</tr>""", RegexOption.IGNORE_CASE)
        val tdRegex = Regex("""<td\b[^>]*cc="(\d+)"[^>]*>([\s\S]*?)</td>""", RegexOption.IGNORE_CASE)

        val timetableCells = mutableListOf<TimetableCell>()

        val trs = trRegex.findAll(tbodyContent)
        for (trMatch in trs) {
            val trContent = trMatch.groupValues[1]
            val tds = tdRegex.findAll(trContent).toList()
            if (tds.isEmpty()) continue

            // Column 0 is the period info: "1 교시\n(08:00-08:50)"
            val periodTd = tds.firstOrNull { it.groupValues[1] == "0" } ?: continue
            val periodText = periodTd.groupValues[2].stripHtmlTags()
            if (periodText.isBlank()) continue

            val periodParts = periodText.split("\n")
            val periodName = periodParts.getOrNull(0) ?: ""
            val periodTime = periodParts.getOrNull(1) ?: ""

            // Process remaining cells (course slots)
            for (tdMatch in tds) {
                val colIndexStr = tdMatch.groupValues[1]
                val colIndex = colIndexStr.toIntOrNull() ?: continue
                if (colIndex == 0) continue

                val cellHtml = tdMatch.groupValues[2]
                if (cellHtml.contains("lsSTEmptyRow") || cellHtml.contains("비어있음") || cellHtml.contains("비어 임")) {
                    continue
                }

                val cellText = cellHtml.stripHtmlTags()
                if (cellText.isBlank()) continue

                val lines = cellText.split("\n")
                val subject = lines.getOrNull(0) ?: ""
                val professor = lines.getOrNull(1) ?: ""
                val time = lines.getOrNull(2) ?: ""
                val classroom = lines.getOrNull(3) ?: ""

                if (subject.isNotBlank()) {
                    timetableCells.add(
                        TimetableCell(
                            dayOfWeek = DayOfWeek.fromKoreanName(getDayOfWeek(colIndex)) ?: DayOfWeek.MONDAY,
                            period = periodName,
                            periodTime = periodTime,
                            subject = subject,
                            professor = professor,
                            time = time,
                            classroom = classroom
                        )
                    )
                }
            }
        }

        return Timetable(
            year = year,
            semester = semester,
            items = timetableCells
        )
    }

    private suspend fun getGraduateTable(url: String): GraduateTable {
        val html = fetchWebDynproHtml(url, "ZCMW8015")
        return parseGraduateTable(html)
    }

    internal suspend fun fetchWebDynproHtml(url: String, appName: String): String {
        checkLoggedIn()
        
        val cached = webDynproCache[appName]
        if (cached != null) {
            val (secureId, formAction) = cached
            try {
                val html = postEventQueue(url, appName, secureId, formAction)
                if (isValidWebDynproResponse(html)) {
                    println("[WebDynpro Cache] Hit - Reusing cached session for app: $appName")
                    return html
                } else {
                    println("[WebDynpro Cache] Miss (Expired/Invalid) - Cached session for app: $appName failed validation. Re-fetching fresh context...")
                }
            } catch (e: Exception) {
                println("[WebDynpro Cache] Miss (Error) - Request with cached session for app: $appName failed. Re-fetching fresh context...")
            }
            webDynproCache.remove(appName)
        } else {
            println("[WebDynpro Cache] Miss (Cold) - No cached session found for app: $appName. Fetching fresh context...")
        }

        val response = client.get(url) {
            headers {
                append(HttpHeaders.UserAgent, "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36")
                append(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
            }
        }
        val html = response.bodyAsText().decodeHtmlEntities().decodeHtmlEntities()
        
        val secureId = Regex("""name="sap-wd-secure-id"\s+value="([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1) ?: ""
        val formAction = Regex("""<form\s+[^>]*id="sap\.client\.SsrClient\.form"[^>]*action="([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.decodeHtmlEntities()?.decodeHtmlEntities() ?: ""
            
        if (secureId.isNotBlank() && formAction.isNotBlank()) {
            webDynproCache[appName] = Pair(secureId, formAction)
            try {
                val eventHtml = postEventQueue(url, appName, secureId, formAction)
                if (isValidWebDynproResponse(eventHtml)) {
                    val finalSecureId = Regex("""name="sap-wd-secure-id"\s+value="([^"]+)"""", RegexOption.IGNORE_CASE)
                        .find(eventHtml)?.groupValues?.get(1) ?: secureId
                    val finalFormActionNew = Regex("""<form\s+[^>]*id="sap\.client\.SsrClient\.form"[^>]*action="([^"]+)"""", RegexOption.IGNORE_CASE)
                        .find(eventHtml)?.groupValues?.get(1)?.decodeHtmlEntities()?.decodeHtmlEntities() ?: formAction
                    
                    webDynproCache[appName] = Pair(finalSecureId, finalFormActionNew)
                    return eventHtml
                }
            } catch (e: Exception) {
                // Ignore exception
            }
        }
        
        return html
    }

    private fun isValidWebDynproResponse(html: String): Boolean {
        return !html.contains("로그온 준비 중입니다.") && !html.contains("sap-system-login")
    }

    private suspend fun postEventQueue(url: String, appName: String, secureId: String, formAction: String): String {
        val initialDataWd01 = "ClientWidth:1920px;ClientHeight:1000px;ScreenWidth:1920px;ScreenHeight:1080px;ScreenOrientation:landscape;ThemedTableRowHeight:33px;ThemedFormLayoutRowHeight:32px;ThemedSvgLibUrls:{\"SAPGUI-icons\":\"https://ecc.ssu.ac.kr:8443/sap/public/bc/ur/nw5/themes/~cache-20210223121230/Base/baseLib/sap_fiori_3/svg/libs/SAPGUI-icons.svg\",\"SAPWeb-icons\":\"https://ecc.ssu.ac.kr:8443/sap/public/bc/ur/nw5/themes/~cache-20210223121230/Base/baseLib/sap_fiori_3/svg/libs/SAPGUI-icons.svg\"};ThemeTags:Fiori_3,Touch;ThemeID:sap_fiori_3;SapThemeID:sap_fiori_3;DeviceType:DESKTOP"
        val e1 = "WD01_Notify~E002Id~E004WD01~E005Data~E004${escapeStr(initialDataWd01)}~E003~E002ResponseData~E004delta~E005EnqueueCardinality~E004single~E003~E002~E003"

        val initialDataWd02 = "ThemedTableRowHeight:25px"
        val e2 = "WD02_Notify~E002Id~E004WD02~E005Data~E004${escapeStr(initialDataWd02)}~E003~E002ResponseData~E004delta~E005EnqueueCardinality~E004single~E003~E002~E003"

        val e3 = "_loadingPlaceholder__Load~E002Id~E004_loadingPlaceholder_~E003~E002ClientAction~E004submit~E005ResponseData~E004delta~E003~E002~E003"

        val e4Params = mapOf(
            "Id" to "WD01",
            "WindowOpenerExists" to "true",
            "ClientURL" to url,
            "ClientWidth" to "1920",
            "ClientHeight" to "1000",
            "DocumentDomain" to "ssu.ac.kr",
            "IsTopWindow" to "true",
            "ParentAccessible" to "true"
        )
        val e4ParamsSerialized = e4Params.entries.joinToString("~E005") { "${it.key}~E004${escapeStr(it.value)}" }
        val e4 = "Custom_ClientInfos~E002${e4ParamsSerialized}~E003~E002ClientAction~E004enqueue~E005ResponseData~E004delta~E003~E002~E003"

        val e5 = "Form_Request~E002FocusInfo~E004~E005Id~E004sap.client.SsrClient.form~E005Async~E004false~E005Hash~E004~E005IsDirty~E004false~E005DomChanged~E004false~E003~E002~E003~E002~E003"

        val eventQueue = listOf(e1, e2, e3, e4, e5).joinToString("~E001")

        val actionFullUrl = if (formAction.startsWith("http")) formAction else "https://ecc.ssu.ac.kr:8443$formAction"
        
        val eventResponse = client.submitForm(
            url = actionFullUrl,
            formParameters = parameters {
                append("sap-charset", "utf-8")
                append("sap-wd-secure-id", secureId)
                append("fesrAppName", appName)
                append("fesrUseBeacon", "true")
                append("SAPEVENTQUEUE", eventQueue)
            }
        ) {
            headers {
                append(HttpHeaders.UserAgent, "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36")
                append(HttpHeaders.Accept, "*/*")
                append("X-Requested-With", "XMLHttpRequest")
                append(HttpHeaders.ContentType, "application/x-www-form-urlencoded; charset=UTF-8")
            }
        }

        val eventResponseBody = eventResponse.bodyAsText()
        val tableHtml = if (eventResponseBody.contains("<![CDATA[")) {
            eventResponseBody.substringAfter("<![CDATA[").substringBefore("]]>")
        } else {
            eventResponseBody
        }

        return tableHtml
    }

    /**
     * 유세인트 졸업사정표 정보를 조회하여 가져옵니다.
     *
     * @return 유세인트 졸업사정표 정보
     */
    @Throws(Exception::class)
    suspend fun getGraduateTable(): GraduateTable {
        checkLoggedIn()
        return getGraduateTable("https://ecc.ssu.ac.kr:8443/sap/bc/webdynpro/SAP/ZCMW8015")
    }

    /**
     * 유세인트 졸업사정표 정보를 비동기 방식으로 조회하고 그 결과를 completion 콜백으로 전달합니다.
     *
     * @param completion 결과 수신 콜백
     */
    fun getGraduateTable(completion: (LmsGraduateTableResult) -> Unit) {
        apiScope.launch {
            val result = try {
                LmsGraduateTableResult(success = true, graduateTable = getGraduateTable())
            } catch (throwable: Throwable) {
                LmsGraduateTableResult(success = false, errorMessage = throwable.toResultMessage())
            }
            completion(result)
        }
    }

    fun parseGraduateTable(html: String): GraduateTable {
        val decodedHtml = html.decodeHtmlEntities()
        val trRegex = Regex("""<tr\b[^>]*>([\s\S]*?)</tr>""", RegexOption.IGNORE_CASE)
        val tdRegex = Regex("""<td\b[^>]*>([\s\S]*?)</td>""", RegexOption.IGNORE_CASE)
        
        val cellsList = mutableListOf<GraduateTableCell>()
        val trMatches = trRegex.findAll(decodedHtml)
        var currentClassification = ""

        for (trMatch in trMatches) {
            val trContent = trMatch.groupValues[1]
            val tds = tdRegex.findAll(trContent).toList()
            if (tds.size == 6) {
                val classification = tds[0].groupValues[1].stripHtmlTags()
                val requirement = tds[1].groupValues[1].stripHtmlTags()
                val standardValue = tds[2].groupValues[1].stripHtmlTags()
                val calculatedValue = tds[3].groupValues[1].stripHtmlTags()
                val difference = tds[4].groupValues[1].stripHtmlTags()
                val result = tds[5].groupValues[1].stripHtmlTags()
                
                if (classification.isNotBlank() && classification != "졸업사정일자" && classification != "이수구분" && requirement.isNotBlank()) {
                    currentClassification = classification
                    cellsList.add(
                        GraduateTableCell(
                            classification = classification,
                            requirement = requirement,
                            standardValue = standardValue,
                            calculatedValue = calculatedValue,
                            difference = difference,
                            result = result
                        )
                    )
                }
            } else if (tds.size == 5) {
                val requirement = tds[0].groupValues[1].stripHtmlTags()
                val standardValue = tds[1].groupValues[1].stripHtmlTags()
                val calculatedValue = tds[2].groupValues[1].stripHtmlTags()
                val difference = tds[3].groupValues[1].stripHtmlTags()
                val result = tds[4].groupValues[1].stripHtmlTags()
                
                if (currentClassification.isNotBlank() && requirement.isNotBlank()) {
                    cellsList.add(
                        GraduateTableCell(
                            classification = currentClassification,
                            requirement = requirement,
                            standardValue = standardValue,
                            calculatedValue = calculatedValue,
                            difference = difference,
                            result = result
                        )
                    )
                }
            }
        }
        
        return GraduateTable(items = cellsList)
    }

    private suspend fun getTuitionTable(url: String): TuitionTable {
        val html = fetchWebDynproHtml(url, "ZCMW6520n")
        return parseTuitionTable(html)
    }

    /**
     * 유세인트 등록금 납부 이력 정보를 조회하여 가져옵니다.
     *
     * @return 유세인트 등록금 납부 내역 데이터
     */
    @Throws(Exception::class)
    suspend fun getTuitionTable(): TuitionTable {
        checkLoggedIn()
        return getTuitionTable("https://ecc.ssu.ac.kr:8443/sap/bc/webdynpro/SAP/ZCMW6520n")
    }

    /**
     * 유세인트 등록금 납부 이력 정보를 비동기 방식으로 조회하고 그 결과를 completion 콜백으로 전달합니다.
     *
     * @param completion 결과 수신 콜백
     */
    fun getTuitionTable(completion: (LmsTuitionResult) -> Unit) {
        apiScope.launch {
            val result = try {
                LmsTuitionResult(success = true, tuitionTable = getTuitionTable())
            } catch (throwable: Throwable) {
                LmsTuitionResult(success = false, errorMessage = throwable.toResultMessage())
            }
            completion(result)
        }
    }

    fun parseTuitionTable(html: String): TuitionTable {
        val decodedHtml = html.decodeHtmlEntities()
        val trRegex = Regex("""<tr\b[^>]*>([\s\S]*?)</tr>""", RegexOption.IGNORE_CASE)
        val tdRegex = Regex("""<td\b[^>]*>([\s\S]*?)</td>""", RegexOption.IGNORE_CASE)
        
        val cellsList = mutableListOf<TuitionCell>()
        val trMatches = trRegex.findAll(decodedHtml)
        for (trMatch in trMatches) {
            val trContent = trMatch.groupValues[1]
            val tds = tdRegex.findAll(trContent).toList()
            if (tds.size == 14) {
                val year = tds[1].groupValues[1].stripHtmlTags()
                val semester = tds[2].groupValues[1].stripHtmlTags()
                val grade = tds[3].groupValues[1].stripHtmlTags()
                val registrationType = tds[4].groupValues[1].stripHtmlTags()
                val registrationDate = tds[5].groupValues[1].stripHtmlTags()
                val amount = tds[6].groupValues[1].stripHtmlTags()
                val reduction = tds[7].groupValues[1].stripHtmlTags()
                val paymentAmount = tds[8].groupValues[1].stripHtmlTags()
                
                if (year.contains("학년도") && semester.isNotBlank()) {
                    cellsList.add(
                        TuitionCell(
                            year = year,
                            semester = semester,
                            grade = grade,
                            registrationType = registrationType,
                            registrationDate = registrationDate,
                            amount = amount,
                            reduction = reduction,
                            paymentAmount = paymentAmount
                        )
                    )
                }
            }
        }
        
        return TuitionTable(items = cellsList)
    }

    private suspend fun getScholarshipHistoryTable(url: String): ScholarshipHistoryTable {
        val html = fetchWebDynproHtml(url, "ZCMW7530n")
        return parseScholarshipHistoryTable(html)
    }

    /**
     * 유세인트 장학 수혜 이력 정보를 조회하여 가져옵니다.
     *
     * @return 유세인트 장학 수혜 내역 데이터
     */
    @Throws(Exception::class)
    suspend fun getScholarshipHistoryTable(): ScholarshipHistoryTable {
        checkLoggedIn()
        return getScholarshipHistoryTable("https://ecc.ssu.ac.kr:8443/sap/bc/webdynpro/SAP/ZCMW7530n")
    }

    /**
     * 유세인트 장학 수혜 이력 정보를 비동기 방식으로 조회하고 그 결과를 completion 콜백으로 전달합니다.
     *
     * @param completion 결과 수신 콜백
     */
    fun getScholarshipHistoryTable(completion: (LmsScholarshipHistoryResult) -> Unit) {
        apiScope.launch {
            val result = try {
                LmsScholarshipHistoryResult(success = true, scholarshipHistoryTable = getScholarshipHistoryTable())
            } catch (throwable: Throwable) {
                LmsScholarshipHistoryResult(success = false, errorMessage = throwable.toResultMessage())
            }
            completion(result)
        }
    }

    fun parseScholarshipHistoryTable(html: String): ScholarshipHistoryTable {
        val decodedHtml = html.decodeHtmlEntities()
        val trRegex = Regex("""<tr\b[^>]*>([\s\S]*?)</tr>""", RegexOption.IGNORE_CASE)
        val tdRegex = Regex("""<td\b[^>]*>([\s\S]*?)</td>""", RegexOption.IGNORE_CASE)
        
        val cellsList = mutableListOf<ScholarshipHistoryCell>()
        val trMatches = trRegex.findAll(decodedHtml)
        for (trMatch in trMatches) {
            val trContent = trMatch.groupValues[1]
            val tds = tdRegex.findAll(trContent).toList()
            if (tds.size == 15) {
                val year = tds[1].groupValues[1].stripHtmlTags()
                val semester = tds[2].groupValues[1].stripHtmlTags()
                val scholarshipName = tds[3].groupValues[1].stripHtmlTags()
                val paymentMethod = tds[4].groupValues[1].stripHtmlTags()
                val processStatus = tds[5].groupValues[1].stripHtmlTags()
                val note = tds[6].groupValues[1].stripHtmlTags()
                val dropReason = tds[7].groupValues[1].stripHtmlTags()
                val processDate = tds[8].groupValues[1].stripHtmlTags()
                val selectedAmount = tds[9].groupValues[1].stripHtmlTags()
                val actualAmount = tds[10].groupValues[1].stripHtmlTags()
                val redeemedAmount = tds[11].groupValues[1].stripHtmlTags()
                val replacedAmount = tds[12].groupValues[1].stripHtmlTags()
                val replacedScholarshipName = tds[13].groupValues[1].stripHtmlTags()
                val workDepartment = tds[14].groupValues[1].stripHtmlTags()
                
                if (year.isNotBlank() && year.firstOrNull()?.isDigit() == true) {
                    cellsList.add(
                        ScholarshipHistoryCell(
                            year = year,
                            semester = semester,
                            scholarshipName = scholarshipName,
                            paymentMethod = paymentMethod,
                            processStatus = processStatus,
                            note = note,
                            dropReason = dropReason,
                            processDate = processDate,
                            selectedAmount = selectedAmount,
                            actualAmount = actualAmount,
                            redeemedAmount = redeemedAmount,
                            replacedAmount = replacedAmount,
                            replacedScholarshipName = replacedScholarshipName,
                            workDepartment = workDepartment
                        )
                    )
                }
            }
        }
        
        return ScholarshipHistoryTable(items = cellsList)
    }

    /**
     * 유세인트 성적 조회 정보를 가져옵니다.
     * 특정 학년도와 학기를 지정하여 조회하거나, null 지정 시 캐싱된 최신 성적을 가져옵니다.
     *
     * @param year 학년도 (예: "2026")
     * @param semester 학기 정보
     * @return 유세인트 성적 데이터 테이블
     */
    @Throws(Exception::class)
    suspend fun getGradeTable(year: String? = null, semester: Semester? = null): GradeTable {
        checkLoggedIn()

        if (year == null && semester == null) {
            val cachedYear = cachedLatestGradeYear
            val cachedSem = cachedLatestGradeSemester
            if (cachedYear != null && cachedSem != null) {
                return getGradeTable(cachedYear, cachedSem)
            }
        }
        
        var currentHtml = fetchWebDynproHtml("https://ecc.ssu.ac.kr:8443/sap/bc/webdynpro/SAP/ZCMB3W0017", "ZCMB3W0017")
        
        if (cachedLatestGradeYear == null || cachedLatestGradeSemester == null) {
            val defaultYear = parseYearFromHtml(currentHtml)
            val defaultSem = parseSemesterFromHtml(currentHtml)
            if (defaultYear.isNotBlank() && defaultSem != null) {
                cachedLatestGradeYear = defaultYear
                cachedLatestGradeSemester = defaultSem
            }
        }

        val cached = webDynproCache["ZCMB3W0017"] ?: throw IllegalStateException("성적 페이지 세션을 초기화하지 못했습니다.")
        val secureId = cached.first
        val formAction = cached.second

        val peryrLabelMatch = Regex("""<label\b[^>]*\bfor="([^"]+)"[^>]*>(?:(?!</?label\b).)*?학년도""", RegexOption.IGNORE_CASE).find(currentHtml)
        val peryrMatch = Regex("""id="([^"]+:VIW_MAIN\.PERYR)"""").find(currentHtml)
        val peryrId = peryrLabelMatch?.groupValues?.get(1)
            ?: peryrMatch?.groupValues?.get(1)
            ?: "ZCMW_PERIOD_RE.ID_0DC742680F42DA9747594D1AE51A0C69:VIW_MAIN.PERYR"

        val peridLabelMatch = Regex("""<label\b[^>]*\bfor="([^"]+)"[^>]*>(?:(?!</label>).)*?학기""", RegexOption.IGNORE_CASE).find(currentHtml)
        val peridMatch = Regex("""id="([^"]+:VIW_MAIN\.PERID)"""").find(currentHtml)
        val peridId = peridLabelMatch?.groupValues?.get(1)
            ?: peridMatch?.groupValues?.get(1)
            ?: "ZCMW_PERIOD_RE.ID_0DC742680F42DA9747594D1AE51A0C69:VIW_MAIN.PERID"

        val btnSearchLabelMatch = Regex("""<(?:div|button)\b[^>]*\bid="([^"]+)"[^>]*ct="B"[^>]*>(?:(?!<(?:div|button)\b).)*?조회""", RegexOption.IGNORE_CASE).find(currentHtml)
        val btnSearchMatch = Regex("""id="([^"]+:VIW_MAIN\.BTN_SEARCH)"""").find(currentHtml)
        val btnSearchId = btnSearchLabelMatch?.groupValues?.get(1)
            ?: btnSearchMatch?.groupValues?.get(1)
            ?: "ZCMB3W0017.ID_0001:VIW_MAIN.BTN_SEARCH"

        val buttonEvent = "Button_Press~E002Id~E004${btnSearchId}~E003~E002ClientAction~E004submit~E005ResponseData~E004delta~E003~E002~E003"
        val focusInfo = escapeStr("{\"sFocussedId\":\"${btnSearchId}\"}")
        val buttonFormReq = "Form_Request~E002Id~E004sap.client.SsrClient.form~E005Async~E004false~E005FocusInfo~E004${focusInfo}~E005Hash~E004~E005DomChanged~E004false~E005IsDirty~E004false~E003~E002ResponseData~E004delta~E003~E002~E003"

        val eventQueue = if (year != null && semester != null) {
            val yearEvent = "ComboBox_Select~E002Id~E004${peryrId}~E005Key~E004${year}~E005ByEnter~E004false~E003~E002ClientAction~E004submit~E005ResponseData~E004delta~E003~E002~E003"
            val semesterEvent = "ComboBox_Select~E002Id~E004${peridId}~E005Key~E004${semester.code}~E005ByEnter~E004false~E003~E002ClientAction~E004submit~E005ResponseData~E004delta~E003~E002~E003"
            listOf(yearEvent, semesterEvent, buttonEvent, buttonFormReq).joinToString("~E001")
        } else {
            listOf(buttonEvent, buttonFormReq).joinToString("~E001")
        }

        val actionFullUrl = if (formAction.startsWith("http")) formAction else "https://ecc.ssu.ac.kr:8443$formAction"
        val response = client.submitForm(
            url = actionFullUrl,
            formParameters = parameters {
                append("sap-charset", "utf-8")
                append("sap-wd-secure-id", secureId)
                append("fesrAppName", "ZCMB3W0017")
                append("fesrUseBeacon", "true")
                append("SAPEVENTQUEUE", eventQueue)
            }
        ) {
            headers {
                append(HttpHeaders.UserAgent, "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36")
                append(HttpHeaders.Accept, "*/*")
                append("X-Requested-With", "XMLHttpRequest")
                append(HttpHeaders.ContentType, "application/x-www-form-urlencoded; charset=UTF-8")
            }
        }
        val resultHtml = response.bodyAsText().decodeHtmlEntities().decodeHtmlEntities()
        
        val nextSecureId = Regex("""name="sap-wd-secure-id"\s+value="([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(resultHtml)?.groupValues?.get(1) ?: secureId
        val nextFormAction = Regex("""<form\s+[^>]*id="sap\.client\.SsrClient\.form"[^>]*action="([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(resultHtml)?.groupValues?.get(1)?.decodeHtmlEntities()?.decodeHtmlEntities() ?: formAction

        webDynproCache["ZCMB3W0017"] = Pair(nextSecureId, nextFormAction)
        val gradeTable = parseGradeTable(resultHtml, year, semester)

        if (year == null && semester == null) {
            cachedLatestGradeYear = gradeTable.year
            cachedLatestGradeSemester = gradeTable.semester
        }

        return gradeTable
    }

    /**
     * 특정 학기의 유세인트 성적 조회 정보를 비동기 방식으로 조회하고 그 결과를 completion 콜백으로 전달합니다.
     *
     * @param year 학년도 (예: "2026")
     * @param semester 학기 정보
     * @param completion 결과 수신 콜백
     */
    fun getGradeTable(year: String?, semester: Semester?, completion: (LmsGradeResult) -> Unit) {
        apiScope.launch {
            val result = try {
                LmsGradeResult(success = true, gradeTable = getGradeTable(year, semester))
            } catch (throwable: Throwable) {
                LmsGradeResult(success = false, errorMessage = throwable.toResultMessage())
            }
            completion(result)
        }
    }

    /**
     * 최신 학기의 유세인트 성적 조회 정보를 비동기 방식으로 조회하고 그 결과를 completion 콜백으로 전달합니다.
     *
     * @param completion 결과 수신 콜백
     */
    fun getGradeTable(completion: (LmsGradeResult) -> Unit) {
        getGradeTable(null, null, completion)
    }

    /**
     * 유세인트 학기별 성적 요약 정보를 조회하여 가져옵니다.
     *
     * @return 유세인트 학기별 성적 요약 테이블 데이터
     */
    @Throws(Exception::class)
    suspend fun getSemesterGradeSummaryTable(): SemesterGradeSummaryTable {
        checkLoggedIn()
        val url = "https://ecc.ssu.ac.kr:8443/sap/bc/webdynpro/SAP/ZCMB3W0017"
        val appName = "ZCMB3W0017"
        val currentYear = Clock.System.now().toString().take(4).toInt()

        // A completed term search initializes the WebDynpro grade view and its summary table.
        getGradeTable(currentYear.minus(1).toString(), Semester.FIRST)

        var initialHtml = fetchWebDynproHtml(url, appName)
        var summaryTable = parseSemesterGradeSummaryTable(initialHtml)

        if (summaryTable.items.isEmpty()) {
            webDynproCache.remove(appName)
            getGradeTable(currentYear.minus(1).toString(), Semester.FIRST)
            initialHtml = fetchWebDynproHtml(url, appName)
            summaryTable = parseSemesterGradeSummaryTable(initialHtml)
        }

        val tableId = findSemesterGradeSummaryTableId(initialHtml)
        if (tableId == null) return summaryTable
        val visibleRowCount = summaryTable.items.size

        if (visibleRowCount == 0) return summaryTable

        var firstVisibleRow = visibleRowCount
        repeat(20) {
            val scrolledHtml = fetchWebDynproTableRows(
                url = url,
                appName = appName,
                tableId = tableId,
                firstVisibleRow = firstVisibleRow,
            )
            val scrolledTable = parseSemesterGradeSummaryTable(scrolledHtml)
            if (scrolledTable.items.isEmpty()) return summaryTable

            val merged = mergeSemesterGradeSummaryTables(summaryTable, scrolledTable)
            if (merged.items.size == summaryTable.items.size) return summaryTable

            summaryTable = merged
            firstVisibleRow += visibleRowCount
        }

        return summaryTable
    }

    private suspend fun fetchWebDynproTableRows(
        url: String,
        appName: String,
        tableId: String,
        firstVisibleRow: Int,
    ): String {
        val (secureId, formAction) = webDynproCache[appName]
            ?: throw IllegalStateException("성적 페이지 세션을 초기화하지 못했습니다.")
        val scrollEvent = "Table_VerticalScroll~E002Id~E004${tableId}~E005FirstVisibleItemIndex~E004${firstVisibleRow}~E005AccessType~E004SCROLLBAR~E003~E002ClientAction~E004submit~E005ResponseData~E004delta~E003~E002~E003"
        val focusInfo = escapeStr("{\"sFocussedId\":\"${tableId}\"}")
        val formRequest = "Form_Request~E002Id~E004sap.client.SsrClient.form~E005Async~E004false~E005FocusInfo~E004${focusInfo}~E005Hash~E004~E005DomChanged~E004false~E005IsDirty~E004false~E003~E002ResponseData~E004delta~E003~E002~E003"
        val actionFullUrl = if (formAction.startsWith("http")) formAction else "https://ecc.ssu.ac.kr:8443$formAction"
        val response = client.submitForm(
            url = actionFullUrl,
            formParameters = parameters {
                append("sap-charset", "utf-8")
                append("sap-wd-secure-id", secureId)
                append("fesrAppName", appName)
                append("fesrUseBeacon", "true")
                append("SAPEVENTQUEUE", listOf(scrollEvent, formRequest).joinToString("~E001"))
            },
        ) {
            headers {
                append(HttpHeaders.UserAgent, "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36")
                append(HttpHeaders.Accept, "*/*")
                append("X-Requested-With", "XMLHttpRequest")
                append(HttpHeaders.ContentType, "application/x-www-form-urlencoded; charset=UTF-8")
            }
        }
        val responseBody = response.bodyAsText().decodeHtmlEntities().decodeHtmlEntities()
        val nextSecureId = Regex("""name="sap-wd-secure-id"\s+value="([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(responseBody)?.groupValues?.get(1) ?: secureId
        val nextFormAction = Regex("""<form\s+[^>]*id="sap\.client\.SsrClient\.form"[^>]*action="([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(responseBody)?.groupValues?.get(1)?.decodeHtmlEntities()?.decodeHtmlEntities() ?: formAction
        webDynproCache[appName] = Pair(nextSecureId, nextFormAction)
        return responseBody
    }

    internal fun findSemesterGradeSummaryTableId(html: String): String? {
        val decodedHtml = html.decodeHtmlEntities()
        val controlRegex = Regex("""<(?:div|table)\b(?=[^>]*\bct="(?:ST|CT)")(?=[^>]*\bid="([^"]+)")[^>]*>""", RegexOption.IGNORE_CASE)
        val controls = controlRegex.findAll(decodedHtml).toList()

        for ((index, control) in controls.withIndex()) {
            val end = controls.getOrNull(index + 1)?.range?.first ?: decodedHtml.length
            val controlHtml = decodedHtml.substring(control.range.first, end)
            if (parseSemesterGradeSummaryTable(controlHtml).items.isNotEmpty()) {
                return control.groupValues[1]
            }
        }

        val summaryRow = Regex("""<tr\b[^>]*>([\s\S]*?)</tr>""", RegexOption.IGNORE_CASE)
            .findAll(decodedHtml)
            .firstOrNull { parseSemesterGradeSummaryTable(it.value).items.isNotEmpty() }
            ?: return null
        val tableTag = Regex("""<table\b[^>]*\bid="([^"]+)"[^>]*>""", RegexOption.IGNORE_CASE)
            .findAll(decodedHtml.substring(0, summaryRow.range.first))
            .lastOrNull()
            ?: return null
        return tableTag.groupValues[1]
            .removeSuffix("-content")
            .removeSuffix("-table")
    }

    internal fun mergeSemesterGradeSummaryTables(
        first: SemesterGradeSummaryTable,
        second: SemesterGradeSummaryTable,
    ): SemesterGradeSummaryTable {
        val merged = linkedMapOf<String, SemesterGradeSummaryCell>()
        (first.items + second.items).forEach { cell ->
            val key = "${cell.year}:${cell.semester?.code.orEmpty()}"
            if (key !in merged) merged[key] = cell
        }
        return SemesterGradeSummaryTable(items = merged.values.toList())
    }

    /**
     * 유세인트 학기별 성적 요약 정보를 비동기 방식으로 조회하고 그 결과를 completion 콜백으로 전달합니다.
     *
     * @param completion 결과 수신 콜백
     */
    fun getSemesterGradeSummaryTable(completion: (LmsSemesterGradeSummaryResult) -> Unit) {
        apiScope.launch {
            val result = try {
                LmsSemesterGradeSummaryResult(success = true, summaryTable = getSemesterGradeSummaryTable())
            } catch (throwable: Throwable) {
                LmsSemesterGradeSummaryResult(success = false, errorMessage = throwable.toResultMessage())
            }
            completion(result)
        }
    }

    fun parseSemesterGradeSummaryTable(html: String): SemesterGradeSummaryTable {
        val decodedHtml = html.decodeHtmlEntities()
        val trRegex = Regex("""<tr\b[^>]*>([\s\S]*?)</tr>""", RegexOption.IGNORE_CASE)
        val tdRegex = Regex("""<(td|th)\b[^>]*>([\s\S]*?)</\1>""", RegexOption.IGNORE_CASE)
        
        val cellsList = mutableListOf<SemesterGradeSummaryCell>()
        val trMatches = trRegex.findAll(decodedHtml).toList()
        
        for (trMatch in trMatches) {
            val trContent = trMatch.groupValues[1]
            val tds = tdRegex.findAll(trContent).map { it.groupValues[2].stripHtmlTags() }.toList()
            
            // Semester summary table rows have 14 columns
            if (tds.size == 14) {
                val year = tds[1].trim()
                val semesterName = tds[2].trim()
                
                // Academic year should look like a year (e.g. 4-digit number)
                if (year.length == 4 && year.all { it.isDigit() } && semesterName.isNotBlank()) {
                    val semesterEnum = Semester.fromName(semesterName)
                    val cell = SemesterGradeSummaryCell(
                        year = year,
                        semester = semesterEnum,
                        attemptedCredits = tds[3].trim(),
                        earnedCredits = tds[4].trim(),
                        pfCredits = tds[5].trim(),
                        gpa = tds[6].trim(),
                        gpaSum = tds[7].trim(),
                        arithmeticMean = tds[8].trim(),
                        semesterRank = tds[9].trim(),
                        totalRank = tds[10].trim(),
                        academicWarning = tds[11].trim(),
                        consultationStatus = tds[12].trim(),
                        failedYearStatus = tds[13].trim()
                    )
                    cellsList.add(cell)
                }
            }
        }
        
        return SemesterGradeSummaryTable(items = cellsList)
    }

    fun parseGradeTable(html: String, defaultYear: String? = null, defaultSemester: Semester? = null): GradeTable {
        val decodedHtml = html.decodeHtmlEntities()
        
        val parsedYear = parseYearFromHtml(html)
        val parsedSemester = parseSemesterFromHtml(html)
        
        val finalYear = if (parsedYear.isNotBlank()) parsedYear else (defaultYear ?: "")
        val finalSemester = (parsedSemester ?: defaultSemester) ?: Semester.FIRST

        val trRegex = Regex("""<tr\b[^>]*>([\s\S]*?)</tr>""", RegexOption.IGNORE_CASE)
        val tdRegex = Regex("""<(td|th)\b[^>]*>([\s\S]*?)</\1>""", RegexOption.IGNORE_CASE)
        
        val cellsList = mutableListOf<GradeCell>()
        val trMatches = trRegex.findAll(decodedHtml).toList()
        
        for ((index, trMatch) in trMatches.withIndex()) {
            val trContent = trMatch.groupValues[1]
            val tds = tdRegex.findAll(trContent).map { it.groupValues[2].stripHtmlTags() }.toList()
            
            // Detailed grade rows in Web Dynpro have 9 columns:
            if (tds.size == 9) {
                val subjectCode = tds[8].trim()
                val subjectName = tds[3].trim()
                
                // Subject code should be numeric and at least 7 digits (normally 8 in u-saint)
                if (subjectCode.length >= 7 && subjectCode.all { it.isDigit() } && subjectName.isNotBlank()) {
                    val grade = tds[1].trim()
                    val gradePoint = tds[2].trim()
                    val classification = tds[4].trim()
                    val credits = tds[5].trim()
                    val professor = tds[6].trim()
                    
                    val cell = GradeCell(
                        subjectCode = subjectCode,
                        subjectName = subjectName,
                        classification = classification,
                        credits = credits,
                        grade = grade,
                        gradePoint = gradePoint,
                        professor = professor
                    )
                    cellsList.add(cell)
                }
            }
        }
        
        if (cellsList.isEmpty()) {
            println("[DEBUG] Grade cells list is empty! Printing decoded HTML first 3000 chars:")
            println(decodedHtml.take(3000))
        }
        
        return GradeTable(year = finalYear, semester = finalSemester, items = cellsList)
    }

    /**
     * 유세인트 채플 정보를 조회하여 가져옵니다.
     * 특정 학년도와 학기를 지정하여 조회하거나, null 지정 시 캐싱된 최신 채플 내역을 가져옵니다.
     * 계절학기 조회는 불가능합니다.
     *
     * @param year 학년도 (예: "2026")
     * @param semester 학기 정보
     * @return 유세인트 채플 정보 (좌석 현황, 출결 상태, 결석계 내역 포함)
     */
    @Throws(Exception::class)
    suspend fun getChapelTable(year: String? = null, semester: Semester? = null): ChapelInformation {
        checkLoggedIn()

        if (year == null && semester == null) {
            val cachedYear = cachedLatestChapelYear
            val cachedSem = cachedLatestChapelSemester
            if (cachedYear != null && cachedSem != null) {
                return getChapelTable(cachedYear, cachedSem)
            }
        }

        if (year != null && semester != null) {
            val cachedInfo = cachedChapelInformation
            if (cachedInfo != null && year == cachedInfo.year && semester == cachedInfo.semester) {
                println("[WebDynpro Cache] Hit - Returning cached ChapelInformation for $year-$semester")
                return cachedInfo
            }
        }

        var currentHtml = fetchWebDynproHtml("https://ecc.ssu.ac.kr:8443/sap/bc/webdynpro/SAP/ZCMW3681", "ZCMW3681")
        
        if (cachedLatestChapelYear == null || cachedLatestChapelSemester == null) {
            val defaultYear = parseYearFromHtml(currentHtml)
            val defaultSem = parseSemesterFromHtml(currentHtml)
            if (defaultYear.isNotBlank() && defaultSem != null) {
                cachedLatestChapelYear = defaultYear
                cachedLatestChapelSemester = defaultSem
                println("[WebDynpro Cache] Captured default latest chapel semester: $defaultYear-${defaultSem.name}")
            }
        }

        val cached = webDynproCache["ZCMW3681"] ?: throw IllegalStateException("채플 페이지 세션을 초기화하지 못했습니다.")
        val secureId = cached.first
        val formAction = cached.second

        val peryrLabelMatch = Regex("""<label\b[^>]*\bfor="([^"]+)"[^>]*>(?:(?!</?label\b).)*?학년도""", RegexOption.IGNORE_CASE).find(currentHtml)
        val peryrMatch = Regex("""id="([^"]+:VIW_MAIN\.PERYR)"""").find(currentHtml)
        val peryrId = peryrLabelMatch?.groupValues?.get(1)
            ?: peryrMatch?.groupValues?.get(1)
            ?: ""

        val peridLabelMatch = Regex("""<label\b[^>]*\bfor="([^"]+)"[^>]*>(?:(?!</label>).)*?학기""", RegexOption.IGNORE_CASE).find(currentHtml)
        val peridMatch = Regex("""id="([^"]+:VIW_MAIN\.PERID)"""").find(currentHtml)
        val peridId = peridLabelMatch?.groupValues?.get(1)
            ?: peridMatch?.groupValues?.get(1)
            ?: ""

        val btnSearchLabelMatch = Regex("""<(?:div|button)\b[^>]*\bid="([^"]+)"[^>]*ct="B"[^>]*>(?:(?!<(?:div|button)\b).)*?조회""", RegexOption.IGNORE_CASE).find(currentHtml)
        val btnSearchMatch = Regex("""id="([^"]+\.BTN_SEARCH)"""").find(currentHtml)
        val btnSearchId = btnSearchLabelMatch?.groupValues?.get(1)
            ?: btnSearchMatch?.groupValues?.get(1)
            ?: ""

        // Cache the IDs if they were found in this html response
        if (peryrId.isNotBlank()) cachedChapelPeryrId = peryrId
        if (peridId.isNotBlank()) cachedChapelPeridId = peridId
        if (btnSearchId.isNotBlank()) cachedChapelBtnSearchId = btnSearchId

        // Resolve active IDs using cached fallback
        val finalPeryrId = peryrId.ifBlank { cachedChapelPeryrId ?: "" }
        val finalPeridId = peridId.ifBlank { cachedChapelPeridId ?: "" }
        val finalBtnSearchId = btnSearchId.ifBlank { cachedChapelBtnSearchId ?: "WDB2" }

        val buttonEvent = "Button_Press~E002Id~E004${finalBtnSearchId}~E003~E002ClientAction~E004submit~E003~E002~E003"
        val focusInfo = escapeStr("{\"sFocussedId\":\"${finalBtnSearchId}\"}")
        val buttonFormReq = "Form_Request~E002Id~E004sap.client.SsrClient.form~E005Async~E004false~E005FocusInfo~E004${focusInfo}~E005Hash~E004~E005DomChanged~E004false~E005IsDirty~E004false~E003~E002ResponseData~E004delta~E003~E002~E003"

        if (semester != null && (semester == Semester.SUMMER || semester == Semester.WINTER)) {
            throw IllegalArgumentException("채플은 계절학기 조회를 지원하지 않습니다.")
        }

        val eventQueue = if (year != null && semester != null && finalPeryrId.isNotBlank() && finalPeridId.isNotBlank()) {
            val yearEvent = "ComboBox_Select~E002Id~E004${finalPeryrId}~E005Key~E004${year}~E005ByEnter~E004false~E003~E002ClientAction~E004submit~E005ResponseData~E004delta~E003~E002~E003"
            val semesterEvent = "ComboBox_Select~E002Id~E004${finalPeridId}~E005Key~E004${semester.code}~E005ByEnter~E004false~E003~E002ClientAction~E004submit~E005ResponseData~E004delta~E003~E002~E003"
            listOf(yearEvent, semesterEvent, buttonEvent, buttonFormReq).joinToString("~E001")
        } else {
            listOf(buttonEvent, buttonFormReq).joinToString("~E001")
        }

        val actionFullUrl = if (formAction.startsWith("http")) formAction else "https://ecc.ssu.ac.kr:8443$formAction"
        val response = client.submitForm(
            url = actionFullUrl,
            formParameters = parameters {
                append("sap-charset", "utf-8")
                append("sap-wd-secure-id", secureId)
                append("fesrAppName", "ZCMW3681")
                append("fesrUseBeacon", "true")
                append("SAPEVENTQUEUE", eventQueue)
            }
        ) {
            headers {
                append(HttpHeaders.UserAgent, "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36")
                append(HttpHeaders.Accept, "*/*")
                append("X-Requested-With", "XMLHttpRequest")
                append(HttpHeaders.ContentType, "application/x-www-form-urlencoded; charset=UTF-8")
            }
        }
        val resultHtml = response.bodyAsText().decodeHtmlEntities().decodeHtmlEntities()
        
        val nextSecureId = Regex("""name="sap-wd-secure-id"\s+value="([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(resultHtml)?.groupValues?.get(1) ?: secureId
        val nextFormAction = Regex("""<form\s+[^>]*id="sap\.client\.SsrClient\.form"[^>]*action="([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(resultHtml)?.groupValues?.get(1)?.decodeHtmlEntities()?.decodeHtmlEntities() ?: formAction

        webDynproCache["ZCMW3681"] = Pair(nextSecureId, nextFormAction)
        
        val chapelInfo = parseChapelInformation(resultHtml, year, semester)

        if (year == null && semester == null) {
            cachedLatestChapelYear = chapelInfo.year
            cachedLatestChapelSemester = chapelInfo.semester
        }

        cachedChapelInformation = chapelInfo

        return chapelInfo
    }

    /**
     * 특정 학기의 유세인트 채플 정보를 비동기 방식으로 조회하고 그 결과를 completion 콜백으로 전달합니다.
     *
     * @param year 학년도 (예: "2026")
     * @param semester 학기 정보
     * @param completion 결과 수신 콜백
     */
    fun getChapelTable(year: String?, semester: Semester?, completion: (LmsChapelResult) -> Unit) {
        apiScope.launch {
            val result = try {
                LmsChapelResult(success = true, chapelInformation = getChapelTable(year, semester))
            } catch (throwable: Throwable) {
                LmsChapelResult(success = false, errorMessage = throwable.toResultMessage())
            }
            completion(result)
        }
    }

    /**
     * 최신 학기의 유세인트 채플 정보를 비동기 방식으로 조회하고 그 결과를 completion 콜백으로 전달합니다.
     *
     * @param completion 결과 수신 콜백
     */
    fun getChapelTable(completion: (LmsChapelResult) -> Unit) {
        getChapelTable(null, null, completion)
    }

    fun parseChapelInformation(html: String, defaultYear: String? = null, defaultSemester: Semester? = null): ChapelInformation {
        val decodedHtml = html.decodeHtmlEntities()
        
        val parsedYear = parseYearFromHtml(html)
        val parsedSemester = parseSemesterFromHtml(html)
        
        val finalYear = if (parsedYear.isNotBlank()) parsedYear else (defaultYear ?: "")
        val finalSemester = (parsedSemester ?: defaultSemester) ?: Semester.FIRST

        val tables = extractAllTables(decodedHtml)
        
        var seatStatusTable = ChapelSeatStatusTable(emptyList())
        var attendanceTable = ChapelAttendanceTable(emptyList())
        var absenceTable = ChapelAbsenceTable(emptyList())
        
        val seatStatusHeaders = listOf("분반", "시간표", "강의실", "층수", "좌석번호", "결석일수", "설문조사", "성적", "비고")
        val attendanceHeaders = listOf("분반", "수업일자", "강의구분", "강사", "소속", "제목", "출결상태", "평가", "비고")
        val absenceHeaders = listOf("학년도", "학기", "결석구분상세", "결석시작일자", "결석종료일자", "결석사유(국문)", "결석사유(영문)", "신청일자", "승인일자", "거부사유", "상태")

        for (tableHtml in tables) {
            val cleanedHtml = removeInnerTables(tableHtml)
            val rawTable = parseRawTable(cleanedHtml) ?: continue
            
            val colsCount = rawTable.headers.size
            if (rawTable.rows.isEmpty()) continue
            
            val firstRow = rawTable.rows[0]
            
            if (colsCount == 9 && firstRow.size >= 2 && firstRow[0].length >= 8 && firstRow[0].all { it.isDigit() } && !firstRow[1].contains(".")) {
                val items = rawTable.rows.map { row ->
                    val map = mutableMapOf<String, String>()
                    for (i in seatStatusHeaders.indices) {
                        if (i < row.size) {
                            map[seatStatusHeaders[i]] = row[i]
                        }
                    }
                    ChapelSeatStatusCell(
                        classGroup = map["분반"] ?: "",
                        timetable = map["시간표"] ?: "",
                        classroom = map["강의실"] ?: "",
                        seatNo = map["좌석번호"] ?: "",
                        absenceCount = map["결석일수"] ?: "",
                        gradeResult = map["성적"] ?: "",
                        rawValues = map
                    )
                }
                seatStatusTable = ChapelSeatStatusTable(items)
            } else if (colsCount == 9 && firstRow.size >= 2 && firstRow[1].matches(Regex("""\d{4}\.\d{2}\.\d{2}"""))) {
                val items = rawTable.rows.map { row ->
                    val map = mutableMapOf<String, String>()
                    for (i in attendanceHeaders.indices) {
                        if (i < row.size) {
                            map[attendanceHeaders[i]] = row[i]
                        }
                    }
                    ChapelAttendanceCell(
                        classGroup = map["분반"] ?: "",
                        date = map["수업일자"] ?: "",
                        lectureType = map["강의구분"] ?: "",
                        status = map["출결상태"] ?: "",
                        rawValues = map
                    )
                }
                attendanceTable = ChapelAttendanceTable(items)
            } else if (colsCount == 11 && firstRow.size >= 1 && firstRow[0].matches(Regex("""\d{4}"""))) {
                val items = rawTable.rows.map { row ->
                    val map = mutableMapOf<String, String>()
                    for (i in absenceHeaders.indices) {
                        if (i < row.size) {
                            map[absenceHeaders[i]] = row[i]
                        }
                    }
                    ChapelAbsenceCell(
                        year = map["학년도"] ?: "",
                        semester = map["학기"] ?: "",
                        detail = map["결석구분상세"] ?: "",
                        rawValues = map
                    )
                }
                absenceTable = ChapelAbsenceTable(items)
            }
        }
        
        return ChapelInformation(
            year = finalYear,
            semester = finalSemester,
            seatStatusTable = seatStatusTable,
            attendanceTable = attendanceTable,
            absenceTable = absenceTable
        )
    }

    private fun extractAllTables(html: String): List<String> {
        val tables = mutableListOf<String>()
        var pos = 0
        while (true) {
            val startIdx = html.indexOf("<table", pos, ignoreCase = true)
            if (startIdx == -1) break
            
            var depth = 1
            var currentPos = startIdx + 6
            var endIdx = -1
            while (depth > 0 && currentPos < html.length) {
                val nextStart = html.indexOf("<table", currentPos, ignoreCase = true)
                val nextEnd = html.indexOf("</table>", currentPos, ignoreCase = true)
                
                if (nextEnd == -1) break
                
                if (nextStart != -1 && nextStart < nextEnd) {
                    depth++
                    currentPos = nextStart + 6
                } else {
                    depth--
                    currentPos = nextEnd + 8
                    if (depth == 0) {
                        endIdx = nextEnd + 8
                    }
                }
            }
            if (endIdx != -1) {
                tables.add(html.substring(startIdx, endIdx))
            }
            pos = startIdx + 6
        }
        return tables
    }

    private fun removeInnerTables(tableHtml: String): String {
        var html = tableHtml
        while (true) {
            val startIdx = html.indexOf("<table", 1, ignoreCase = true)
            if (startIdx == -1) break
            
            var depth = 1
            var currentPos = startIdx + 6
            var endIdx = -1
            while (depth > 0 && currentPos < html.length) {
                val nextStart = html.indexOf("<table", currentPos, ignoreCase = true)
                val nextEnd = html.indexOf("</table>", currentPos, ignoreCase = true)
                if (nextEnd == -1) break
                
                if (nextStart != -1 && nextStart < nextEnd) {
                    depth++
                    currentPos = nextStart + 6
                } else {
                    depth--
                    currentPos = nextEnd + 8
                    if (depth == 0) {
                        endIdx = nextEnd + 8
                    }
                }
            }
            if (endIdx != -1) {
                html = html.substring(0, startIdx) + html.substring(endIdx)
            } else {
                break
            }
        }
        return html
    }

    private fun parseRawTable(tableHtml: String): RawTable? {
        val decodedHtml = tableHtml.decodeHtmlEntities()
        val trRegex = Regex("""<tr\b[^>]*>([\s\S]*?)</tr>""", RegexOption.IGNORE_CASE)
        val tdRegex = Regex("""<(td|th)\b[^>]*>([\s\S]*?)</\1>""", RegexOption.IGNORE_CASE)
        
        val allRows = mutableListOf<List<String>>()
        for (trMatch in trRegex.findAll(decodedHtml)) {
            val trContent = trMatch.groupValues[1]
            val cells = tdRegex.findAll(trContent).map { it.groupValues[2].stripHtmlTags().trim() }.toList()
            if (cells.isNotEmpty()) {
                allRows.add(cells)
            }
        }
        if (allRows.isEmpty()) return null
        
        val headers = allRows[0]
        val rows = allRows.drop(1)
        
        return RawTable(headers = headers, rows = rows)
    }

    private fun parseYearFromHtml(html: String): String {
        val decodedHtml = html.decodeHtmlEntities()
        val labelRegex = Regex("""<label\b[^>]*for="([^"]+)"[^>]*>(?:(?!</label>)[\s\S])*?학년도""", RegexOption.IGNORE_CASE)
        val inputValRegex = { id: String -> Regex("""id="$id"[^>]*value="([^"]+)"""", RegexOption.IGNORE_CASE) }
        
        var parsedYear = ""
        labelRegex.find(decodedHtml)?.groupValues?.get(1)?.let { id ->
            parsedYear = inputValRegex(id).find(decodedHtml)?.groupValues?.get(1)?.decodeHtmlEntities() ?: ""
        }
        if (parsedYear.isBlank()) {
            parsedYear = Regex("""value="(\d{4})학년도"""", RegexOption.IGNORE_CASE).find(decodedHtml)?.groupValues?.get(1) ?: ""
        } else {
            parsedYear = Regex("""\d{4}""").find(parsedYear)?.value ?: parsedYear
        }
        return parsedYear
    }

    private fun parseSemesterFromHtml(html: String): Semester? {
        val decodedHtml = html.decodeHtmlEntities()
        val semesterLabelRegex = Regex("""<label\b[^>]*for="([^"]+)"[^>]*>(?:(?!</label>)[\s\S])*?학기""", RegexOption.IGNORE_CASE)
        val inputValRegex = { id: String -> Regex("""id="$id"[^>]*value="([^"]+)"""", RegexOption.IGNORE_CASE) }
        
        var parsedSemesterStr = ""
        semesterLabelRegex.find(decodedHtml)?.groupValues?.get(1)?.let { id ->
            parsedSemesterStr = inputValRegex(id).find(decodedHtml)?.groupValues?.get(1)?.decodeHtmlEntities() ?: ""
        }
        if (parsedSemesterStr.isBlank()) {
            parsedSemesterStr = Regex("""value="([^"]*학기)"""", RegexOption.IGNORE_CASE).find(decodedHtml)?.groupValues?.get(1) ?: ""
        }
        return Semester.fromName(parsedSemesterStr)
    }

    private data class RawTable(val headers: List<String>, val rows: List<List<String>>)
}


fun normalizePem(raw: String): String {
    return raw
        .replace("\\n", "\n")
        .replace("\\r", "")
        .replace("-----BEGIN RSA PRIVATE KEY-----", "-----BEGIN RSA PRIVATE KEY-----\n")
        .replace("-----END RSA PRIVATE KEY-----", "\n-----END RSA PRIVATE KEY-----")
        .trim()
}

fun String.decodeHtmlEntities(): String {
    val regex = Regex("&(?:#(x?[0-9a-fA-F]+)|([a-zA-Z0-9]+));")
    return regex.replace(this) { matchResult ->
        val hexOrDec = matchResult.groups[1]?.value
        val name = matchResult.groups[2]?.value
        if (hexOrDec != null) {
            try {
                val codePoint = if (hexOrDec.startsWith("x", ignoreCase = true)) {
                    hexOrDec.substring(1).toInt(16)
                } else {
                    hexOrDec.toInt()
                }
                codePoint.toChar().toString()
            } catch (e: Exception) {
                matchResult.value
            }
        } else if (name != null) {
            when (name) {
                "nbsp" -> " "
                "lt" -> "<"
                "gt" -> ">"
                "amp" -> "&"
                "quot" -> "\""
                "apos" -> "'"
                else -> matchResult.value
            }
        } else {
            matchResult.value
        }
    }
}

fun String.stripHtmlTags(): String {
    val withNewlines = this.replace(Regex("""<br\b[^>]*>""", RegexOption.IGNORE_CASE), "\n")
    val stripped = withNewlines.replace(Regex("""<[^>]+>"""), "")
    return stripped.decodeHtmlEntities().trim()
}

internal expect fun pemToString(rawPem: String, rawPw: String): String
