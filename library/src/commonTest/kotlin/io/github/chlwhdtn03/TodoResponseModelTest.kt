package io.github.chlwhdtn03

import io.github.chlwhdtn03.data.Lms.AssignmentDetail
import io.github.chlwhdtn03.data.Lms.Todos
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class TodoResponseModelTest {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun parsesNestedTodoResponse() {
        val response = json.decodeFromString<Todos>(
            """
            {
              "to_dos": [
                {
                  "course_id": 44383,
                  "activities": {
                    "total_unread_announcements": 0,
                    "total_announcements": 1,
                    "total_incompleted_movies": 2,
                    "total_unsubmitted_assignments": 1,
                    "total_unsubmitted_quizzes": 0
                  },
                  "todo_list": [
                    {
                      "section_id": 0,
                      "unit_id": 0,
                      "component_id": 0,
                      "generated_from_lecture_content": false,
                      "component_type": "assignment",
                      "assignment_id": 718158,
                      "title": "실습과제 1",
                      "due_date": "2026-03-19T14:59:59Z"
                    }
                  ]
                }
              ],
              "total_count": 1,
              "total_unread_messages": 3
            }
            """.trimIndent(),
        )

        assertEquals(1, response.total_count)
        assertEquals(44383, response.to_dos.single().course_id)
        assertEquals(1, response.to_dos.single().activities.total_unsubmitted_assignments)
        assertEquals(718158, response.to_dos.single().todo_list.single().assignment_id)
    }

    @Test
    fun parsesAssignmentDetailUsedByTodoService() {
        val response = json.decodeFromString<AssignmentDetail>(
            """
            {
              "id": 718158,
              "course_id": 44383,
              "name": "실습과제 1",
              "description": "<p>과제 설명</p>",
              "submission_types": ["online_upload"],
              "due_at": "2026-03-19T14:59:59Z",
              "lock_at": "2026-03-20T14:59:59Z",
              "late_at": "2026-03-20T14:59:59Z",
              "points_possible": 10.0
            }
            """.trimIndent(),
        )

        assertEquals(718158, response.id)
        assertEquals("실습과제 1", response.name)
        assertEquals(listOf("online_upload"), response.submission_types)
        assertEquals(10.0, response.points_possible)
    }

    @Test
    fun parsesTodoDetailWithUnlockAt() {
        val jsonString = """
        [
          {
            "module_id": 878875,
            "title": "1주차",
            "position": 1,
            "published": null,
            "unlock_at": null,
            "module_items": [
              {
                "module_item_id": 3370359,
                "title": "컴파일러-1장 강의노트",
                "content_type": "attendance_item",
                "content_id": 909084,
                "content_data": {
                  "item_id": 909084,
                  "course_id": 49313,
                  "use_attendance": false,
                  "item_content_type": "commons",
                  "title": "컴파일러-1장 강의노트",
                  "unlock_at": "2026-08-30T15:00:00Z",
                  "late_at": null,
                  "due_at": "2026-09-14T14:59:59Z",
                  "lock_at": "2026-09-14T14:59:59Z"
                },
                "completed": true
              },
              {
                "module_item_id": 3378859,
                "title": "과제-1",
                "content_type": "assignment",
                "content_id": 738244,
                "content_data": {
                  "unlock_at": "2026-09-02T15:00:00Z",
                  "due_at": "2026-09-08T00:00:00Z",
                  "lock_at": "2026-09-08T00:00:00Z"
                },
                "completed": true
              }
            ]
          }
        ]
        """.trimIndent()

        val list = json.decodeFromString<List<io.github.chlwhdtn03.data.Lms.TodoDetail>>(jsonString)
        assertEquals(1, list.size)
        val module = list.first()
        assertEquals(878875, module.module_id)
        assertEquals(null, module.unlock_at)
        val item1 = module.module_items?.get(0)
        assertEquals("2026-08-30T15:00:00Z", item1?.content_data?.unlock_at)
        val item2 = module.module_items?.get(1)
        assertEquals("2026-09-02T15:00:00Z", item2?.content_data?.unlock_at)
    }
}
