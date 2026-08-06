package io.github.chlwhdtn03.internal

import io.github.chlwhdtn03.data.Lms.Semester
import io.github.chlwhdtn03.decodeHtmlEntities
import io.github.chlwhdtn03.stripHtmlTags
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException

/**
 * 한 번의 Web Dynpro 조회 안에서만 사용하는 화면 세션입니다.
 *
 * 로그인 쿠키는 Ktor가 보관하지만, Web Dynpro의 secure ID와 form action은 각 화면
 * 응답에서 직접 전달해야 합니다. 이 context는 전역으로 캐시하지 않고 호출이 끝나면
 * 폐기합니다.
 */
internal data class WebDynproContext(
    val url: String,
    val appName: String,
    val html: String,
    val secureId: String,
    val formAction: String,
)

/** 버튼 이벤트로 열린 외부 창 URL과 이어서 사용할 Web Dynpro 세션입니다. */
internal data class WebDynproExternalUrls(
    val context: WebDynproContext,
    val urlsByButtonId: Map<String, String>,
)

/** SAP Web Dynpro 화면 초기화와 이벤트 요청을 공통 처리합니다. */
internal class WebDynproService(
    private val client: HttpClient,
    private val ensureLoggedIn: () -> Unit,
) {
    suspend fun openSession(url: String, appName: String): WebDynproContext {
        ensureLoggedIn()

        var lastFailure: WebDynproSessionException? = null
        repeat(SESSION_OPEN_ATTEMPTS) {
            try {
                return openSessionOnce(url, appName)
            } catch (failure: WebDynproSessionException) {
                lastFailure = failure
            }
        }
        throw lastFailure
            ?: IllegalStateException("$appName Web Dynpro 화면 세션을 초기화하지 못했습니다.")
    }

    private suspend fun openSessionOnce(url: String, appName: String): WebDynproContext {
        val response = client.get(url) {
            headers {
                append(HttpHeaders.UserAgent, USER_AGENT)
                append(HttpHeaders.Accept, HTML_ACCEPT)
            }
        }
        val initialHtml = response.bodyAsText().decodeResponse()
        validateResponse(initialHtml, appName)

        val secureId = parseSecureId(initialHtml)
        val formAction = parseFormAction(initialHtml)
        if (secureId.isBlank() || formAction.isBlank()) {
            throw WebDynproSessionException(
                "$appName Web Dynpro 화면 세션을 초기화하지 못했습니다.",
            )
        }

        val initialContext = WebDynproContext(
            url = url,
            appName = appName,
            html = initialHtml,
            secureId = secureId,
            formAction = formAction,
        )
        return refreshSession(initialContext)
    }

    suspend fun fetchHtml(url: String, appName: String): String {
        return openSession(url, appName).html
    }

    /**
     * 현재 context에 초기 화면 이벤트를 다시 보냅니다.
     *
     * SAP가 `setFocus` 같은 부분 변경만 반환하면 기존 HTML을 유지합니다. 브라우저처럼
     * DOM을 보관하지 않는 클라이언트가 부분 응답을 전체 페이지로 오인하지 않게 합니다.
     */
    suspend fun refreshSession(context: WebDynproContext): WebDynproContext {
        val responseHtml = submitRaw(
            context = context,
            eventQueue = initialEventQueue(context.url),
            extractCdata = true,
        )
        validateResponse(responseHtml, context.appName)
        return if (isRenderablePage(responseHtml)) {
            context.updatedWith(responseHtml)
        } else if (containsTable(context.html)) {
            context
        } else {
            throw WebDynproSessionException(
                "${context.appName} Web Dynpro 조회 화면을 불러오지 못했습니다.",
            )
        }
    }

    suspend fun submitEvents(
        context: WebDynproContext,
        eventQueue: String,
    ): WebDynproContext {
        ensureLoggedIn()
        val responseHtml = submitRaw(
            context = context,
            eventQueue = eventQueue,
            extractCdata = false,
        )
        validateResponse(responseHtml, context.appName)
        return context.updatedWith(responseHtml)
    }

    /**
     * 같은 화면의 버튼들을 눌러 `openExternalWindow`로 내려오는 URL을 추출합니다.
     *
     * 요청 수를 줄이기 위해 버튼을 묶어 전송하고, 응답 URL 수가 맞지 않으면 해당 묶음만
     * 개별 요청으로 다시 확인합니다. 일부 버튼 요청이 실패해도 이미 찾은 URL은 유지합니다.
     */
    suspend fun resolveExternalWindowUrls(
        initialContext: WebDynproContext,
        buttonIds: List<String>,
    ): WebDynproExternalUrls {
        var context = initialContext
        val urlsByButtonId = linkedMapOf<String, String>()
        for (batch in buttonIds.distinct().chunked(EXTERNAL_URL_BUTTON_BATCH_SIZE)) {
            val batchResponse = try {
                submitEvents(
                    context,
                    buttonEventQueue(batch),
                )
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                break
            }
            context = batchResponse
            val batchUrls = parseExternalWindowUrls(batchResponse.html)
            if (batchUrls.size == batch.size) {
                batch.zip(batchUrls).forEach { (buttonId, url) ->
                    urlsByButtonId[buttonId] = url
                }
                continue
            }

            for (buttonId in batch) {
                val response = try {
                    submitEvents(context, buttonEventQueue(listOf(buttonId)))
                } catch (throwable: Throwable) {
                    if (throwable is CancellationException) throw throwable
                    break
                }
                context = response
                parseExternalWindowUrls(response.html).firstOrNull()?.let { url ->
                    urlsByButtonId[buttonId] = url
                }
            }
        }
        return WebDynproExternalUrls(context, urlsByButtonId)
    }

    fun parseExternalWindowUrls(html: String): List<String> {
        return Regex(
            """["']url["']\s*:\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        ).findAll(html.decodeHtmlEntities()).map { match ->
            match.groupValues[1].decodeJavascriptEscapes()
        }.filter { url ->
            url.startsWith("https://") || url.startsWith("http://")
        }.toList()
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

    private fun buttonEventQueue(buttonIds: List<String>): String {
        val events = buttonIds.map { buttonId ->
            "Button_Press~E002Id~E004$buttonId~E003~E002ClientAction~E004submit" +
                "~E005ResponseData~E004delta~E003~E002~E003"
        }
        val focusId = buttonIds.last()
        val focusInfo = escape("""{"sFocussedId":"$focusId"}""")
        val formRequest =
            "Form_Request~E002Id~E004sap.client.SsrClient.form~E005Async~E004false" +
                "~E005FocusInfo~E004$focusInfo~E005Hash~E004~E005DomChanged~E004false" +
                "~E005IsDirty~E004false~E003~E002ResponseData~E004delta~E003~E002~E003"
        return (events + formRequest).joinToString("~E001")
    }

    private fun String.decodeJavascriptEscapes(): String {
        val unicodeDecoded = Regex("""\\u([0-9a-fA-F]{4})""").replace(this) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
        return Regex("""\\x([0-9a-fA-F]{2})""").replace(unicodeDecoded) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }.replace("\\/", "/")
    }

    private suspend fun submitRaw(
        context: WebDynproContext,
        eventQueue: String,
        extractCdata: Boolean,
    ): String {
        val actionUrl = if (context.formAction.startsWith("http")) {
            context.formAction
        } else {
            "$ECC_BASE_URL${context.formAction}"
        }
        val response = client.submitForm(
            url = actionUrl,
            formParameters = parameters {
                append("sap-charset", "utf-8")
                append("sap-wd-secure-id", context.secureId)
                append("fesrAppName", context.appName)
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

        val body = response.bodyAsText()
        val content = if (extractCdata) {
            Regex("""<!\[CDATA\[([\s\S]*?)]]>""")
                .findAll(body)
                .map { it.groupValues[1] }
                .maxByOrNull { it.length }
                ?: body
        } else {
            body
        }
        return content.decodeResponse()
    }

    private fun WebDynproContext.updatedWith(responseHtml: String): WebDynproContext {
        return copy(
            html = responseHtml,
            secureId = parseSecureId(responseHtml).ifBlank { secureId },
            formAction = parseFormAction(responseHtml).ifBlank { formAction },
        )
    }

    private fun parseSecureId(html: String): String {
        return Regex(
            """name="sap-wd-secure-id"\s+value="([^"]+)"""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.get(1).orEmpty()
    }

    private fun parseFormAction(html: String): String {
        return Regex(
            """<form\s+[^>]*id="sap\.client\.SsrClient\.form"[^>]*action="([^"]+)"""",
            RegexOption.IGNORE_CASE,
        ).find(html)
            ?.groupValues
            ?.get(1)
            ?.decodeHtmlEntities()
            ?.decodeHtmlEntities()
            .orEmpty()
    }

    private fun validateResponse(html: String, appName: String) {
        val title = Regex(
            """<title\b[^>]*>([\s\S]*?)</title>""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.get(1)?.stripHtmlTags().orEmpty()
        when {
            title.contains("Application Server Error", ignoreCase = true) -> {
                throw WebDynproSessionException("$appName Web Dynpro 서버 오류가 발생했습니다.")
            }

            html.contains("로그온 준비 중입니다.") ||
                html.contains("sap-system-login", ignoreCase = true) -> {
                throw IllegalStateException("$appName Web Dynpro 로그인 세션이 유효하지 않습니다.")
            }
        }
    }

    private fun isRenderablePage(html: String): Boolean {
        if (html.isBlank()) return false
        return containsTable(html) ||
            html.contains("sap-wd-secure-id", ignoreCase = true) ||
            html.contains("sap.client.SsrClient.form", ignoreCase = true)
    }

    private fun containsTable(html: String): Boolean {
        return html.contains("<table", ignoreCase = true)
    }

    private fun initialEventQueue(url: String): String {
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
        return listOf(e1, e2, e3, e4, e5).joinToString("~E001")
    }

    private fun String.decodeResponse(): String {
        if ('&' !in this) return this
        return buildString(length) {
            var index = 0
            while (index < this@decodeResponse.length) {
                if (this@decodeResponse[index] != '&') {
                    append(this@decodeResponse[index++])
                    continue
                }

                val first = this@decodeResponse.entityAt(index)
                if (first == null) {
                    append(this@decodeResponse[index++])
                    continue
                }

                if (first.value == "&") {
                    val nestedStart = first.endExclusive
                    val nestedEnd = this@decodeResponse.indexOf(';', nestedStart)
                    val nested = if (
                        nestedEnd in nestedStart until minOf(
                            this@decodeResponse.length,
                            nestedStart + MAX_ENTITY_LENGTH,
                        )
                    ) {
                        decodeEntityToken(
                            this@decodeResponse.substring(nestedStart, nestedEnd),
                        )
                    } else {
                        null
                    }
                    if (nested != null) {
                        append(nested)
                        index = nestedEnd + 1
                        continue
                    }
                }

                append(first.value)
                index = first.endExclusive
            }
        }
    }

    private fun String.entityAt(startIndex: Int): DecodedEntity? {
        val semicolon = indexOf(';', startIndex + 1)
        if (semicolon < 0 || semicolon - startIndex > MAX_ENTITY_LENGTH) return null
        val value = decodeEntityToken(substring(startIndex + 1, semicolon)) ?: return null
        return DecodedEntity(value, semicolon + 1)
    }

    private companion object {
        const val SESSION_OPEN_ATTEMPTS = 3
        const val EXTERNAL_URL_BUTTON_BATCH_SIZE = 20
        const val MAX_ENTITY_LENGTH = 16
        const val ECC_BASE_URL = "https://ecc.ssu.ac.kr:8443"
        const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"
        const val HTML_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp," +
                "image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
    }
}

private data class DecodedEntity(
    val value: String,
    val endExclusive: Int,
)

private fun decodeEntityToken(token: String): String? {
    if (token.startsWith("#")) {
        val number = token.substring(1)
        val codePoint = runCatching {
            if (number.startsWith("x", ignoreCase = true)) {
                number.substring(1).toInt(16)
            } else {
                number.toInt()
            }
        }.getOrNull() ?: return null
        return codePoint.toChar().toString()
    }
    return when (token) {
        "nbsp" -> " "
        "lt" -> "<"
        "gt" -> ">"
        "amp" -> "&"
        "quot" -> "\""
        "apos" -> "'"
        else -> null
    }
}

private class WebDynproSessionException(message: String) : IllegalStateException(message)
