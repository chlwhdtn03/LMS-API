package io.github.chlwhdtn03.data.notice

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

@Serializable(with = ScholarshipNoticeSerializer::class)
data class ScholarshipNotice(
    val id: Int,
    val date: String,
    val link: String,
    val slug: String,
    val title: ScholarshipRenderedText,
    val content: ScholarshipContent,
    val attach: ScholarshipAttachment? = null,
    val attachments: List<ScholarshipAttachment> = emptyList(),
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

@Serializable
private data class ScholarshipNoticeSurrogate(
    val id: Int,
    val date: String,
    val link: String,
    val slug: String,
    val title: ScholarshipRenderedText,
    val content: ScholarshipContent,
    val attach: JsonElement? = null,
)

object ScholarshipNoticeSerializer : KSerializer<ScholarshipNotice> {
    private val attachmentListSerializer = ListSerializer(ScholarshipAttachment.serializer())

    override val descriptor = ScholarshipNoticeSurrogate.serializer().descriptor

    override fun deserialize(decoder: Decoder): ScholarshipNotice {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("ScholarshipNoticeSerializer only supports JSON")
        val surrogate = jsonDecoder.decodeSerializableValue(ScholarshipNoticeSurrogate.serializer())
        val attachments = surrogate.attach.toAttachments(jsonDecoder)

        return ScholarshipNotice(
            id = surrogate.id,
            date = surrogate.date,
            link = surrogate.link,
            slug = surrogate.slug,
            title = surrogate.title,
            content = surrogate.content,
            attach = attachments.firstOrNull(),
            attachments = attachments,
        )
    }

    override fun serialize(encoder: Encoder, value: ScholarshipNotice) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("ScholarshipNoticeSerializer only supports JSON")
        val attachments = value.attachments.ifEmpty {
            value.attach?.let(::listOf).orEmpty()
        }
        val surrogate = ScholarshipNoticeSurrogate(
            id = value.id,
            date = value.date,
            link = value.link,
            slug = value.slug,
            title = value.title,
            content = value.content,
            attach = when (attachments.size) {
                0 -> JsonNull
                1 -> jsonEncoder.json.encodeToJsonElement(ScholarshipAttachment.serializer(), attachments.first())
                else -> jsonEncoder.json.encodeToJsonElement(attachmentListSerializer, attachments)
            },
        )
        jsonEncoder.encodeSerializableValue(ScholarshipNoticeSurrogate.serializer(), surrogate)
    }

    private fun JsonElement?.toAttachments(decoder: JsonDecoder): List<ScholarshipAttachment> {
        return when (this) {
            null, JsonNull -> emptyList()
            is JsonArray -> decoder.json.decodeFromJsonElement(attachmentListSerializer, this)
            is JsonObject -> if (isEmpty()) {
                emptyList()
            } else {
                listOf(decoder.json.decodeFromJsonElement(ScholarshipAttachment.serializer(), this))
            }
            else -> emptyList()
        }
    }
}
