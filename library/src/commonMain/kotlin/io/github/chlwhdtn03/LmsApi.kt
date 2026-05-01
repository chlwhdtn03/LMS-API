package io.github.chlwhdtn03

import io.github.chlwhdtn03.data.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kotlin.time.ExperimentalTime

object LmsApi {
    private const val LMS_LOGIN_URL = "https://smartid.ssu.ac.kr/Symtra_sso/smln_pcs.asp"
    private const val LMS_CERT_URL = "https://lms.ssu.ac.kr/xn-sso/gw-cb.php"
    private const val LMS_POST_LOGIN = "https://canvas.ssu.ac.kr/login/canvas"
    private const val LMS_CONFIRM_LOGIN = "https://canvas.ssu.ac.kr/?login_success=1"
    var isLoggined = false
    private var lmsId = ""
    private var apiBearerToken = ""

    private val client = HttpClient() {
        install(HttpCookies)
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                prettyPrint = true
                isLenient = true
                coerceInputValues = true
            })
        }
        followRedirects = true
    }

    private data class AssignmentMetadata(
        val groupName: String,
        val name: String,
        val maxScore: Double,
    )

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
    private suspend fun fetchLearnStatuses(term: Term): LearnStatuses {
        return client.get("https://canvas.ssu.ac.kr/learningx/api/v1/learn_activities/learnstatus?term_ids=${term.id}&type=subsection") {
            headers { append("Authorization", "Bearer $apiBearerToken") }
        }.body<LearnStatuses>()
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun fetchTodos(term: Term): Todos {
        return client.get("https://canvas.ssu.ac.kr/learningx/api/v1/learn_activities/to_dos?term_ids[]=${term.id}") {
            headers { append("Authorization", "Bearer $apiBearerToken") }
        }.body<Todos>()
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

    private suspend fun fetchSubmissions(courseId: Int): List<Submission> {
        return client.get("https://canvas.ssu.ac.kr/api/v1/courses/${courseId}/students/submissions") {
            url {
                parameters.append("per_page", "50")
            }
        }.body<List<Submission>>()
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
                        maxScore = assignment.points_possible,
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

    private fun buildScoredAssignments(
        submissions: List<Submission>,
        assignmentMetadataById: Map<Int, AssignmentMetadata>,
    ): List<ScoredAssignment> {
        val result = mutableListOf<ScoredAssignment>()
        for (submission in submissions) {
            if (!(submission.score > Double.NEGATIVE_INFINITY)) continue

            val metadata = assignmentMetadataById[submission.assignment_id]
            result += ScoredAssignment(
                groupName = metadata?.groupName ?: "알 수 없음",
                name = metadata?.name ?: "알 수 없음",
                score = submission.score,
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
    suspend fun loginLMS(id: String, password: String): Boolean {
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
    suspend fun getTerms(): List<Term> {
        checkLoggedIn()
        return fetchTerms()
    }

    /**
     * @param loadingState Float 변수에는 진행률을 각 단계마다 전달합니다. (0.0f~1.0f)
     * @throws IllegalStateException loginLMS()를 통해 로그인을 하지 않은 경우
     */
    @ExperimentalTime
    suspend fun getSubjects(term: Term, loadingState: (Float) -> Unit = {}): List<Subject> {
        checkLoggedIn()

        val lectures = fetchLectures(term)
        loadingState(0.1f)

        val learnStatuses = fetchLearnStatuses(term)
        loadingState(0.2f)

        val todoList = fetchTodos(term)
        loadingState(0.3f)

        val todoByCourseId = todoList.to_dos.associateFirstById { it.course_id }
        val learnStatusByCourseId = learnStatuses.learnstatuses.associateFirstById { it.course.id }
        val weight = 0.7f / lectures.size
        var nowProgress = 0.3f
        return lectures.map { lecture ->
            nowProgress += weight
            loadingState(nowProgress)

            val assignmentMetadataById = fetchAssignmentGroups(lecture.id).toAssignmentMetadataById()
            val submissions = fetchSubmissions(lecture.id)
            applyAssignmentMetadata(submissions, assignmentMetadataById)

            Subject(
                id = lecture.id,
                termId = lecture.term_id,
                termName = term.name ?: "학기정보 없음",
                name = lecture.name,
                professor = lecture.professors,
                totalStudents = lecture.total_students,
                todoList = todoByCourseId[lecture.id]?.todo_list ?: emptyList(),
                attendances = learnStatusByCourseId[lecture.id]?.sections?.map { section ->
                    section.subsections.map { sub ->
                        when (sub.status) {
                            "attendance" -> AttendanceType.ATTENDANCE
                            "absent" -> AttendanceType.ABSENT
                            "late" -> AttendanceType.LATE // TODO 지각일때 뭐로 뜨는지 확인 필요
                            else -> AttendanceType.NONE
                        }
                    }
                } ?: emptyList(),
                discussions = fetchDiscussions(lecture.id),
                submissions = submissions,
                scoredAssignments = buildScoredAssignments(submissions, assignmentMetadataById),
            )
        }
    }

    /**
     * 제출해야 할 과제, 동영상 시청 정보만 빠르게 가져옵니다. (SSU-Time 전용)
     * @param loadingState Float 변수에는 진행률을 각 단계마다 전달합니다. (0.0f~1.0f)
     * @throws IllegalStateException loginLMS()를 통해 로그인을 하지 않은 경우
     */
    @ExperimentalTime
    suspend fun getTodoList(term: Term, loadingState: (Float) -> Unit = {}): List<Subject> {
        checkLoggedIn()

        val lectures = fetchLectures(term)
        loadingState(0.1f)

        val todoList = fetchTodos(term)
        loadingState(0.3f)

        val todoByCourseId = todoList.to_dos.associateFirstById { it.course_id }
        val weight = 0.7f / lectures.size
        var nowProgress = 0.3f
        return lectures.map { lecture ->
            nowProgress += weight
            loadingState(nowProgress)

            val assignmentMetadataById = fetchAssignmentGroups(lecture.id).toAssignmentMetadataById()
            val submissions = fetchSubmissions(lecture.id)
            applyAssignmentMetadata(submissions, assignmentMetadataById)

            Subject(
                id = lecture.id,
                termId = lecture.term_id,
                termName = term.name ?: "학기정보 없음",
                name = lecture.name,
                professor = lecture.professors,
                totalStudents = lecture.total_students,
                todoList = todoByCourseId[lecture.id]?.todo_list ?: emptyList(),
                attendances = emptyList(),
                discussions = emptyList(),
                submissions = submissions,
                scoredAssignments = emptyList(),
            )
        }
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

expect fun pemToString(rawPem: String, rawPw: String): String
