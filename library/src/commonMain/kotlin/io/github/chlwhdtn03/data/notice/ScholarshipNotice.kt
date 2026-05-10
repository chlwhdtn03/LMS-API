package io.github.chlwhdtn03.data.notice

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ScholarshipNotice(
    val id: Int,
    val date: String,
    val link: String,
    val slug: String,
    val title: ScholarshipRenderedText,
    val content: ScholarshipContent,
    val attach: ScholarshipAttachment? = null,
)

@Serializable
data class ScholarshipRenderedText(
    val rendered: String,
)

@Serializable
data class ScholarshipContent(
    val rendered: String,
    @SerialName("protected")
    val isProtected: Boolean,
)

@Serializable
data class ScholarshipAttachment(
    @SerialName("file_type")
    val fileType: String,
    val title: String,
    @SerialName("link_text")
    val linkText: String,
    val link: String,
)
