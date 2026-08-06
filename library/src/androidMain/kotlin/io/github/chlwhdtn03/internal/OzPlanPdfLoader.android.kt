package io.github.chlwhdtn03.internal

import android.annotation.SuppressLint
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.*
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object AndroidPlanContext {
    lateinit var applicationContext: Context
}

/** `loadPlan()`이 Activity 인자 없이 숨겨진 WebView를 만들 수 있도록 앱 Context를 보관합니다. */
class LmsApiPlanContextProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        AndroidPlanContext.applicationContext = requireNotNull(context).applicationContext
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}

internal actual suspend fun loadOzPlanPdf(
    viewerUrl: String,
    cookies: List<OzPlanCookie>,
): ByteArray = withTimeout(PLAN_LOAD_TIMEOUT) {
    suspendCancellableCoroutine { continuation ->
        val mainHandler = Handler(Looper.getMainLooper())
        var webView: WebView? = null
        var completed = false

        fun finish(result: Result<ByteArray>) {
            mainHandler.post {
                if (completed) return@post
                completed = true
                webView?.run {
                    stopLoading()
                    WebViewCompat.removeWebMessageListener(this, BRIDGE_NAME)
                    destroy()
                }
                webView = null
                if (continuation.isActive) {
                    result.fold(continuation::resume, continuation::resumeWithException)
                }
            }
        }

        continuation.invokeOnCancellation {
            mainHandler.post {
                if (!completed) {
                    completed = true
                    webView?.destroy()
                    webView = null
                }
            }
        }

        mainHandler.post {
            try {
                val context = AndroidPlanContext.applicationContext
                val created = createPlanWebView(
                    context = context,
                    viewerUrl = viewerUrl,
                    onPdf = { finish(Result.success(it)) },
                    onError = { finish(Result.failure(IllegalStateException(it))) },
                )
                webView = created
                installCookies(cookies) {
                    if (!completed) created.loadUrl(viewerUrl)
                }
            } catch (throwable: Throwable) {
                finish(Result.failure(throwable))
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createPlanWebView(
    context: Context,
    viewerUrl: String,
    onPdf: (ByteArray) -> Unit,
    onError: (String) -> Unit,
): WebView {
    check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
        "Android System WebView가 바이너리 메시지 브리지를 지원하지 않습니다. WebView를 업데이트해 주세요."
    }
    check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)) {
        "Android System WebView가 ArrayBuffer 메시지를 지원하지 않습니다. WebView를 업데이트해 주세요."
    }
    val webView = WebView(context)
    webView.measure(
        View.MeasureSpec.makeMeasureSpec(VIEWPORT_WIDTH, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(VIEWPORT_HEIGHT, View.MeasureSpec.EXACTLY),
    )
    webView.layout(0, 0, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
    webView.settings.javaScriptEnabled = true
    webView.settings.domStorageEnabled = true
    webView.settings.allowFileAccess = false
    webView.settings.allowContentAccess = false
    CookieManager.getInstance().run {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(webView, true)
    }
    WebViewCompat.addWebMessageListener(
        webView,
        BRIDGE_NAME,
        setOf(originRule(viewerUrl)),
    ) { _, message, _, _, _ ->
        when (message.type) {
            WebMessageCompat.TYPE_ARRAY_BUFFER -> onPdf(message.arrayBuffer)
            WebMessageCompat.TYPE_STRING -> onError(
                message.data ?: "OZ Viewer에서 알 수 없는 오류가 발생했습니다.",
            )
        }
    }
    webView.webChromeClient = object : WebChromeClient() {
        override fun onJsAlert(
            view: WebView?,
            url: String?,
            message: String?,
            result: JsResult?,
        ): Boolean {
            result?.confirm()
            onError(message ?: "OZ Viewer에서 알 수 없는 오류가 발생했습니다.")
            return true
        }
    }
    webView.webViewClient = object : WebViewClient() {
        private var injected = false

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            if (!injected) {
                injected = true
                view.evaluateJavascript(EXPORT_SCRIPT, null)
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            super.onReceivedError(view, request, error)
            if (request.isForMainFrame) onError(error.description.toString())
        }
    }
    return webView
}

private fun originRule(viewerUrl: String): String {
    val uri = Uri.parse(viewerUrl)
    val scheme = uri.scheme ?: return "*"
    val host = uri.host ?: return "*"
    val port = uri.port.takeIf { it >= 0 }?.let { ":$it" }.orEmpty()
    return "$scheme://$host$port"
}

private fun installCookies(cookies: List<OzPlanCookie>, onComplete: () -> Unit) {
    if (cookies.isEmpty()) {
        onComplete()
        return
    }
    val manager = CookieManager.getInstance()
    var remaining = cookies.size
    cookies.forEach { cookie ->
        val attributes = buildList {
            add("${cookie.name}=${cookie.value}")
            add("Domain=${cookie.domain}")
            add("Path=${cookie.path}")
            if (cookie.secure) {
                add("Secure")
                add("SameSite=None")
            }
            if (cookie.httpOnly) add("HttpOnly")
        }.joinToString("; ")
        manager.setCookie(cookieOrigin(cookie.domain), attributes) {
            remaining--
            if (remaining == 0) {
                manager.flush()
                onComplete()
            }
        }
    }
}

private fun cookieOrigin(domain: String): String =
    "https://${domain.trim().trimStart('.')}/"

private const val BRIDGE_NAME = "OzPlanBridge"
private const val PLAN_LOAD_TIMEOUT = 180_000L
private const val VIEWPORT_WIDTH = 1280
private const val VIEWPORT_HEIGHT = 800

private val EXPORT_SCRIPT = """
    (function() {
        if (window.__lmsApiPlanExportInstalled) return;
        window.__lmsApiPlanExportInstalled = true;
        window.OZExportMemoryStreamCallBack_OZViewer = function(outputData) {
            try {
                var result = typeof outputData === 'string' ? JSON.parse(outputData) : outputData;
                var names = Object.keys(result || {});
                if (names.length === 0) throw new Error('OZ Viewer PDF 응답이 비어 있습니다.');
                var encoded = String(result[names[0]] || '');
                var marker = encoded.indexOf('base64,');
                if (marker >= 0) encoded = encoded.substring(marker + 7);
                var padding = encoded.endsWith('==') ? 2 : (encoded.endsWith('=') ? 1 : 0);
                var bytes = new Uint8Array(Math.floor(encoded.length * 3 / 4) - padding);
                var targetOffset = 0;
                for (var sourceOffset = 0; sourceOffset < encoded.length; sourceOffset += 65536) {
                    var binary = window.atob(encoded.substring(sourceOffset, sourceOffset + 65536));
                    for (var index = 0; index < binary.length; index++) {
                        bytes[targetOffset++] = binary.charCodeAt(index);
                    }
                }
                window.OzPlanBridge.postMessage(bytes.buffer);
            } catch (error) {
                window.OzPlanBridge.postMessage(String(error));
            }
        };
        var attempts = 0;
        var timer = window.setInterval(function() {
            attempts += 1;
            try {
                var viewer = document.getElementById('OZViewer');
                if (viewer && typeof viewer.ScriptEx === 'function' &&
                    typeof viewer.GetInformation === 'function') {
                    var pages = parseInt(viewer.GetInformation('TOTAL_PAGE'), 10);
                    if (pages > 0) {
                        window.clearInterval(timer);
                        viewer.ScriptEx(
                            'save_memorystream',
                            'export.format=pdf;export.mode=silent;export.filename=plan.pdf;' +
                                'export.confirmsave=false;pdf.fontembedding=false',
                            ';'
                        );
                        return;
                    }
                }
            } catch (_) {}
            if (attempts >= 300) {
                window.clearInterval(timer);
                window.OzPlanBridge.postMessage('OZ Viewer PDF 생성 시간이 초과되었습니다.');
            }
        }, 500);
    })();
""".trimIndent()
