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

const val LMS_LOGIN_URL = "https://smartid.ssu.ac.kr/Symtra_sso/smln_pcs.asp"
const val LMS_CERT_URL = "https://lms.ssu.ac.kr/xn-sso/gw-cb.php"
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
        })
    }
    followRedirects = true
}

/**
 * @param id LMS 아이디
 * @param password LMS 비밀번호
 * @return LMS로그인에 성공하면 true, 실패하면 false를 반환합니다.
 */
suspend fun loginLMS(id: String, password: String) : Boolean {
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
    println(token)

    if(token.isBlank())
        throw IllegalArgumentException("아이디 또는 비밀번호가 일치하지 않습니다.")

    val redirectURL = client.get(LMS_CERT_URL) {
        url {
            parameters.append("sToken", token)
            parameters.append("sldno", id)
        }
    }.bodyAsText()
        .substringAfter("iframe.src=\"")
        .substringBefore("\";")

    val apiToken = client.get(redirectURL)
        .headers.getAll("Set-Cookie")?.find { it.contains("xn_api_token") }
        ?.substringAfter("xn_api_token=")
        ?.substringBefore(";") ?: ""
    if(apiToken.isBlank())
        throw RuntimeException("API 토큰값을 불러오지 못했습니다. 다시 시도해주세요.")
    apiBearerToken = apiToken
    println("Bearer Token : $apiBearerToken")

    // TODO 토큰은 얻었지만, LMS_CERT_URL로 접속하고나서 실패하는 경우를 찾아야함

    isLoggined = true
    lmsId = id
    return isLoggined // 토큰값이 비어있거나 Null이면 로그인 실패
}

/**
 * @throws IllegalStateException loginLMS()를 통해 로그인을 하지 않은 경우
 */
@ExperimentalTime
suspend fun getSubjects(): List<Subject> {
    if (!isLoggined || lmsId.isBlank())
        throw IllegalStateException("LMS 로그인이 되어있지 않습니다.")

    // 학기정보 불러옴
    val semesterResponse = client.get("https://canvas.ssu.ac.kr/learningx/api/v1/users/${lmsId}/terms?include_invited_course_contained=true") {
        headers { append("Authorization", "Bearer $apiBearerToken") }
    }.body<Terms>()

    // 제일 최신학기 정보 얻어옴
    val semesterId = semesterResponse.enrollment_terms.firstOrNull()?.id
        ?: throw IllegalStateException("학기 정보를 불러오지 못했습니다.")

    val lectures = client.get("https://canvas.ssu.ac.kr/learningx/api/v1/learn_activities/courses?term_ids[]=$semesterId") {
        headers { append("Authorization", "Bearer $apiBearerToken") }
    }.body<List<Lecture>>()

    val learnStatuses = client.get("https://canvas.ssu.ac.kr/learningx/api/v1/learn_activities/learnstatus?term_ids=${semesterId}&type=subsection") {
        headers { append("Authorization", "Bearer $apiBearerToken") }
    }.body<LearnStatuses>()

    val todoList = client.get("https://canvas.ssu.ac.kr/learningx/api/v1/learn_activities/to_dos?term_ids[]=${semesterId}") {
        headers { append("Authorization", "Bearer $apiBearerToken") }
    }.body<Todos>()

    val announceList = client.get("https://canvas.ssu.ac.kr/learningx/api/v1/learn_activities/to_dos?term_ids[]=${semesterId}") {
        headers { append("Authorization", "Bearer $apiBearerToken") }
    }.body<Todos>()

    return lectures.map {
        Subject(
            id = it.id,
            termId = it.term_id,
            termName = semesterResponse.enrollment_terms.find { term -> term.id == it.term_id }?.name ?: "학기정보 없음",
            name = it.name,
            professor = it.professors,
            totalStudents = it.total_students,
            todoList = todoList.to_dos.find { todo -> todo.course_id == it.id }?.todo_list ?: emptyList(),
            attendances = learnStatuses.learnstatuses.find { status -> status.course.id == it.id }?.sections?.map { section ->
                section.subsections.map { sub ->
                    when (sub.status) {
                        "attendance" -> AttendanceType.ATTENDANCE
                        "absence" -> AttendanceType.ABSENCE
                        "late" -> AttendanceType.LATE
                        else -> AttendanceType.NONE
                    }
                }
            } ?: emptyList()
        )
    }
}
