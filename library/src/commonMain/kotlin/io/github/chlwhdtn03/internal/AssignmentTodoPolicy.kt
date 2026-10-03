package io.github.chlwhdtn03.internal

import io.github.chlwhdtn03.data.Lms.AssignmentDetail
import io.github.chlwhdtn03.data.Lms.Submission
import io.github.chlwhdtn03.data.Lms.TodoList
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

internal fun Submission.isCompletedForTodo(): Boolean =
    excused == true || !submitted_at.isNullOrBlank() ||
        workflow_state in setOf("submitted", "pending_review", "graded")

/** See assignment-todo-policy.md for evidence, precedence and missing/invalid date policy. */
@OptIn(ExperimentalTime::class)
internal fun Submission.toAssignmentTodo(detail: AssignmentDetail, now: Instant): TodoList? {
    val assignmentId = assignment_id?.takeIf { it > 0 } ?: return null
    if (isCompletedForTodo() || workflow_state != "unsubmitted") return null
    if (detail.published == false || detail.workflow_state in setOf("unpublished", "deleted")) return null
    if (detail.locked_for_user == true) return null

    fun String?.parseDate(): Instant? =
        takeUnless { it.isNullOrBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }

    // Invalid availability bounds must not silently become unrestricted availability.
    // late_at is the late-submission deadline; lock_at is retained as raw metadata only.
    val endValue = detail.late_at
    val availabilityDates = listOf(detail.unlock_at, endValue)
    if (availabilityDates.any { !it.isNullOrBlank() && it.parseDate() == null }) return null
    val unlock = detail.unlock_at.parseDate()
    if (unlock != null && now < unlock) return null

    // The detail endpoint reflects the requesting user's overrides; the submission date is a cache.
    val due = detail.due_at.parseDate() ?: cached_due_date.parseDate()
    val explicitEnd = endValue.parseDate()
    val end = explicitEnd ?: due ?: return null
    if (unlock != null && unlock >= end) return null
    if (now >= end) return null

    return TodoList(
        section_id = 0,
        unit_id = 0,
        component_id = 0,
        generated_from_lecture_content = false,
        component_type = if (detail.submission_types.orEmpty().firstOrNull() == "online_quiz") "quiz" else "assignment",
        assignment_id = assignmentId,
        title = detail.name?.takeIf { it.isNotBlank() } ?: name,
        due_date = due?.toString().orEmpty(),
        late_at = detail.late_at.orEmpty(),
        unlock_at = detail.unlock_at.orEmpty(),
        description = detail.description,
        url = detail.html_url,
        attachments = attachments,
        due_at = due?.toString().orEmpty(),
        lock_at = detail.lock_at.orEmpty(),
        submission_deadline = end.toString(),
    )
}
