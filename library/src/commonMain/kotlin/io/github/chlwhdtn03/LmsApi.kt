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
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

internal val client = HttpClient() {
    install(HttpCookies)
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

private fun String.withoutXssiPrefix(): String {
    val body = dropWhile { it == '\uFEFF' || it.isWhitespace() }
    return if (body.startsWith(XSSI_PREFIX)) {
        body.drop(XSSI_PREFIX.length).dropWhile { it.isWhitespace() }
    } else {
        body
    }
}

object LmsApi {
    private const val LMS_LOGIN_URL = "https://smartid.ssu.ac.kr/Symtra_sso/smln_pcs.asp"
    private const val LMS_CERT_URL = "https://lms.ssu.ac.kr/xn-sso/gw-cb.php"
    private const val LMS_POST_LOGIN = "https://canvas.ssu.ac.kr/login/canvas"
    private const val LMS_CONFIRM_LOGIN = "https://canvas.ssu.ac.kr/?login_success=1"
    var isLoggined = false
    private var lmsId = ""
    private var apiBearerToken = ""

    private data class AssignmentMetadata(
        val groupName: String,
        val name: String,
        val maxScore: Double,
    )

    @Serializable
    private data class TodoSubmission(
        val assignment_id: Int? = 0,
        val cached_due_date: String? = "",
        val late: Boolean? = false,
        val submitted_at: String? = "",
        val workflow_state: String? = "",
        val name: String = "알 수 없음",
    )

    @Serializable
    private data class TodoAssignmentDetail(
        val name: String? = "",
        val description: String? = "",
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
    )

    private val trackedTodoSnapshotDatesByDistinctId = mutableMapOf<String, String>()

    private fun checkLoggedIn() {
        if (!isLoggined || lmsId.isBlank()) {
            throw IllegalStateException("LMS 로그인이 되어있지 않습니다.")
        }
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun fetchTerms(): List<Term> {
        return client.get("https://canvas.ssu.ac.kr/learningx/api/v1/users/${lmsId}/terms?include_invited_course_contained=true") {
            headers { append("Authorization", "Bearer $apiBearerToken") }
        }.body<Terms>().enrollment_terms
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun fetchLectures(term: Term): List<Lecture> {
        return client.get("https://canvas.ssu.ac.kr/learningx/api/v1/learn_activities/courses?term_ids[]=${term.id}") {
            headers { append("Authorization", "Bearer $apiBearerToken") }
        }.body<List<Lecture>>()
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun fetchLoginInfo(): Info {
        return client.get("https://lms.ssu.ac.kr/api/v1/users/${lmsId}") {
        }.body<Info>()
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun fetchLearnStatuses(term: Term): LearnStatuses {
        return client.get("https://canvas.ssu.ac.kr/learningx/api/v1/learn_activities/learnstatus?term_ids=${term.id}&type=subsection") {
            headers { append("Authorization", "Bearer $apiBearerToken") }
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
        return client.get("https://canvas.ssu.ac.kr/api/v1/courses/${courseId}/assignment_groups") {
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
            .get("https://canvas.ssu.ac.kr/api/v1/courses/$courseId/assignments/$assignmentId")
            .body<TodoAssignmentDetail>()
    }

    private suspend fun fetchTodoDetail(courseId: Int): List<TodoDetail> {
        return client
            .get("https://canvas.ssu.ac.kr/learningx/api/v1/courses/${courseId}/modules?include_detail=true") {
                headers { append("Authorization", "Bearer $apiBearerToken") }
            }
            .body<List<TodoDetail>>()
    }


    private suspend fun fetchSubmissions(courseId: Int): List<Submission> {
        return client.get("https://canvas.ssu.ac.kr/api/v1/courses/${courseId}/students/submissions") {
            url {
                parameters.append("per_page", "50")
            }
        }.body<List<Submission>>()
    }

    private suspend fun fetchTodoSubmissions(courseId: Int): List<TodoSubmission> {
        return client.get("https://canvas.ssu.ac.kr/api/v1/courses/${courseId}/students/submissions") {
            url {
                parameters.append("per_page", "50")
                parameters.append("exclude_response_fields[]", "body")
                parameters.append("exclude_response_fields[]", "preview_url")
                parameters.append("exclude_response_fields[]", "attachments")
                parameters.append("exclude_response_fields[]", "turnitin_data")
                parameters.append("exclude_response_fields[]", "submission_comments")
                parameters.append("exclude_response_fields[]", "rubric_assessment")
            }
        }.body<List<TodoSubmission>>()
    }

    private suspend fun fetchDiscussions(courseId: Int): List<Discussion> {
        return client.get("https://canvas.ssu.ac.kr/api/v1/courses/${courseId}/discussion_topics?only_announcements=true&per_page=40&page=1&filter_by=all&no_avatar_fallback=1&include[]=sections_user_count&include[]=sections") {
            headers {
                append("Referer", "https://canvas.ssu.ac.kr/courses/${courseId}/announcements")
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

    private fun TodoSubmission.isCompletedForTodo(): Boolean {
        return !submitted_at.isNullOrBlank() || workflow_state == "submitted" || workflow_state == "graded"
    }

    @OptIn(ExperimentalTime::class)
    private fun TodoSubmission.isOverdueUnsubmitted(now: Instant): Boolean {
        if (workflow_state != "unsubmitted") return false
        if (late == true) return true

        return cached_due_date.isPastOrCurrentInstant(now)
    }

    private fun List<TodoSubmission>.toUnsubmittedStats(now: Instant): UnsubmittedStats {
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

    private fun List<TodoSubmission>.toSubmissionTrackingItems(
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

    private fun Submission.toTodoSubmission(): TodoSubmission {
        return TodoSubmission(
            assignment_id = assignment_id,
            cached_due_date = cached_due_date,
            late = late,
            submitted_at = submitted_at,
            workflow_state = workflow_state,
            name = name,
        )
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

    @OptIn(ExperimentalTime::class)
    private suspend fun buildTodoListFromSubmissions(
        courseId: Int,
        submissions: List<TodoSubmission>,
        includeCommons: Boolean,
        includeCommonsForTracking: Boolean = false,
    ): TodoBuildResult {
        val now = Clock.System.now()
        val seenAssignmentIds = mutableSetOf<Int>()
        val todoList = mutableListOf<TodoList>()
        var commonsStats = UnsubmittedStats()
        var commonsTrackingItems = emptyList<TodoTrackingItem>()

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
                component_type = "assignment",
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

            if (includeCommons) {
                todoList += todoDetails.toCommonsTodoList(now)
            }
        }

        return TodoBuildResult(
            todoList = todoList.sortedBy { it.due_date },
            commonsStats = commonsStats,
            commonsTrackingItems = commonsTrackingItems,
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
     * @param id LMS 아이디
     * @param password LMS 비밀번호
     * @return LMS로그인에 성공하면 true, 실패하면 false를 반환합니다.
     */
    internal suspend fun loginLMS(id: String, password: String): Boolean {
        val loginResponse = client.submitForm(
            url = LMS_LOGIN_URL,
            formParameters = parameters {
                append("userid", id)
                append("pwd", password)
            }
        ).headers.getAll("Set-Cookie")
        val token = loginResponse?.find { it.contains("sToken") }
            ?.substringAfter("sToken=")
            ?.substringBefore(";") ?: ""
        println("sToken : $token")

        if (token.isBlank())
            throw IllegalArgumentException("아이디 또는 비밀번호가 일치하지 않습니다.")

        val redirectURL = client.get(LMS_CERT_URL) {
            url {
                parameters.append("sToken", token)
                parameters.append("sIdno", id)
            }
        }.bodyAsText()
            .substringAfter("iframe.src=\"")
            .substringBefore("\";")

        val apiToken = client.get(redirectURL)

        apiBearerToken = apiToken
            .headers.getAll("Set-Cookie")?.find { it.contains("xn_api_token") }
            ?.substringAfter("xn_api_token=")
            ?.substringBefore(";") ?: ""

        if (apiBearerToken.isBlank())
            throw RuntimeException("API 토큰값을 불러오지 못했습니다. 다시 시도해주세요.")

        println("Bearer Token : $apiBearerToken")

        val body = apiToken.bodyAsText()
        val pem = body
            .substringAfter("window.loginCryption(\"")
            .substringBefore("\")")
            .substringAfter(", \"")

        val raw_pw = body
            .substringAfter("window.loginCryption(\"")
            .substringBefore("\"")

        val decrypted = pemToString(rawPem = pem, rawPw = raw_pw)

        client.submitForm(
            url = LMS_POST_LOGIN,
            formParameters = parameters {
                append("utf8", "✓")
                append("redirect_to_ssl", "1")
                append("after_login_url", "")
                append("pseudonym_session[unique_id]", id)
                append("pseudonym_session[password]", decrypted)
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

        client.get(LMS_CONFIRM_LOGIN) {
            headers {
                append(HttpHeaders.Referrer, redirectURL)
            }
        }

        isLoggined = true
        lmsId = id
        println(lmsId + "으로 로그인에 성공하였습니다.")
        return isLoggined // 토큰값이 비어있거나 Null이면 로그인 실패
    }

    @OptIn(ExperimentalTime::class)
    internal suspend fun getTerms(): List<Term> {
        checkLoggedIn()
        return fetchTerms()
    }

    internal suspend fun getLoginInfo(): Info {
        checkLoggedIn()

        return fetchLoginInfo()
    }

    internal suspend fun getCookies(): LmsSessionResponse {
        checkLoggedIn()

        return fetchLmsSession()
    }

    fun loginLMS(id: String, password: String, completion: (LmsLoginResult) -> Unit) {
        launchLoginResult(completion) {
            loginLMS(id, password)
        }
    }

    @OptIn(ExperimentalTime::class)
    fun getTerms(completion: (LmsTermsResult) -> Unit) {
        launchTermsResult(completion) {
            getTerms()
        }
    }

    fun getLoginInfo(completion: (LmsLoginInfoResult) -> Unit) {
        launchLoginInfoResult(completion) {
            getLoginInfo()
        }
    }

    fun getCookies(completion: (LmsCookiesResult) -> Unit) {
        launchCookiesResult(completion) {
            getCookies()
        }
    }

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
     * @param loadingState Float 변수에는 진행률을 각 단계마다 전달합니다. (0.0f~1.0f)
     */
    @ExperimentalTime
    internal suspend fun getSubjects(term: Term, loadingState: (Float) -> Unit = {}): List<Subject> {
        return loadSubjects(term, loadingState, SubjectLoadMode.Full)
    }

    /**
     * 제출해야 할 과제, 동영상 시청 정보만 빠르게 가져옵니다. (SSU-Time 전용)
     * @param loadingState Float 변수에는 진행률을 각 단계마다 전달합니다. (0.0f~1.0f)
     */
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

            val assignmentStats = fetchTodoSubmissions(lecture.id).toUnsubmittedStats(now)
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

            val submissions: List<Submission> = fetchSubmissions(lecture.id)
            val assignmentMetadataById = if (submissions.isEmpty()) {
                emptyMap()
            } else {
                fetchAssignmentGroups(lecture.id).toAssignmentMetadataById()
            }
            applyAssignmentMetadata(submissions, assignmentMetadataById)

            val todoSubmissions: List<TodoSubmission>
            val includeCommons: Boolean

            if (mode == SubjectLoadMode.Full) {
                todoSubmissions = submissions.map { it.toTodoSubmission() }
                includeCommons = true
            } else {
                includeCommons = lecture.activities.mayHaveCommonsTodos()

                todoSubmissions = fetchTodoSubmissions(lecture.id)

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

            val todoList = todoBuildResult.todoList
            if (mode == SubjectLoadMode.TodoOnly && todoList.isEmpty()) continue

            subjects += Subject(
                id = lecture.id,
                termId = lecture.term_id,
                termName = term.name ?: "학기정보 없음",
                name = lecture.name,
                professor = lecture.professors,
                totalStudents = lecture.total_students,
                todoList = todoList,
                attendances = if (mode == SubjectLoadMode.Full) learnStatusByCourseId[lecture.id]?.sections?.map { section ->
                    section.subsections.map { sub ->
                        when (sub.status) {
                            "attendance" -> AttendanceType.ATTENDANCE
                            "absent" -> AttendanceType.ABSENT
                            "late" -> AttendanceType.LATE
                            else -> AttendanceType.NONE
                        }
                    }
                } ?: emptyList() else emptyList(),
                discussions = if (mode == SubjectLoadMode.Full) fetchDiscussions(lecture.id) else emptyList(),
                submissions = submissions,
                scoredAssignments = if (mode == SubjectLoadMode.Full) {
                    buildScoredAssignments(submissions, assignmentMetadataById)
                } else {
                    emptyList()
                },
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
}

fun normalizePem(raw: String): String {
    return raw
        .replace("\\n", "\n")
        .replace("\\r", "")
        .replace("-----BEGIN RSA PRIVATE KEY-----", "-----BEGIN RSA PRIVATE KEY-----\n")
        .replace("-----END RSA PRIVATE KEY-----", "\n-----END RSA PRIVATE KEY-----")
        .trim()
}

internal expect fun pemToString(rawPem: String, rawPw: String): String
