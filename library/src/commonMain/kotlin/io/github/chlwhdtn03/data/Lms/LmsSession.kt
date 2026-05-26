package io.github.chlwhdtn03.data.Lms

import kotlinx.serialization.Serializable

@Serializable
data class LmsSessionResponse(
    val lmsSession: LmsSession = LmsSession(),
)

@Serializable
data class LmsSession(
    val cookies: List<LmsSessionCookie> = emptyList(),
)

@Serializable
data class LmsSessionCookie(
    val name: String = "",
    val value: String = "",
    val domain: String = "",
    val path: String = "",
)
