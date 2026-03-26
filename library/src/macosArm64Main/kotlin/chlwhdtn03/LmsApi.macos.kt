package io.github.chlwhdtn03

import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.Security.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
actual fun pemToString(rawPem: String, rawPw: String): String {
    val pem = normalizePem(rawPem)
    val privateKey = createRsaPrivateKeyFromPkcs8(pemToDerBytes(pem))
    val encryptedData = Base64.decode(rawPw).toCfData()

    try {
        memScoped {
            val error = alloc<CFErrorRefVar>()
            val decrypted = SecKeyCreateDecryptedData(
                privateKey,
                kSecKeyAlgorithmRSAEncryptionPKCS1,
                encryptedData,
                error.ptr
            ) ?: error("RSA 복호화 실패")

            try {
                return decrypted.toByteArray().decodeToString()
            } finally {
                CFRelease(decrypted)
            }
        }
    } finally {
        CFRelease(encryptedData)
        CFRelease(privateKey)
    }
}

@OptIn(ExperimentalEncodingApi::class)
private fun pemToDerBytes(pem: String): ByteArray {
    val base64Body = pem
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .filterNot { it.startsWith("-----BEGIN") || it.startsWith("-----END") }
        .joinToString("")

    return Base64.decode(base64Body)
}

@OptIn(ExperimentalForeignApi::class)
private fun createRsaPrivateKeyFromPkcs8(derBytes: ByteArray): SecKeyRef {
    val keyData = derBytes.toCfData()
    val attributes = createKeyAttributes()

    try {
        memScoped {
            val error = alloc<CFErrorRefVar>()

            return SecKeyCreateWithData(
                keyData,
                attributes,
                error.ptr
            ) ?: error("개인키 생성 실패")
        }
    } finally {
        CFRelease(attributes)
        CFRelease(keyData)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun createKeyAttributes(): CFDictionaryRef {
    val attributes = CFDictionaryCreateMutable(
        kCFAllocatorDefault,
        0,
        kCFTypeDictionaryKeyCallBacks.ptr,
        kCFTypeDictionaryValueCallBacks.ptr
    ) ?: error("키 속성 생성 실패")

    CFDictionaryAddValue(attributes, kSecAttrKeyType, kSecAttrKeyTypeRSA)
    CFDictionaryAddValue(attributes, kSecAttrKeyClass, kSecAttrKeyClassPrivate)

    return attributes
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toCfData(): CFDataRef {
    if (isEmpty()) {
        return CFDataCreate(kCFAllocatorDefault, null, 0) ?: error("CFData 생성 실패")
    }

    return usePinned { pinned ->
        CFDataCreate(
            kCFAllocatorDefault,
            pinned.addressOf(0).reinterpret(),
            size.toLong()
        ) ?: error("CFData 생성 실패")
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun CFDataRef.toByteArray(): ByteArray {
    val length = CFDataGetLength(this).toInt()
    if (length == 0) return ByteArray(0)

    val source = CFDataGetBytePtr(this) ?: return ByteArray(0)
    val bytes = ByteArray(length)
    bytes.usePinned { pinned ->
        platform.posix.memcpy(
            pinned.addressOf(0),
            source,
            length.toULong()
        )
    }
    return bytes
}
