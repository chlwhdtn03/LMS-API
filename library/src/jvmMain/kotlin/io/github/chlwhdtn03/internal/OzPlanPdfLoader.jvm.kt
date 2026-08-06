package io.github.chlwhdtn03.internal

internal actual suspend fun loadOzPlanPdf(
    viewerUrl: String,
    cookies: List<OzPlanCookie>,
): ByteArray {
    throw UnsupportedOperationException(
        "강의계획서 PDF 로딩은 Android와 iOS에서만 지원합니다.",
    )
}
