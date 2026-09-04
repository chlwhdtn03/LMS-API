package io.github.chlwhdtn03.internal

import io.github.chlwhdtn03.cyberRsaEncrypt
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
private data class CyberPublicKey(
    val publicExponent: String,
    val modulus: String,
)

/** 사이버대학교 포털(`portal.kcu.ac`) 로그인을 담당합니다. */
internal class CyberAuthService(
    private val client: HttpClient,
) {
    suspend fun login(id: String, password: String) {
        val publicKey = client.get(PUBLIC_KEY_URL) {
            url {
                // 프론트엔드가 cache:false로 보내는 것과 동일하게 캐시 무효화용 파라미터를 붙인다.
                parameters.append("_", Random.nextLong().toString())
            }
        }.body<CyberPublicKey>()

        val encryptedId = cyberRsaEncrypt(publicKey.modulus, publicKey.publicExponent, id)
        val encryptedPassword = cyberRsaEncrypt(publicKey.modulus, publicKey.publicExponent, password)

        // 로그인 성공 시 서버가 302로 대시보드로 리다이렉트하는데, ktor-client-cio는 메서드가
        // 바뀌어야 하는 POST 응답의 3xx를 자동으로 따라가지 않는다. 그래서 Location 헤더를
        // 직접 읽어 수동으로 리다이렉트를 따라간다(각 홉의 Set-Cookie는 HttpCookies가 계속 누적한다).
        val loginResponse = client.submitForm(
            url = LOGIN_URL,
            formParameters = parameters {
                append("uid", encryptedId)
                append("upw", encryptedPassword)
                append("returnUrl", "")
                append("firebaseToken", "noToken")
                append("firebaseType", "noType")
            },
        )
        client.followRedirectsManually(loginResponse)

        // lms.kcu.ac 세션이 이 시점엔 아직 자리잡지 않아 302가 올 수 있으므로, 같은 요청을
        // 한 번은 리다이렉트를 끝까지 따라가며 워밍업 삼아 보낸다.
        val warmupResponse = client.submitForm(
            url = "$LMS_BASE_URL/atnlcSubj/list",
            formParameters = parameters {
                append("menuGrpCd", "new_SSJU")
            },
        )
        client.followRedirectsManually(warmupResponse)

        // 상태 코드만으로는 로그인 성공/실패를 판단할 수 없으므로, 로그인 이후에만 채워지는
        // lms.kcu.ac 세션 값(loginUserNo)으로 실제 로그인 여부를 검증한다. 세션이 이미
        // 자리잡았으므로 이번엔 리다이렉트를 따라가지 않고 응답을 그대로 확인한다.
        val verifyResponse = client.submitForm(
            url = "$LMS_BASE_URL/atnlcSubj/list",
            formParameters = parameters {
                append("menuGrpCd", "new_SSJU")
            },
        )
        val loginUserNo = LOGIN_USER_NO_REGEX.find(verifyResponse.bodyAsText())?.groupValues?.get(1).orEmpty()
        if (loginUserNo.isBlank()) {
            throw IllegalArgumentException("아이디 또는 비밀번호가 일치하지 않습니다.")
        }
    }

    /** 3xx 응답의 Location을 최대 [MAX_REDIRECTS]번까지 GET으로 직접 따라가 최종 응답을 반환한다. */
    private suspend fun HttpClient.followRedirectsManually(initial: HttpResponse): HttpResponse {
        var response = initial
        var hops = 0
        while (response.status.value in 300..399 && hops < MAX_REDIRECTS) {
            val location = response.headers[HttpHeaders.Location] ?: return response
            val nextUrl = URLBuilder(response.request.url).takeFrom(location).build()
            response = get(nextUrl)
            hops++
        }
        return response
    }

    private companion object {
        const val PORTAL_BASE_URL = "https://portal.kcu.ac"
        const val LMS_BASE_URL = "https://lms.kcu.ac"
        const val PUBLIC_KEY_URL = "$PORTAL_BASE_URL/publickeys"
        const val LOGIN_URL = "$PORTAL_BASE_URL/sso/login"
        const val MAX_REDIRECTS = 5
        val LOGIN_USER_NO_REGEX = Regex("""id="loginUserNo"\s+value="([^"]*)"""", RegexOption.IGNORE_CASE)
    }
}
