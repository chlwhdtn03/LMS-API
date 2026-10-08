package io.github.chlwhdtn03.internal

import io.github.chlwhdtn03.LmsApi
import io.github.chlwhdtn03.TODO_SNAPSHOT_SAMPLE_RATE
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

/** LMS와 사이버의 전송 방식과 속성명은 공유하고, 스냅샷 이벤트명과 일별 전송 이력은 분리합니다. */
@OptIn(ExperimentalTime::class)
internal class TodoSnapshotTracker(
    private val client: HttpClient,
    private val backgroundScope: CoroutineScope,
    private val trackedSnapshotDates: MutableMap<String, String>,
    private val eventSuffix: String = "",
    private val shouldSend: () -> Boolean = { shouldSendTodoSnapshot() },
    private val currentTime: () -> Instant = { Clock.System.now() },
) {
    fun track(
        stats: LmsApi.UnsubmittedStats,
        items: List<TodoSnapshotItem>,
        postHogDistinctId: String?,
    ) {
        val distinctId = postHogDistinctId?.trim()?.takeIf { it.isNotBlank() } ?: return
        val now = currentTime().toString()
        val today = now.substringBefore('T')
        if (trackedSnapshotDates[distinctId] == today) return
        trackedSnapshotDates[distinctId] = today

        val syncId = "todo_sync:${Random.nextLong()}:$now"
        if (!shouldSend()) return

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
                event = POSTHOG_TODO_SNAPSHOT_EVENT + eventSuffix,
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

internal data class TodoSnapshotItem(
    val itemKey: String,
    val itemType: String,
    val courseId: JsonPrimitive,
    val dueAt: String,
    val isCompleted: Boolean,
    val isOverdueUnsubmitted: Boolean,
    val workflowState: String? = null,
    val late: Boolean? = null,
)
