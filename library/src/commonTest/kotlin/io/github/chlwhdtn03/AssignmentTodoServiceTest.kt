package io.github.chlwhdtn03

import io.github.chlwhdtn03.data.Lms.AssignmentDetail
import io.github.chlwhdtn03.data.Lms.Submission
import io.github.chlwhdtn03.internal.LmsCourseClient
import io.github.chlwhdtn03.internal.TodoService
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class AssignmentTodoServiceTest {
    @Test
    fun sequentialAndParallelFetchOverdueDetailsAndReturnSameTodos() = runTest {
        val due = "2000-01-01T00:00:00Z"
        val end = "2099-01-01T00:00:00Z"
        val requested = mutableListOf<Int>()
        val details = mapOf(
            1 to AssignmentDetail(id = 1, due_at = due, late_at = end, published = true),
            2 to AssignmentDetail(id = 2, due_at = due, lock_at = end, published = true),
            3 to AssignmentDetail(id = 3, due_at = due, late_at = due, published = true),
            4 to AssignmentDetail(id = 4, due_at = end, published = false),
            5 to AssignmentDetail(id = 5, due_at = end, unlock_at = end, published = true),
            6 to AssignmentDetail(id = 6, due_at = end, locked_for_user = true, published = true),
            7 to AssignmentDetail(id = 7, due_at = end, published = true),
            8 to AssignmentDetail(id = 8, due_at = "invalid", lock_at = "invalid", published = true),
        )
        val engine = MockEngine { request ->
            val id = request.url.encodedPath.substringAfterLast('/').toInt()
            requested += id
            respond(Json.encodeToString(details.getValue(id)), HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json() } }
        try {
            val service = TodoService(client, LmsCourseClient(client) { "" }, backgroundScope, mutableMapOf()) {}
            val submissions = (1..8).map { Submission(
                assignment_id = it, cached_due_date = due, workflow_state = "unsubmitted",
            ) } + listOf(
                Submission(assignment_id = 9, cached_due_date = end, workflow_state = "submitted"),
                Submission(assignment_id = 10, cached_due_date = end, workflow_state = "unsubmitted", excused = true),
                Submission(assignment_id = 0, workflow_state = "unsubmitted"),
                Submission(assignment_id = 1, cached_due_date = due, workflow_state = "unsubmitted"),
            )
            val sequential = service.buildCourseTodo(100, submissions, includeCommons = false)
            assertEquals((1..8).toSet(), requested.toSet())
            assertEquals(8, requested.size)
            requested.clear()
            val parallel = service.buildCourseTodoParallel(100, submissions, includeCommons = false, todoDetails = emptyList())
            assertEquals((1..8).toSet(), requested.toSet())
            assertEquals(8, requested.size)
            assertEquals(sequential, parallel)
            assertEquals(listOf(1, 7), sequential.todoList.map { it.assignment_id })
            assertEquals(end, sequential.todoList.first().submission_deadline)
        } finally {
            client.close()
        }
    }
}
