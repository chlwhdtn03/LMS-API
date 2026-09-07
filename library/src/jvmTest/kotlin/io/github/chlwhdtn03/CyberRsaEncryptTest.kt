package io.github.chlwhdtn03

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import javax.crypto.Cipher
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [cyberRsaEncrypt]가 표준 PKCS#1 v1.5 공개키 암호화와 호환되는지, 직접 생성한
 * RSA 키 쌍으로 암호화 -> 복호화 왕복이 원문과 일치하는지 검증합니다.
 */
class CyberRsaEncryptTest {
    @Test
    fun encryptedValueDecryptsBackToPlainTextWithMatchingPrivateKey() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
        val publicKey = keyPair.public as RSAPublicKey
        val privateKey = keyPair.private as RSAPrivateKey

        val modulusHex = publicKey.modulus.toString(16)
        val exponentHex = publicKey.publicExponent.toString(16)
        val plainText = "test-user-id"

        val encryptedHex = cyberRsaEncrypt(modulusHex, exponentHex, plainText)

        assertEquals(0, encryptedHex.length % 2, "16진 문자열 길이는 짝수여야 합니다.")

        val encryptedBytes = ByteArray(encryptedHex.length / 2) { i ->
            encryptedHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }

        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        val decrypted = cipher.doFinal(encryptedBytes).toString(Charsets.UTF_8)

        assertEquals(plainText, decrypted)
    }

    @Test
    fun encryptedHexLengthMatchesModulusByteLength() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
        val publicKey = keyPair.public as RSAPublicKey

        val modulusHex = publicKey.modulus.toString(16)
        val exponentHex = publicKey.publicExponent.toString(16)

        val encryptedHex = cyberRsaEncrypt(modulusHex, exponentHex, "abc")
        val modulusByteLength = (publicKey.modulus as BigInteger).bitLength().let { (it + 7) / 8 }

        assertEquals(modulusByteLength * 2, encryptedHex.length)
    }
}
