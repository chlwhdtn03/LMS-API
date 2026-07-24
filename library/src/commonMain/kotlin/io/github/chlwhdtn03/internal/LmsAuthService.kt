package io.github.chlwhdtn03.internal

import io.github.chlwhdtn03.data.Lms.*
import io.github.chlwhdtn03.decodeHtmlEntities
import io.github.chlwhdtn03.pemToString
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlin.time.ExperimentalTime

internal data class AuthenticatedLmsSession(
    val userId: String,
    val bearerToken: String,
)

/**
 * LMS 로그인과 로그인 사용자·세션 조회를 담당합니다.
 *
 * 로그인 상태 저장과 세션 초기화는 계속 [LmsApi][io.github.chlwhdtn03.LmsApi]가 담당합니다.
 */
@OptIn(ExperimentalTime::class)
internal class LmsAuthService(
    private val client: HttpClient,
    private val json: Json,
) {
    suspend fun login(id: String, password: String): AuthenticatedLmsSession {
        val loginResponse = client.submitForm(
            url = LMS_LOGIN_URL,
            formParameters = parameters {
                append("userid", id)
                append("pwd", password)
            },
        )
        val loginBody = loginResponse.bodyAsText()
        val sToken = extractSsoToken(loginBody)
            .ifBlank { extractSsoTokenFromCookies(loginResponse.headers.getAll("Set-Cookie")) }
        if (sToken.isBlank()) {
            throw IllegalArgumentException("아이디 또는 비밀번호가 일치하지 않습니다.")
        }

        val certResponse = client.get(LMS_CERT_URL) {
            url {
                parameters.append("sToken", sToken)
                parameters.append("sIdno", id)
            }
        }
        val certBody = certResponse.bodyAsText()
        var redirectUrl = certBody
            .substringAfter("iframe.src=\"")
            .substringBefore("\";")
        if (!redirectUrl.startsWith("http")) {
            redirectUrl = "$CANVAS_BASE_URL$redirectUrl"
        }

        val apiTokenResponse = client.get(redirectUrl)
        val bearerToken = resolveBearerToken(redirectUrl, apiTokenResponse.headers.getAll("Set-Cookie"))
        if (bearerToken.isBlank()) {
            throw RuntimeException("API 토큰값을 불러오지 못했습니다. 다시 시도해주세요.")
        }

        val tokenBody = apiTokenResponse.bodyAsText()
        val pem = tokenBody
            .substringAfter("window.loginCryption(\"")
            .substringBefore("\")")
            .substringAfter(", \"")
        val rawPassword = tokenBody
            .substringAfter("window.loginCryption(\"")
            .substringBefore("\"")
        val authenticityToken = Regex(
            """<input\b(?=[^>]*\bname=["']authenticity_token["'])(?=[^>]*\bvalue=["']([^"']+)["'])[^>]*>""",
            RegexOption.IGNORE_CASE,
        ).find(tokenBody)?.groupValues?.get(1)?.decodeHtmlEntities().orEmpty()
        val decryptedPassword = pemToString(rawPem = pem, rawPw = rawPassword)

        client.submitForm(
            url = canvasUrl("/login/canvas"),
            formParameters = parameters {
                append("utf8", "✓")
                if (authenticityToken.isNotBlank()) {
                    append("authenticity_token", authenticityToken)
                }
                append("redirect_to_ssl", "1")
                append("after_login_url", "")
                append("pseudonym_session[unique_id]", id)
                append("pseudonym_session[password]", decryptedPassword)
                append("pseudonym_session[remember_me]", "0")
            },
        ) {
            headers {
                append(HttpHeaders.Origin, CANVAS_BASE_URL)
                append(HttpHeaders.Referrer, redirectUrl)
                append(HttpHeaders.CacheControl, "max-age=0")
                append(HttpHeaders.Accept, HTML_ACCEPT)
            }
        }

        client.get(canvasUrl("/?login_success=1")) {
            headers {
                append(HttpHeaders.Referrer, redirectUrl)
            }
        }
        client.get(saintUrl("/webSSO/sso.jsp")) {
            headers {
                append(HttpHeaders.UserAgent, USER_AGENT)
                append(HttpHeaders.Accept, HTML_ACCEPT)
            }
        }

        val loginInfo = getLoginInfo(id)
        if (loginInfo.user_login != id) {
            throw IllegalStateException("로그인 사용자 검증에 실패했습니다.")
        }

        return AuthenticatedLmsSession(userId = id, bearerToken = bearerToken)
    }

    suspend fun getTerms(userId: String, bearerToken: String): List<Term> {
        val response = client.get(
            canvasUrl("/learningx/api/v1/users/$userId/terms?include_invited_course_contained=true"),
        ) {
            headers {
                if (bearerToken.isNotBlank()) {
                    append(HttpHeaders.Authorization, "Bearer $bearerToken")
                }
            }
        }
        val body = response.bodyAsText()
        if (response.status.value != 200) {
            throw IllegalStateException("LMS 학기 조회 실패 (${response.status.value}): $body")
        }

        return try {
            json.decodeFromString<Terms>(body).enrollment_terms
        } catch (wrappedResponseError: Exception) {
            try {
                json.decodeFromString<List<Term>>(body)
            } catch (_: Exception) {
                throw IllegalStateException("LMS 학기 파싱 실패. 응답: $body", wrappedResponseError)
            }
        }
    }

    suspend fun getLoginInfo(userId: String): Info {
        return client.get(lmsUrl("/api/v1/users/$userId")).body()
    }

    suspend fun getSession(): LmsSessionResponse {
        val cookiesByKey = linkedMapOf<String, LmsSessionCookie>()
        for (urlString in COOKIE_URLS) {
            for (cookie in client.cookies(Url(urlString))) {
                val sessionCookie = LmsSessionCookie(
                    name = cookie.name,
                    value = cookie.value,
                    domain = ".ssu.ac.kr",
                    path = cookie.path ?: "/",
                )
                val key = "${sessionCookie.domain}|${sessionCookie.path}|${sessionCookie.name}"
                cookiesByKey[key] = sessionCookie
            }
        }
        return LmsSessionResponse(
            lmsSession = LmsSession(cookies = cookiesByKey.values.toList()),
        )
    }

    private fun extractSsoToken(body: String): String {
        if (!body.contains("sToken=")) {
            return ""
        }
        val rawToken = body.substringAfter("sToken=").substringBefore("&").substringBefore("'")
        return Regex("""x(2B|2F|3D|5F|78|79|7A)""").replace(rawToken) { match ->
            match.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: match.value
        }
    }

    private fun extractSsoTokenFromCookies(cookies: List<String>?): String {
        return cookies
            ?.find { it.contains("sToken") }
            ?.substringAfter("sToken=")
            ?.substringBefore(";")
            .orEmpty()
    }

    private suspend fun resolveBearerToken(
        redirectUrl: String,
        responseCookies: List<String>?,
    ): String {
        val redirectToken = redirectUrl
            .takeIf { it.contains("api_token=") }
            ?.substringAfter("api_token=")
            ?.substringBefore("&")
            .orEmpty()
        if (redirectToken.isNotBlank()) {
            return redirectToken
        }

        val cookieToken = (
            client.cookies(CANVAS_BASE_URL) +
                client.cookies(LMS_BASE_URL)
            ).find { it.name == "xn_api_token" }?.value.orEmpty()
        if (cookieToken.isNotBlank()) {
            return cookieToken
        }

        return responseCookies
            ?.find { it.contains("xn_api_token") }
            ?.substringAfter("xn_api_token=")
            ?.substringBefore(";")
            .orEmpty()
    }

    private fun canvasUrl(path: String): String = resolveUrl(CANVAS_BASE_URL, path)

    private fun lmsUrl(path: String): String = resolveUrl(LMS_BASE_URL, path)

    private fun saintUrl(path: String): String = resolveUrl(SAINT_BASE_URL, path)

    private fun resolveUrl(baseUrl: String, path: String): String {
        return if (path.startsWith("/")) "$baseUrl$path" else "$baseUrl/$path"
    }

    private companion object {
        const val CANVAS_BASE_URL = "https://canvas.ssu.ac.kr"
        const val LMS_BASE_URL = "https://lms.ssu.ac.kr"
        const val SAINT_BASE_URL = "https://saint.ssu.ac.kr"
        const val LMS_LOGIN_URL = "https://smartid.ssu.ac.kr/Symtra_sso/smln_pcs.asp"
        const val LMS_CERT_URL = "https://lms.ssu.ac.kr/xn-sso/gw-cb.php"
        val COOKIE_URLS = listOf(
            CANVAS_BASE_URL,
            LMS_BASE_URL,
            "https://smartid.ssu.ac.kr",
        )
        const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"
        const val HTML_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp," +
                "image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
    }
}
