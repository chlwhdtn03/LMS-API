package io.github.chlwhdtn03.internal

/** OZ Viewer를 구동할 플랫폼 HTTP 저장소에 전달할 최소 쿠키 정보입니다. */
internal data class OzPlanCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val secure: Boolean,
    val httpOnly: Boolean,
)

/**
 * 플랫폼의 숨겨진 HTML5 Viewer에서 보고서를 바인딩하고 PDF 메모리 스트림을 반환합니다.
 */
internal expect suspend fun loadOzPlanPdf(
    viewerUrl: String,
    cookies: List<OzPlanCookie>,
): ByteArray

internal fun requirePdf(bytes: ByteArray): ByteArray {
    require(bytes.size >= PDF_SIGNATURE.size && PDF_SIGNATURE.indices.all { bytes[it] == PDF_SIGNATURE[it] }) {
        "OZ Viewer가 올바른 PDF 데이터를 반환하지 않았습니다."
    }
    return bytes
}

private val PDF_SIGNATURE = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D)
