package io.github.chlwhdtn03

import com.yourssu.lms.data.Discussion
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
const val LMS_POST_LOGIN = "https://canvas.ssu.ac.kr/login/canvas"
const val LMS_CONFIRM_LOGIN = "https://canvas.ssu.ac.kr/?login_success=1"
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
    println("sToken : $token")

    if(token.isBlank())
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

    if(apiBearerToken.isBlank())
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
            append(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")

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
                        "absent" -> AttendanceType.ABSENT
                        "late" -> AttendanceType.LATE // TODO 지각일때 뭐로 뜨는지 확인 필요
                        else -> AttendanceType.NONE
                    }
                }
            } ?: emptyList(),
            discussions = client.get("https://canvas.ssu.ac.kr/api/v1/courses/45446/discussion_topics?only_announcements=true&per_page=40&page=1&filter_by=all&no_avatar_fallback=1&include[]=sections_user_count&include[]=sections") {
                headers {
                    append("Referer", "https://canvas.ssu.ac.kr/courses/${it.id}/announcements")
                }
            }.body<List<Discussion>>()
        )
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