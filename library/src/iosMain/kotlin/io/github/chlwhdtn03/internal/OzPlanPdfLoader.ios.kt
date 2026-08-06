@file:OptIn(
    kotlinx.cinterop.BetaInteropApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package io.github.chlwhdtn03.internal

import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import platform.CoreGraphics.CGRectMake
import platform.Foundation.*
import platform.WebKit.*
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal actual suspend fun loadOzPlanPdf(
    viewerUrl: String,
    cookies: List<OzPlanCookie>,
): ByteArray = withTimeout(PLAN_LOAD_TIMEOUT) {
    suspendCancellableCoroutine { continuation ->
        var loader: IosOzPlanLoader? = null
        continuation.invokeOnCancellation {
            dispatch_async(dispatch_get_main_queue()) {
                loader?.cancel()
                loader = null
            }
        }
        dispatch_async(dispatch_get_main_queue()) {
            loader = IosOzPlanLoader(
                viewerUrl = viewerUrl,
                continuation = continuation,
            ).also { created ->
                activeLoaders += created
                created.start(cookies)
            }
        }
    }
}

private class IosOzPlanLoader(
    private val viewerUrl: String,
    private val continuation: Continuation<ByteArray>,
) : NSObject(), WKScriptMessageHandlerProtocol, WKNavigationDelegateProtocol {
    private val contentController = WKUserContentController()
    private val webView: WKWebView
    private var completed = false

    init {
        contentController.addScriptMessageHandler(this, BRIDGE_NAME)
        contentController.addUserScript(
            WKUserScript(
                source = EXPORT_SCRIPT,
                injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                forMainFrameOnly = true,
            ),
        )
        val configuration = WKWebViewConfiguration().apply {
            userContentController = contentController
        }
        webView = WKWebView(
            frame = CGRectMake(0.0, 0.0, 1280.0, 800.0),
            configuration = configuration,
        ).apply {
            navigationDelegate = this@IosOzPlanLoader
        }
    }

    fun start(cookies: List<OzPlanCookie>) {
        setCookie(cookies, 0)
    }

    private fun setCookie(cookies: List<OzPlanCookie>, index: Int) {
        if (index >= cookies.size) {
            val url = NSURL.URLWithString(viewerUrl)
                ?: return finish(Result.failure(IllegalArgumentException("OZ Viewer URL이 올바르지 않습니다.")))
            webView.loadRequest(NSURLRequest.requestWithURL(url))
            return
        }
        val source = cookies[index]
        val properties = mutableMapOf<Any?, Any>(
            NSHTTPCookieName to source.name,
            NSHTTPCookieValue to source.value,
            NSHTTPCookieDomain to source.domain,
            NSHTTPCookiePath to source.path,
        )
        if (source.secure) properties[NSHTTPCookieSecure] = "TRUE"
        val cookie = NSHTTPCookie.cookieWithProperties(properties)
        if (cookie == null) {
            setCookie(cookies, index + 1)
            return
        }
        webView.configuration.websiteDataStore.httpCookieStore.setCookie(cookie) {
            setCookie(cookies, index + 1)
        }
    }

    override fun userContentController(
        userContentController: WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage,
    ) {
        val message = didReceiveScriptMessage.body as? String ?: return
        when {
            message.startsWith(PDF_PREFIX) -> runCatching {
                decodePdfCallback(message.removePrefix(PDF_PREFIX))
            }.fold(
                onSuccess = { finish(Result.success(it)) },
                onFailure = { finish(Result.failure(it)) },
            )

            message.startsWith(ERROR_PREFIX) -> finish(
                Result.failure(IllegalStateException(message.removePrefix(ERROR_PREFIX))),
            )
        }
    }

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didFailNavigation: WKNavigation?,
        withError: NSError,
    ) {
        finish(Result.failure(IllegalStateException(withError.localizedDescription)))
    }

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didFailProvisionalNavigation: WKNavigation?,
        withError: NSError,
    ) {
        finish(Result.failure(IllegalStateException(withError.localizedDescription)))
    }

    fun cancel() {
        if (completed) return
        completed = true
        cleanup()
    }

    private fun finish(result: Result<ByteArray>) {
        if (completed) return
        completed = true
        cleanup()
        result.fold(continuation::resume, continuation::resumeWithException)
    }

    private fun cleanup() {
        webView.stopLoading()
        webView.navigationDelegate = null
        contentController.removeScriptMessageHandlerForName(BRIDGE_NAME)
        activeLoaders -= this
    }
}

private fun decodePdfCallback(outputData: String): ByteArray {
    require(outputData != "{}") { "OZ Viewer가 PDF 메모리 스트림을 만들지 못했습니다." }
    val raw = Regex(""":\s*"([^"]+)"""")
        .find(outputData)
        ?.groupValues
        ?.get(1)
        ?: throw IllegalStateException("OZ Viewer PDF 응답이 비어 있습니다.")
    val encoded = if ("base64," in raw) raw.substringAfter("base64,") else raw
    val data = NSData.create(base64EncodedString = encoded, options = 0u)
        ?: throw IllegalStateException("OZ Viewer PDF Base64를 해석하지 못했습니다.")
    val bytes = ByteArray(data.length.toInt())
    if (bytes.isNotEmpty()) {
        bytes.usePinned { pinned ->
            data.getBytes(pinned.addressOf(0), data.length)
        }
    }
    return bytes
}

private const val BRIDGE_NAME = "OzPlanBridge"
private const val PDF_PREFIX = "pdf:"
private const val ERROR_PREFIX = "error:"
private const val PLAN_LOAD_TIMEOUT = 180_000L
private val activeLoaders = mutableSetOf<IosOzPlanLoader>()

private val EXPORT_SCRIPT = """
    (function() {
        if (window.__lmsApiPlanExportInstalled) return;
        window.__lmsApiPlanExportInstalled = true;
        var send = function(type, value) {
            window.webkit.messageHandlers.OzPlanBridge.postMessage(type + ':' + value);
        };
        window.alert = function(message) { send('error', String(message)); };
        window.OZExportMemoryStreamCallBack_OZViewer = function(outputData) {
            send('pdf', outputData);
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
                send('error', 'OZ Viewer PDF 생성 시간이 초과되었습니다.');
            }
        }, 500);
    })();
""".trimIndent()
