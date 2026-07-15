package io.github.chlwhdtn03

import io.ktor.http.*
import kotlinx.browser.window

@JsModule("node-forge/lib/pki")
@JsNonModule
internal external object ForgePki {
    fun privateKeyFromPem(pem: String): dynamic
}

@JsModule("node-forge/lib/util")
@JsNonModule
internal external object ForgeUtil {
    fun decode64(input: String): String
    fun decodeUtf8(input: String): String
}

internal actual fun pemToString(rawPem: String, rawPw: String): String {
    val privateKey = ForgePki.privateKeyFromPem(normalizePem(rawPem))
    val encryptedBytes = ForgeUtil.decode64(rawPw)
    val decryptedBytes = privateKey.decrypt(encryptedBytes, "RSAES-PKCS1-V1_5") as String
    return ForgeUtil.decodeUtf8(decryptedBytes)
}

internal actual fun adjustUrlForProxy(urlBuilder: io.ktor.http.URLBuilder) {
    val originalHost = urlBuilder.host
    
    val segment = when {
        originalHost.contains("smartid.ssu.ac.kr") -> "proxy-smartid"
        originalHost.contains("lms.ssu.ac.kr") -> "proxy-lms"
        originalHost.contains("canvas.ssu.ac.kr") -> "proxy-canvas"
        originalHost.contains("saint.ssu.ac.kr") -> "proxy-saint"
        originalHost.contains("ecc.ssu.ac.kr") -> "proxy-ecc"
        else -> null
    }
    
    if (segment != null) {
        val loc = window.location
        urlBuilder.protocol = if (loc.protocol.startsWith("https")) io.ktor.http.URLProtocol.HTTPS else io.ktor.http.URLProtocol.HTTP
        urlBuilder.host = loc.hostname
        urlBuilder.port = loc.port.toIntOrNull() ?: if (loc.protocol.startsWith("https")) 443 else 80
        
        // pathSegments 대입 시 발생하는 동기화 이슈 방지를 위해 encodedPath 직접 변경
        urlBuilder.encodedPath = "/$segment${urlBuilder.encodedPath}"
    }
}

internal actual val proxyBaseUrl: String 
    get() = "${window.location.protocol}//${window.location.host}"
