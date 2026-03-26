package io.github.chlwhdtn03

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import java.io.StringReader
import java.security.Security
import javax.crypto.Cipher
import kotlin.io.encoding.Base64
import kotlin.use

actual fun pemToString(rawPem: String, rawPw: String): String {
    Security.addProvider(BouncyCastleProvider())

    val pem = normalizePem(rawPem)

    PEMParser(StringReader(pem)).use { parser ->
        val obj = parser.readObject()
        val converter = JcaPEMKeyConverter()

        val privateKey = when (obj) {
            is PEMKeyPair -> converter.getPrivateKey(obj.privateKeyInfo)
            is PrivateKeyInfo -> converter.getPrivateKey(obj)
            else -> error("지원하지 않는 PEM 형식: ${obj?.javaClass?.name}")
        }

        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        return cipher.doFinal(Base64.decode(rawPw)).toString(Charsets.UTF_8)
    }
}