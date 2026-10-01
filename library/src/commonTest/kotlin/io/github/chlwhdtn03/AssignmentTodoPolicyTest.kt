package io.github.chlwhdtn03

import io.github.chlwhdtn03.data.Lms.AssignmentDetail
import io.github.chlwhdtn03.data.Lms.Submission
import io.github.chlwhdtn03.data.Lms.TodoList
import io.github.chlwhdtn03.internal.toAssignmentTodo
import kotlinx.serialization.json.Json
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class AssignmentTodoPolicyTest {
    private val before = "2026-09-30T12:00:00Z"
    private val current = "2026-10-01T12:00:00Z"
    private val after = "2026-10-02T12:00:00Z"
    private val later = "2026-10-03T12:00:00Z"
    private val now = Instant.parse(current)
    private val submission = Submission(assignment_id = 1, cached_due_date = before, workflow_state = "unsubmitted")
    private val detail = AssignmentDetail(due_at = before, late_at = after, lock_at = later, published = true)

    @Test
    fun observedLmsResponseUsesLockAtWithoutLateAt() {
        // Dates/statuses observed in LMSTest on 2026-10-01; IDs and other personal fields removed.
        val observedDetail = Json.decodeFromString<AssignmentDetail>("""
            {
              "due_at": "2026-09-28T14:59:59Z",
              "unlock_at": "2026-09-14T15:00:00Z",
              "lock_at": "2026-10-30T14:59:59Z",
              "workflow_state": "published",
              "published": true,
              "locked_for_user": false
            }
        """)
        val observedSubmission = Json.decodeFromString<Submission>("""
            {
              "assignment_id": 1,
              "submitted_at": "2026-09-21T10:20:21Z",
              "workflow_state": "submitted",
              "cached_due_date": "2026-09-28T14:59:59Z",
              "excused": null,
              "late": false
            }
        """)
        assertNull(observedSubmission.toAssignmentTodo(observedDetail, now))
        // Synthetic unsubmitted variant of the observed response; no server mutation.
        val unsubmitted = observedSubmission.copy(submitted_at = null, workflow_state = "unsubmitted")
        val todo = assertNotNull(unsubmitted.toAssignmentTodo(observedDetail, now))
        assertEquals("2026-09-28T14:59:59Z", todo.due_at)
        assertEquals("2026-10-30T14:59:59Z", todo.lock_at)
        assertEquals(todo.lock_at, todo.submission_deadline)
        assertNull(unsubmitted.toAssignmentTodo(observedDetail, Instant.parse("2026-10-30T14:59:59Z")))
    }

    @Test
    fun overdueAssignmentRemainsUntilLateDeadlineAndSerializesBothDeadlines() {
        val todo = assertNotNull(submission.toAssignmentTodo(detail, now))
        assertEquals(before, todo.due_date)
        assertEquals(before, todo.due_at)
        assertEquals(after, todo.late_at)
        assertEquals(later, todo.lock_at)
        assertEquals(later, todo.submission_deadline)
        assertEquals(todo, Json.decodeFromString<TodoList>(Json.encodeToString(todo)))
        assertTrue(Json.encodeToString(todo).contains("\"submission_deadline\""))
    }

    @Test
    fun lockAtIsAuthoritativeLateSubmissionDeadline() {
        assertEquals(later, assertNotNull(submission.toAssignmentTodo(detail, now)).submission_deadline)
        assertNotNull(submission.toAssignmentTodo(detail, Instant.parse(after)))
        assertNull(submission.toAssignmentTodo(detail, Instant.parse(later)))
        assertNull(submission.toAssignmentTodo(detail.copy(lock_at = before), now))
        assertNotNull(submission.toAssignmentTodo(detail.copy(late_at = before), now))
        assertNotNull(submission.toAssignmentTodo(detail.copy(late_at = "invalid"), now))
        assertNull(submission.toAssignmentTodo(detail.copy(lock_at = null, late_at = current), now))
    }

    @Test
    fun lockOrLateAloneAllowsLateSubmissionButMissingEndDoesNot() {
        assertNotNull(submission.toAssignmentTodo(detail.copy(late_at = null), now))
        assertNotNull(submission.toAssignmentTodo(detail.copy(lock_at = null), now))
        assertNull(submission.toAssignmentTodo(detail.copy(late_at = null, lock_at = ""), now))
        val upcoming = detail.copy(due_at = after, late_at = null, lock_at = null)
        assertEquals(after, assertNotNull(submission.toAssignmentTodo(upcoming, now)).submission_deadline)
        assertNull(submission.toAssignmentTodo(upcoming, Instant.parse(after)))
    }

    @Test
    fun detailDueTakesPrecedenceOverCacheAndFallsBackWhenInvalid() {
        val noEnd = detail.copy(due_at = after, late_at = null, lock_at = null)
        assertEquals(after, assertNotNull(submission.toAssignmentTodo(noEnd, now)).due_date)
        assertNull(submission.copy(cached_due_date = after).toAssignmentTodo(noEnd.copy(due_at = before), now))
        for (due in listOf(null, "", "invalid")) {
            assertEquals(after, assertNotNull(submission.copy(cached_due_date = after)
                .toAssignmentTodo(noEnd.copy(due_at = due), now)).due_date)
        }
    }

    @Test
    fun invalidBoundsAndUnknownAllDatesAreExcluded() {
        for (d in listOf(detail.copy(unlock_at = "invalid"), detail.copy(lock_at = null, late_at = "invalid"), detail.copy(lock_at = "invalid"))) {
            assertNull(submission.toAssignmentTodo(d, now))
        }
        val noDue = submission.copy(cached_due_date = null)
        assertNull(noDue.toAssignmentTodo(AssignmentDetail(due_at = "invalid"), now))
        val bounded = assertNotNull(noDue.toAssignmentTodo(detail.copy(due_at = null), now))
        assertEquals("", bounded.due_date)
        assertEquals(later, bounded.submission_deadline)
    }

    @Test
    fun publicationAndUnlockAndUserLocksOverrideFutureDeadlines() {
        assertNull(submission.toAssignmentTodo(detail.copy(published = false), now))
        assertNull(submission.toAssignmentTodo(detail.copy(workflow_state = "unpublished"), now))
        assertNull(submission.toAssignmentTodo(detail.copy(workflow_state = "deleted"), now))
        assertNull(submission.toAssignmentTodo(detail.copy(locked_for_user = true), now))
        assertNull(submission.toAssignmentTodo(detail.copy(unlock_at = after), now))
        assertNotNull(submission.toAssignmentTodo(detail.copy(unlock_at = current), now))
        assertNull(submission.toAssignmentTodo(detail.copy(unlock_at = later), now))
        assertNotNull(submission.toAssignmentTodo(detail.copy(published = null, locked_for_user = null), now))
    }

    @Test
    fun completedExcusedAndUnknownSubmissionStatesAreExcluded() {
        for (state in listOf("submitted", "graded", "pending_review", "", "unknown", null)) {
            assertNull(submission.copy(workflow_state = state).toAssignmentTodo(detail, now))
        }
        assertNull(submission.copy(submitted_at = before).toAssignmentTodo(detail, now))
        assertNull(submission.copy(excused = true).toAssignmentTodo(detail, now))
        // late is a submission lateness flag, not permission to submit.
        assertNotNull(submission.copy(late = true).toAssignmentTodo(detail, now))
        assertNull(submission.copy(late = true).toAssignmentTodo(detail.copy(lock_at = before), now))
    }

    @Test
    fun timezoneOffsetsAreComparedAsInstantsAndEmptyTypesDoNotThrow() {
        val todo = assertNotNull(submission.toAssignmentTodo(detail.copy(
            lock_at = "2026-10-02T21:00:00+09:00", submission_types = emptyList(),
        ), now))
        assertEquals(after, todo.submission_deadline)
        assertEquals("assignment", todo.component_type)
        assertEquals("quiz", assertNotNull(submission.toAssignmentTodo(detail.copy(submission_types = listOf("online_quiz")), now)).component_type)
    }
}
