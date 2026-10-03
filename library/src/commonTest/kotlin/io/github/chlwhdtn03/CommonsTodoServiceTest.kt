package io.github.chlwhdtn03

import io.github.chlwhdtn03.data.Lms.TodoDetail
import io.github.chlwhdtn03.data.Lms.TodoDetailContentData
import io.github.chlwhdtn03.data.Lms.TodoDetailItemContentData
import io.github.chlwhdtn03.data.Lms.TodoDetailModuleItem
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

class CommonsTodoServiceTest {
    @Test
    fun sequentialAndParallelIncludeOverdueVideosUntilLateAt() = runTest {
        val past = "2000-01-01T00:00:00Z"
        val future = "2099-01-01T00:00:00Z"
        fun video(id: Int, due: String?, late: String?, completed: Boolean = false) = TodoDetailModuleItem(
            module_item_id = id,
            completed = completed,
            content_data = TodoDetailContentData(
                item_id = id,
                item_content_type = "commons",
                due_at = due,
                late_at = late,
                item_content_data = TodoDetailItemContentData(duration = 60.0),
            ),
        )
        val modules = listOf(TodoDetail(module_items = listOf(
            video(1, past, future), // 일반 마감 이후에도 지각 기한이 남으면 포함
            video(2, past, past),
            video(3, past, null),
            video(4, future, null), // 지각 기한이 없으면 일반 마감 사용
            video(5, past, future, completed = true),
            video(6, future, past), // 일반 마감보다 지각 종료 기한 우선
            video(7, past, "invalid"),
            video(8, null, future),
            video(9, future, " "),
        )))
        val engine = MockEngine { request ->
            assertEquals("/learningx/api/v1/courses/100/modules", request.url.encodedPath)
            respond(Json.encodeToString(modules), HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json() } }
        try {
            val service = TodoService(client, LmsCourseClient(client) { "" }, backgroundScope, mutableMapOf()) {}
            val sequential = service.buildCourseTodo(100, emptyList(), includeCommons = true)
            val parallel = service.buildCourseTodoParallel(100, emptyList(), includeCommons = true, todoDetails = modules)
            assertEquals(sequential, parallel)
            assertEquals(setOf(1, 4, 8, 9), sequential.todoList.map { it.moduleItemId }.toSet())
            val overdue = sequential.todoList.single { it.moduleItemId == 1 }
            assertEquals(past, overdue.due_at)
            assertEquals(past, overdue.due_date)
            assertEquals(future, overdue.late_at)
            assertEquals(future, overdue.submission_deadline)
            assertEquals(future, sequential.todoList.single { it.moduleItemId == 4 }.submission_deadline)
        } finally {
            client.close()
        }
    }
}
