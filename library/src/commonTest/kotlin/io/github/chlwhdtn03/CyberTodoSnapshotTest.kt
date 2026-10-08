package io.github.chlwhdtn03

import io.github.chlwhdtn03.data.Cyber.*
import io.github.chlwhdtn03.internal.CyberCourseService
import io.github.chlwhdtn03.internal.CyberTodoService
import io.github.chlwhdtn03.internal.TodoSnapshotTracker
import io.github.chlwhdtn03.internal.cyberSnapshotItems
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CyberTodoSnapshotTest {
    private val subject = CyberSubject("과목", "", "", "", "2026", "2", "00123", "D", "", 0)
    private val now = Instant.parse("2026-10-08T00:00:00Z")

    @Test
    fun countsOnlyOverdueIncompleteItemsAndDeduplicatesResubmissions() {
        val weeks = listOf(
            CyberWeek(1, "2026.9.1 ~ 2026.9.14", "", "", listOf(lecture(1, 0), lecture(2, 100))),
            CyberWeek(2, "2026.10.1 ~ 2026.10.14", "", "", listOf(lecture(1, 0))),
            CyberWeek(3, "기간 없음", "", "", listOf(lecture(1, 0))),
        )
        val assignment = evaluation(CyberEvaluationType.ASSIGNMENT, "미제출", "2026-10-07 23:59")
        val evaluations = listOf(
            assignment,
            assignment.copy(isResubmission = true, submitStatus = "제출완료"),
            evaluation(CyberEvaluationType.QUIZ, "미응시", "2026-10-07 23:59"),
            evaluation(CyberEvaluationType.EXAM, "미응시", "2026-10-07 23:59"),
        )
        val items = cyberSnapshotItems(subject, weeks, evaluations, now)
        assertEquals(6, items.size)
        assertEquals(2, items.count { it.isOverdueUnsubmitted })
        assertTrue(items.single { it.itemKey.contains(":03:") }.isCompleted)
        assertEquals(JsonPrimitive("00123"), items.first().courseId)
        assertEquals(items.size, items.map { it.itemKey }.toSet().size)
    }

    @Test
    fun interpretsDeadlinesInKoreaAndIncludesEntireAttendanceEndDate() {
        val week = CyberWeek(1, "2026.10.01 ~ 2026.10.08", "", "", listOf(lecture(1, 0)))
        assertFalse(cyberSnapshotItems(subject, listOf(week), emptyList(), now).single().isOverdueUnsubmitted)
        val afterEnd = Instant.parse("2026-10-08T15:00:00Z")
        assertTrue(cyberSnapshotItems(subject, listOf(week), emptyList(), afterEnd).single().isOverdueUnsubmitted)
        val quiz = evaluation(CyberEvaluationType.QUIZ, "미응시", "2026-10-08 09:00")
        assertTrue(cyberSnapshotItems(subject, emptyList(), listOf(quiz), now).single().isOverdueUnsubmitted)
        assertFalse(cyberSnapshotItems(subject, emptyList(), listOf(quiz.copy(endAt = "invalid")), now)
            .single().isOverdueUnsubmitted)
    }

    @Test
    fun sharesIdentifyAndPropertiesButSeparatesSnapshotWithDailyGating() = runTest {
        val requests = mutableListOf<JsonObject>()
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                requests += Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })) { install(ContentNegotiation) { json() } }
        try {
            val lms = TodoSnapshotTracker(client, this, mutableMapOf(), shouldSend = { true }, currentTime = { now })
            val cyber = TodoSnapshotTracker(client, this, mutableMapOf(), "_cyber", { true }, { now })
            val items = cyberSnapshotItems(subject, listOf(
                CyberWeek(1, "2026.09.01 ~ 2026.09.14", "", "", listOf(lecture(1, 0))),
            ), emptyList(), now)
            val stats = LmsApi.UnsubmittedStats(1, 1)
            cyber.track(stats, items, null)
            cyber.track(stats, items, "  ")
            lms.track(stats, items, "user")
            cyber.track(stats, items, " user ")
            cyber.track(stats, items, "user")
            advanceUntilIdle()
            assertEquals(2, requests.size)
            val events = requests.flatMap { it.getValue("batch").jsonArray }.map { it.jsonObject }
            assertEquals(listOf("\$identify", "todo_snapshot", "\$identify", "todo_snapshot_cyber"),
                events.map { it.getValue("event").jsonPrimitive.content })
            val cyberIdentify = events[2].getValue("properties").jsonObject
            val initial = cyberIdentify.getValue("\$set_once").jsonObject
            assertTrue("initial_unsubmitted_ratio" in initial)
            assertFalse(initial.keys.any { it.endsWith("_cyber") })
            assertEquals(events[0].getValue("properties"), events[2].getValue("properties"))
            val lmsSnapshot = events[1].getValue("properties").jsonObject
            val cyberSnapshot = events[3].getValue("properties").jsonObject
            assertEquals(lmsSnapshot.filterKeys { it != "sync_id" }, cyberSnapshot.filterKeys { it != "sync_id" })
            assertEquals(JsonPrimitive(1.0), cyberSnapshot["snapshot_unsubmitted_ratio"])
        } finally { client.close() }
    }

    @Test
    fun samplingRejectionIsRememberedUntilNextUtcDate() = runTest {
        var draws = 0
        var current = now
        val dates = mutableMapOf<String, String>()
        val client = HttpClient(MockEngine { error("샘플링 제외 시 전송하면 안 됩니다") })
        try {
            val tracker = TodoSnapshotTracker(client, this, dates, "_cyber", { draws++; false }, { current })
            repeat(3) { tracker.track(LmsApi.UnsubmittedStats(), emptyList(), "user") }
            assertEquals(1, draws)
            current = Instant.parse("2026-10-09T00:00:00Z")
            tracker.track(LmsApi.UnsubmittedStats(), emptyList(), "user")
            assertEquals(2, draws)
            assertEquals("2026-10-09", dates["user"])
        } finally { client.close() }
    }

    @Test
    fun optOutSkipsDetailsAndPartialCourseFailureDoesNotSendOrConsumeDailyAttempt() = runTest {
        val paths = mutableListOf<String>()
        val dates = mutableMapOf<String, String>()
        val client = HttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            when (request.url.encodedPath) {
                "/atnlcSubj/list" -> respond("""
                    <div class="inBoxCont" data-shyr="2026" data-smst-cd="2">
                      <button data-cose-cd="00123" data-dert-cd="D"></button>
                    <!--// inBoxCont -->
                """, HttpStatusCode.OK)
                "/atnlcSubj/atnlcApe/list" -> respond("", HttpStatusCode.OK)
                else -> error("조회 실패")
            }
        }) { install(ContentNegotiation) { json() } }
        try {
            val service = CyberTodoService(CyberCourseService(client),
                TodoSnapshotTracker(client, this, dates, "_cyber", { true }, { now }))
            assertEquals(1, service.getSubjects(null).size)
            assertEquals(listOf("/atnlcSubj/list"), paths)
            assertFailsWith<IllegalStateException> { service.getSubjects("user") }
            advanceUntilIdle()
            assertTrue(dates.isEmpty())
            assertFalse(paths.any { it == "/batch/" })
        } finally { client.close() }
    }

    private fun lecture(no: Int, progress: Int) = CyberLecture(no, "", progress, "", "", null, null)
    private fun evaluation(type: CyberEvaluationType, status: String, end: String) = CyberEvaluation(
        type, type.code, "", 1, "1주차", "평가", "2026-09-01 00:00", end, "", "", status, "",
        false, false, "", false,
    )
}
