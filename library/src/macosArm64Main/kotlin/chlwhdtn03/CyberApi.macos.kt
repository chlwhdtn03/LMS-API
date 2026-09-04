package io.github.chlwhdtn03

import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.Security.*

@OptIn(ExperimentalForeignApi::class)
internal actual fun cyberRsaEncrypt(modulusHex: String, exponentHex: String, plainText: String): String {
    val publicKeyDer = encodeRsaPublicKey(hexToBytes(modulusHex), hexToBytes(exponentHex))
    val publicKey = createRsaPublicKey(publicKeyDer)
    val plainData = plainText.encodeToByteArray().toCfData()

    try {
        memScoped {
            val error = alloc<CFErrorRefVar>()
            val encrypted = SecKeyCreateEncryptedData(
                publicKey,
                kSecKeyAlgorithmRSAEncryptionPKCS1,
                plainData,
                error.ptr
            ) ?: error("RSA 암호화 실패")

            try {
                return encrypted.toByteArray().toHexString()
            } finally {
                CFRelease(encrypted)
            }
        }
    } finally {
        CFRelease(plainData)
        CFRelease(publicKey)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun createRsaPublicKey(derBytes: ByteArray): SecKeyRef {
    val keyData = derBytes.toCfData()
    val attributes = createKeyAttributes()

    try {
        memScoped {
            val error = alloc<CFErrorRefVar>()

            return SecKeyCreateWithData(
                keyData,
                attributes,
                error.ptr
            ) ?: error("공개키 생성 실패")
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
    CFDictionaryAddValue(attributes, kSecAttrKeyClass, kSecAttrKeyClassPublic)

    return attributes
}

/** RSA 공개키(PKCS#1) DER: `RSAPublicKey ::= SEQUENCE { modulus INTEGER, publicExponent INTEGER }` */
private fun encodeRsaPublicKey(modulus: ByteArray, exponent: ByteArray): ByteArray {
    val content = derInteger(modulus) + derInteger(exponent)
    return byteArrayOf(0x30) + derLength(content.size) + content
}

private fun derInteger(bytes: ByteArray): ByteArray {
    var content = bytes.dropWhile { it == 0.toByte() }.toByteArray()
    if (content.isEmpty()) content = byteArrayOf(0)
    if (content[0].toInt() and 0x80 != 0) {
        content = byteArrayOf(0) + content
    }
    return byteArrayOf(0x02) + derLength(content.size) + content
}

private fun derLength(length: Int): ByteArray {
    if (length < 0x80) return byteArrayOf(length.toByte())
    var value = length
    val bytes = mutableListOf<Byte>()
    while (value > 0) {
        bytes.add(0, (value and 0xFF).toByte())
        value = value shr 8
    }
    return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
}

private fun hexToBytes(hex: String): ByteArray {
    val clean = if (hex.length % 2 != 0) "0$hex" else hex
    return ByteArray(clean.length / 2) { i ->
        clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

private fun ByteArray.toHexString(): String {
    val sb = StringBuilder(size * 2)
    for (byte in this) {
        sb.append(((byte.toInt() shr 4) and 0xF).toString(16))
        sb.append((byte.toInt() and 0xF).toString(16))
    }
    return sb.toString()
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
