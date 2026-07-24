package io.github.chlwhdtn03.internal

import io.github.chlwhdtn03.data.Lms.Semester
import io.github.chlwhdtn03.decodeHtmlEntities
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*

/**
 * SAP WebDynpro 세션 초기화와 이벤트 요청을 공통 처리합니다.
 *
 * 세션 캐시는 [LmsApi][io.github.chlwhdtn03.LmsApi]가 소유하며 이 클래스는 전달받은
 * 캐시를 사용하기만 합니다.
 */
internal class WebDynproService(
    private val client: HttpClient,
    private val sessionCache: MutableMap<String, Pair<String, String>>,
    private val ensureLoggedIn: () -> Unit,
) {
    suspend fun fetchHtml(url: String, appName: String): String {
        ensureLoggedIn()

        val cached = sessionCache[appName]
        if (cached != null) {
            val (secureId, formAction) = cached
            val cachedHtml = try {
                postEventQueue(url, appName, secureId, formAction)
            } catch (_: Exception) {
                null
            }
            if (cachedHtml != null && isValidResponse(cachedHtml)) {
                return cachedHtml
            }
            sessionCache.remove(appName)
        }

        val response = client.get(url) {
            headers {
                append(HttpHeaders.UserAgent, USER_AGENT)
                append(HttpHeaders.Accept, HTML_ACCEPT)
            }
        }
        val html = response.bodyAsText().decodeHtmlEntities().decodeHtmlEntities()

        val secureId = Regex(
            """name="sap-wd-secure-id"\s+value="([^"]+)"""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.get(1).orEmpty()
        val formAction = Regex(
            """<form\s+[^>]*id="sap\.client\.SsrClient\.form"[^>]*action="([^"]+)"""",
            RegexOption.IGNORE_CASE,
        ).find(html)
            ?.groupValues
            ?.get(1)
            ?.decodeHtmlEntities()
            ?.decodeHtmlEntities()
            .orEmpty()

        if (secureId.isNotBlank() && formAction.isNotBlank()) {
            sessionCache[appName] = secureId to formAction
            try {
                val eventHtml = postEventQueue(url, appName, secureId, formAction)
                if (isValidResponse(eventHtml)) {
                    val finalSecureId = Regex(
                        """name="sap-wd-secure-id"\s+value="([^"]+)"""",
                        RegexOption.IGNORE_CASE,
                    ).find(eventHtml)?.groupValues?.get(1) ?: secureId
                    val finalFormAction = Regex(
                        """<form\s+[^>]*id="sap\.client\.SsrClient\.form"[^>]*action="([^"]+)"""",
                        RegexOption.IGNORE_CASE,
                    ).find(eventHtml)
                        ?.groupValues
                        ?.get(1)
                        ?.decodeHtmlEntities()
                        ?.decodeHtmlEntities()
                        ?: formAction

                    sessionCache[appName] = finalSecureId to finalFormAction
                    return eventHtml
                }
            } catch (_: Exception) {
                // 초기 이벤트가 실패해도 최초 HTML을 파싱할 수 있으므로 그대로 반환합니다.
            }
        }

        return html
    }

    fun requireSession(appName: String, errorMessage: String): Pair<String, String> {
        return sessionCache[appName] ?: throw IllegalStateException(errorMessage)
    }

    fun updateSession(appName: String, secureId: String, formAction: String) {
        sessionCache[appName] = secureId to formAction
    }

    fun removeSession(appName: String) {
        sessionCache.remove(appName)
    }

    fun parseYear(html: String): String {
        val decodedHtml = html.decodeHtmlEntities()
        val labelRegex = Regex(
            """<label\b[^>]*for="([^"]+)"[^>]*>(?:(?!</label>)[\s\S])*?학년도""",
            RegexOption.IGNORE_CASE,
        )
        val inputValueRegex = { id: String ->
            Regex("""id="$id"[^>]*value="([^"]+)"""", RegexOption.IGNORE_CASE)
        }
        var year = ""
        labelRegex.find(decodedHtml)?.groupValues?.get(1)?.let { id ->
            year = inputValueRegex(id)
                .find(decodedHtml)
                ?.groupValues
                ?.get(1)
                ?.decodeHtmlEntities()
                .orEmpty()
        }
        return if (year.isBlank()) {
            Regex("""value="(\d{4})학년도"""", RegexOption.IGNORE_CASE)
                .find(decodedHtml)
                ?.groupValues
                ?.get(1)
                .orEmpty()
        } else {
            Regex("""\d{4}""").find(year)?.value ?: year
        }
    }

    fun parseSemester(html: String): Semester? {
        val decodedHtml = html.decodeHtmlEntities()
        val labelRegex = Regex(
            """<label\b[^>]*for="([^"]+)"[^>]*>(?:(?!</label>)[\s\S])*?학기""",
            RegexOption.IGNORE_CASE,
        )
        val inputValueRegex = { id: String ->
            Regex("""id="$id"[^>]*value="([^"]+)"""", RegexOption.IGNORE_CASE)
        }
        var semester = ""
        labelRegex.find(decodedHtml)?.groupValues?.get(1)?.let { id ->
            semester = inputValueRegex(id)
                .find(decodedHtml)
                ?.groupValues
                ?.get(1)
                ?.decodeHtmlEntities()
                .orEmpty()
        }
        if (semester.isBlank()) {
            semester = Regex("""value="([^"]*학기)"""", RegexOption.IGNORE_CASE)
                .find(decodedHtml)
                ?.groupValues
                ?.get(1)
                .orEmpty()
        }
        return Semester.fromName(semester)
    }

    fun escape(text: String): String {
        val result = StringBuilder()
        for (char in text) {
            val isSafe = char in '0'..'9' ||
                char in 'a'..'z' ||
                char in 'A'..'Z' ||
                char == '-' ||
                char == '.' ||
                char == '_'
            if (isSafe) {
                result.append(char)
                continue
            }

            result.append("~")
            result.append(char.code.toString(16).uppercase().padStart(4, '0'))
        }
        return result.toString()
    }

    private fun isValidResponse(html: String): Boolean {
        return !html.contains("로그온 준비 중입니다.") && !html.contains("sap-system-login")
    }

    private suspend fun postEventQueue(
        url: String,
        appName: String,
        secureId: String,
        formAction: String,
    ): String {
        val initialDataWd01 = "ClientWidth:1920px;ClientHeight:1000px;ScreenWidth:1920px;ScreenHeight:1080px;ScreenOrientation:landscape;ThemedTableRowHeight:33px;ThemedFormLayoutRowHeight:32px;ThemedSvgLibUrls:{\"SAPGUI-icons\":\"https://ecc.ssu.ac.kr:8443/sap/public/bc/ur/nw5/themes/~cache-20210223121230/Base/baseLib/sap_fiori_3/svg/libs/SAPGUI-icons.svg\",\"SAPWeb-icons\":\"https://ecc.ssu.ac.kr:8443/sap/public/bc/ur/nw5/themes/~cache-20210223121230/Base/baseLib/sap_fiori_3/svg/libs/SAPGUI-icons.svg\"};ThemeTags:Fiori_3,Touch;ThemeID:sap_fiori_3;SapThemeID:sap_fiori_3;DeviceType:DESKTOP"
        val e1 = "WD01_Notify~E002Id~E004WD01~E005Data~E004${escape(initialDataWd01)}~E003~E002ResponseData~E004delta~E005EnqueueCardinality~E004single~E003~E002~E003"

        val initialDataWd02 = "ThemedTableRowHeight:25px"
        val e2 = "WD02_Notify~E002Id~E004WD02~E005Data~E004${escape(initialDataWd02)}~E003~E002ResponseData~E004delta~E005EnqueueCardinality~E004single~E003~E002~E003"

        val e3 = "_loadingPlaceholder__Load~E002Id~E004_loadingPlaceholder_~E003~E002ClientAction~E004submit~E005ResponseData~E004delta~E003~E002~E003"

        val e4Params = mapOf(
            "Id" to "WD01",
            "WindowOpenerExists" to "true",
            "ClientURL" to url,
            "ClientWidth" to "1920",
            "ClientHeight" to "1000",
            "DocumentDomain" to "ssu.ac.kr",
            "IsTopWindow" to "true",
            "ParentAccessible" to "true",
        )
        val serializedClientInfo = e4Params.entries.joinToString("~E005") {
            "${it.key}~E004${escape(it.value)}"
        }
        val e4 = "Custom_ClientInfos~E002${serializedClientInfo}~E003~E002ClientAction~E004enqueue~E005ResponseData~E004delta~E003~E002~E003"

        val e5 = "Form_Request~E002FocusInfo~E004~E005Id~E004sap.client.SsrClient.form~E005Async~E004false~E005Hash~E004~E005IsDirty~E004false~E005DomChanged~E004false~E003~E002~E003~E002~E003"
        val eventQueue = listOf(e1, e2, e3, e4, e5).joinToString("~E001")
        val actionUrl = if (formAction.startsWith("http")) {
            formAction
        } else {
            "$ECC_BASE_URL$formAction"
        }

        val response = client.submitForm(
            url = actionUrl,
            formParameters = parameters {
                append("sap-charset", "utf-8")
                append("sap-wd-secure-id", secureId)
                append("fesrAppName", appName)
                append("fesrUseBeacon", "true")
                append("SAPEVENTQUEUE", eventQueue)
            },
        ) {
            headers {
                append(HttpHeaders.UserAgent, USER_AGENT)
                append(HttpHeaders.Accept, "*/*")
                append("X-Requested-With", "XMLHttpRequest")
                append(HttpHeaders.ContentType, "application/x-www-form-urlencoded; charset=UTF-8")
            }
        }

        val responseBody = response.bodyAsText()
        return if (responseBody.contains("<![CDATA[")) {
            responseBody.substringAfter("<![CDATA[").substringBefore("]]>")
        } else {
            responseBody
        }
    }

    private companion object {
        const val ECC_BASE_URL = "https://ecc.ssu.ac.kr:8443"
        const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"
        const val HTML_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp," +
                "image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
    }
}
