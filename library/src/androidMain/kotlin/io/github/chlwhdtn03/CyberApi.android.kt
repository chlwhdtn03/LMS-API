package io.github.chlwhdtn03

import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher

internal actual fun cyberRsaEncrypt(modulusHex: String, exponentHex: String, plainText: String): String {
    val modulus = BigInteger(modulusHex, 16)
    val exponent = BigInteger(exponentHex, 16)
    val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))

    val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
    cipher.init(Cipher.ENCRYPT_MODE, publicKey)
    return cipher.doFinal(plainText.toByteArray(Charsets.UTF_8)).toHexString()
}

private fun ByteArray.toHexString(): String {
    val sb = StringBuilder(size * 2)
    for (byte in this) {
        sb.append(((byte.toInt() shr 4) and 0xF).toString(16))
        sb.append((byte.toInt() and 0xF).toString(16))
    }
    return sb.toString()
}
