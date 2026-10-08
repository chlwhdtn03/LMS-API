package io.github.chlwhdtn03.internal

import io.github.chlwhdtn03.LmsApi
import io.github.chlwhdtn03.data.Cyber.CyberEvaluation
import io.github.chlwhdtn03.data.Cyber.CyberEvaluationType
import io.github.chlwhdtn03.data.Cyber.CyberSubject
import io.github.chlwhdtn03.data.Cyber.CyberWeek
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** 전체 과목 조회가 성공한 뒤 한 번만 스냅샷을 전송해 과목 일부만 통계에 들어가는 것을 방지합니다. */
@OptIn(ExperimentalTime::class)
internal class CyberTodoService(
    private val courseService: CyberCourseService,
    private val snapshotTracker: TodoSnapshotTracker,
) {
    private val requestMutex = Mutex()

    suspend fun getSubjects(postHogDistinctId: String?): List<CyberSubject> = requestMutex.withLock {
        val subjects = courseService.getSubjects()
        if (!postHogDistinctId.isNullOrBlank()) {
            val now = Clock.System.now()
            val items = subjects.flatMap { subject ->
                cyberSnapshotItems(
                    subject,
                    courseService.getWeeklyLectures(subject),
                    courseService.getEvaluations(subject),
                    now,
                )
            }.distinctBy { it.itemKey }
            snapshotTracker.track(
                stats = LmsApi.UnsubmittedStats(items.size, items.count { it.isOverdueUnsubmitted }),
                items = items,
                postHogDistinctId = postHogDistinctId,
            )
        }
        subjects
    }
}

@OptIn(ExperimentalTime::class)
internal fun cyberSnapshotItems(
    subject: CyberSubject,
    weeks: List<CyberWeek>,
    evaluations: List<CyberEvaluation>,
    now: Instant,
): List<TodoSnapshotItem> {
    val courseKey = "${subject.year}:${subject.semesterCode}:${subject.courseCode}:${subject.deptCode}"
    val courseId = JsonPrimitive(subject.courseCode)
    val lectureItems = weeks.flatMap { week ->
        // 출석인정기간의 끝 날짜는 한국 시간으로 그 날의 마지막 순간까지 포함합니다.
        val dueAt = cyberAttendanceDeadline(week.attendancePeriod)
        week.lectures.map { lecture ->
            TodoSnapshotItem(
                itemKey = "commons:$courseKey:${week.weekNo}:${lecture.lectureNo}",
                itemType = "commons",
                courseId = courseId,
                dueAt = dueAt,
                isCompleted = lecture.isCompleted,
                isOverdueUnsubmitted = !lecture.isCompleted && cyberDeadlinePassed(dueAt, now),
            )
        }
    }
    val evaluationItems = evaluations
        .filter { it.type == CyberEvaluationType.QUIZ || it.type == CyberEvaluationType.ASSIGNMENT }
        // 본제출/재제출 행은 동일 항목입니다. 어느 행이든 완료이면 제출 완료로 처리합니다.
        .groupBy { "${it.typeCode}:${it.round}:${it.week}:${it.title}" }
        .map { (evaluationKey, rows) ->
            val evaluation = rows.firstOrNull { !it.isResubmission } ?: rows.first()
            val completed = rows.any { cyberEvaluationCompleted(it.submitStatus) }
            val dueAt = cyberEvaluationDeadline(evaluation.endAt)
            TodoSnapshotItem(
                itemKey = "submission:$courseKey:$evaluationKey",
                itemType = "submission",
                courseId = courseId,
                dueAt = dueAt,
                isCompleted = completed,
                isOverdueUnsubmitted = !completed && cyberDeadlinePassed(dueAt, now),
                workflowState = evaluation.submitStatus,
            )
        }
    return (lectureItems + evaluationItems).distinctBy { it.itemKey }
}

internal fun cyberEvaluationCompleted(status: String): Boolean {
    val normalized = status.filterNot { it.isWhitespace() }
    return normalized in setOf("제출", "제출완료", "응시", "응시완료", "완료", "참여", "참여완료", "평가완료", "채점완료")
}

@OptIn(ExperimentalTime::class)
private fun cyberDeadlinePassed(dueAt: String, now: Instant): Boolean =
    Instant.parseOrNull(dueAt)?.let { it <= now } ?: false

internal fun cyberAttendanceDeadline(period: String): String {
    val date = Regex("""\d{4}[.\-/]\s*\d{1,2}[.\-/]\s*\d{1,2}""").findAll(period).lastOrNull()?.value
        ?: return ""
    val parts = date.split(Regex("""[.\-/]\s*"""))
    return "${parts[0]}-${parts[1].padStart(2, '0')}-${parts[2].padStart(2, '0')}T23:59:59.999999999+09:00"
}

internal fun cyberEvaluationDeadline(value: String): String {
    return if (Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""").matches(value)) {
        value.replace(' ', 'T') + ":00+09:00"
    } else ""
}
