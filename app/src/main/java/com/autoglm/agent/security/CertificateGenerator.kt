package com.autoglm.agent.security

import android.content.Context
import android.util.Base64
import android.util.Log
import com.autoglm.agent.util.Constants
import java.io.*
import java.math.BigInteger
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.*
import javax.security.auth.x500.X500Principal

/**
 * 证书生成器 — 纯 Java/Android 内置 API，零第三方依赖
 *
 * 使用 Android 内置 KeyStore (BKS/PKCS12) 管理证书体系：
 * 1. CA 证书 — ca.p12（BKS 格式 keystore 包含自签名 CA）
 * 2. 服务器证书 — server.p12（PKCS12，NanoHTTPD 使用）
 * 3. 客户端证书 — client.p12（PKCS12，远程客户端 mTLS 使用）
 * 4. CA 证书导出 — ca.crt（PEM，给客户端验证用）
 *
 * Android KeyStore API 天然支持自签名证书链生成。
 */
class CertificateGenerator(private val context: Context) {

    companion object {
        private const val TAG = "CertificateGenerator"

        private const val KEY_SIZE = 2048
        private const val VALIDITY_DAYS = 3650L  // 10 年
        private const val SIG_ALGO = "SHA256withRSA"
        private const val CA_CN = "PhoneAgent CA"
        private const val SERVER_CN = "PhoneAgent Server"
        private const val CLIENT_CN = "PhoneAgent Client"
        private const val ORG_DN = "O=AutoGLM"

        // Keystore 密码
        private const val CA_KS_PASSWORD = "phoneagent-ca"
        private const val SERVER_KS_PASSWORD = "phoneagent-srv"
        private const val CLIENT_KS_PASSWORD = "phoneagent-cli"

        // 别名
        private const val CA_ALIAS = "ca"
        private const val SERVER_ALIAS = "server"
        private const val CLIENT_ALIAS = "client"
    }

    /**
     * 检查证书是否已生成
     */
    fun areCertificatesGenerated(): Boolean {
        val certsDir = getCertsDir()
        return File(certsDir, Constants.SERVER_CERT_FILE).exists() &&
               File(certsDir, Constants.CLIENT_P12_FILE).exists()
    }

    /**
     * 生成所有证书
     */
    fun generateAllCertificates(): Boolean {
        return try {
            val certsDir = File(getCertsDir())
            if (!certsDir.exists()) certsDir.mkdirs()

            // 1. 生成 CA
            val caKP = generateKeyPair()
            val caCert = generateSelfSignedCert(caKP, "CN=$CA_CN,$ORG_DN")

            // 保存 CA 为 BKS keystore
            saveKeyStore(
                createKeyStoreWithCert(CA_ALIAS, caKP, caCert, CA_KS_PASSWORD),
                Constants.CA_CERT_FILE,
                CA_KS_PASSWORD
            )
            // 同时保存 CA 的 PEM 证书供客户端使用
            saveCertPEM(caCert, "ca.pem")
            Log.d(TAG, "CA 证书生成成功")

            // 2. 生成服务器证书
            val serverKP = generateKeyPair()
            val serverCert = generateSignedCert(serverKP, "CN=$SERVER_CN,$ORG_DN", caCert, caKP.private)

            saveKeyStore(
                createKeyStoreWithCert(SERVER_ALIAS, serverKP, serverCert, SERVER_KS_PASSWORD),
                Constants.SERVER_CERT_FILE,
                SERVER_KS_PASSWORD
            )
            Log.d(TAG, "服务器证书生成成功")

            // 3. 生成客户端证书
            val clientKP = generateKeyPair()
            val clientCert = generateSignedCert(clientKP, "CN=$CLIENT_CN,$ORG_DN", caCert, caKP.private)

            // 客户端证书保存为 PKCS12（方便导出给 Python/其他客户端使用）
            saveKeyStore(
                createKeyStoreWithCert(CLIENT_ALIAS, clientKP, clientCert, CLIENT_KS_PASSWORD),
                Constants.CLIENT_P12_FILE,
                CLIENT_KS_PASSWORD
            )
            saveCertPEM(clientCert, Constants.CLIENT_CERT_FILE)
            Log.d(TAG, "客户端证书生成成功")

            Log.i(TAG, "所有证书生成完成")
            true
        } catch (e: Exception) {
            Log.e(TAG, "证书生成失败", e)
            false
        }
    }

    // ---- 获取器 ----

    /** 获取 CA 证书 */
    fun getCACertificate(): X509Certificate? {
        return try {
            val ks = loadKeyStore(Constants.CA_CERT_FILE, CA_KS_PASSWORD) ?: return null
            ks.getCertificate(CA_ALIAS) as? X509Certificate
        } catch (e: Exception) {
            Log.e(TAG, "读取 CA 证书失败", e)
            null
        }
    }

    /** 获取服务器 KeyStore */
    fun getServerKeyStore(): KeyStore? {
        return loadKeyStore(Constants.SERVER_CERT_FILE, SERVER_KS_PASSWORD)
    }

    /** 获取服务器证书 */
    fun getServerCertificate(): X509Certificate? {
        return try {
            val ks = getServerKeyStore() ?: return null
            ks.getCertificate(SERVER_ALIAS) as? X509Certificate
        } catch (e: Exception) {
            Log.e(TAG, "读取服务器证书失败", e)
            null
        }
    }

    /** 获取服务器私钥 */
    fun getServerPrivateKey(): PrivateKey? {
        return try {
            val ks = getServerKeyStore() ?: return null
            ks.getKey(SERVER_ALIAS, SERVER_KS_PASSWORD.toCharArray()) as? PrivateKey
        } catch (e: Exception) {
            Log.e(TAG, "读取服务器私钥失败", e)
            null
        }
    }

    /** 获取客户端 KeyStore */
    fun getClientKeyStore(): KeyStore? {
        return loadKeyStore(Constants.CLIENT_P12_FILE, CLIENT_KS_PASSWORD)
    }

    /** 导出 CA 证书 Base64 */
    fun exportCACertBase64(): String? {
        return try {
            val cert = getCACertificate() ?: return null
            certToBase64(cert)
        } catch (e: Exception) {
            Log.e(TAG, "导出 CA 证书失败", e)
            null
        }
    }

    /** 导出客户端证书 Base64 */
    fun exportClientCertBase64(): String? {
        return try {
            val file = File(getCertsDir(), Constants.CLIENT_CERT_FILE)
            if (!file.exists()) return null
            val pem = file.readText()
            pem.replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replace("\n", "").replace("\r", "")
        } catch (e: Exception) {
            Log.e(TAG, "导出客户端证书失败", e)
            null
        }
    }

    // ---- 内部方法 ----

    private fun getCertsDir(): String =
        File(context.filesDir, Constants.CERTS_DIR).absolutePath

    private fun generateKeyPair(): KeyPair {
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(KEY_SIZE, SecureRandom())
        return keyGen.generateKeyPair()
    }

    /**
     * 生成自签名 X509 证书
     * 使用 javax.security 包的标准方式
     */
    private fun generateSelfSignedCert(
        keyPair: KeyPair,
        dn: String
    ): X509Certificate {
        val now = Date()
        val expiry = Date(now.time + VALIDITY_DAYS * 24 * 3600 * 1000)

        // 使用 CertificateFactory + 自编码 DER 构建证书
        // Android 提供了简单的自签名证书生成路径
        val principal = X500Principal(dn)

        // 构建 TBSCertificate DER 编码
        val tbsBytes = buildTbsCertificate(
            serial = BigInteger.valueOf(System.currentTimeMillis()),
            subject = principal,
            issuer = principal,
            notBefore = now,
            notAfter = expiry,
            publicKey = keyPair.public
        )

        // 用 SHA256withRSA 签名
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keyPair.private)
        signer.update(tbsBytes)
        val signature = signer.sign()

        // 组装完整的 X509 证书 DER
        val certDER = assembleX509Cert(tbsBytes, signature)

        // 通过 CertificateFactory 解析
        val cf = CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(ByteArrayInputStream(certDER)) as X509Certificate
    }

    /**
     * 生成由 CA 签名的证书
     */
    private fun generateSignedCert(
        keyPair: KeyPair,
        subjectDN: String,
        caCert: X509Certificate,
        caPrivateKey: PrivateKey
    ): X509Certificate {
        val now = Date()
        val expiry = Date(now.time + VALIDITY_DAYS * 24 * 3600 * 1000)

        val tbsBytes = buildTbsCertificate(
            serial = BigInteger.valueOf(System.currentTimeMillis() + 1),
            subject = X500Principal(subjectDN),
            issuer = caCert.subjectX500Principal,
            notBefore = now,
            notAfter = expiry,
            publicKey = keyPair.public
        )

        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(caPrivateKey)
        signer.update(tbsBytes)
        val signature = signer.sign()

        val certDER = assembleX509Cert(tbsBytes, signature)

        val cf = CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(ByteArrayInputStream(certDER)) as X509Certificate
    }

    /**
     * 构建 TBSCertificate 的 DER 编码 (简化版 X.509 v3)
     * 只包含基本字段，不添加扩展
     */
    private fun buildTbsCertificate(
        serial: BigInteger,
        subject: X500Principal,
        issuer: X500Principal,
        notBefore: Date,
        notAfter: Date,
        publicKey: PublicKey
    ): ByteArray {
        // 构建 DER 编码的 TBSCertificate
        // X.509 v3 TBSCertificate 结构 (RFC 5280 Section 4.1.2):
        //   [0] { version, serial, sigAlg, issuer, validity, subject, subjectPubKey }
        val subjectBytes = subject.encoded
        val issuerBytes = issuer.encoded
        val pubKeyInfo = publicKey.encoded

        // validity: SEQUENCE { notBefore UTCTime, notAfter UTCTime }
        val notBeforeBytes = encodeUTCTime(notBefore)
        val notAfterBytes = encodeUTCTime(notAfter)
        val validity = encodeSequence(notBeforeBytes, notAfterBytes)

        // signature algorithm: SEQUENCE { oid, null }
        val sigAlg = encodeSequence(
            encodeOID("1.2.840.113549.1.1.11"),  // sha256WithRSAEncryption
            byteArrayOf(0x05, 0x00)  // NULL
        )

        // version: [0] EXPLICIT INTEGER 2 (v3)
        val version = byteArrayOf(0xA0.toByte(), 0x03, 0x02, 0x01, 0x02)

        // serialNumber
        val serialBytes = encodeInteger(serial)

        // subjectPublicKeyInfo
        val spki = encodeSequence(
            encodeSequence(
                encodeOID("1.2.840.113549.1.1.1"),  // rsaEncryption
                byteArrayOf(0x05, 0x00)  // NULL
            ),
            encodeBitString(pubKeyInfo)
        )

        // 组装 TBSCertificate
        return encodeExplicit0(version, serialBytes, sigAlg, issuerBytes, validity, subjectBytes, spki)
    }

    /**
     * 组装完整的 X.509 证书 DER (Certificate ::= SEQUENCE { tbs, sigAlg, sig })
     */
    private fun assembleX509Cert(tbsBytes: ByteArray, signature: ByteArray): ByteArray {
        val sigAlg = encodeSequence(
            encodeOID("1.2.840.113549.1.1.11"),
            byteArrayOf(0x05, 0x00)
        )
        val sigBytes = encodeBitString(signature)
        return encodeSequence(tbsBytes, sigAlg, sigBytes)
    }

    // ---- DER 编码辅助方法 ----

    private fun encodeSequence(vararg elements: ByteArray): ByteArray {
        val totalLen = elements.sumOf { it.size }
        return buildDER(0x30, totalLen, *elements)
    }

    private fun encodeExplicit0(vararg elements: ByteArray): ByteArray {
        val inner = encodeSequence(*elements)
        return byteArrayOf(0xA0.toByte(), inner.size.toByte()) + inner
    }

    private fun encodeOID(oidStr: String): ByteArray {
        val parts = oidStr.split(".").map { it.toInt() }
        val bytes = mutableListOf<Byte>()
        // first two components encoded as 40*first + second
        bytes.add((40 * parts[0] + parts[1]).toByte())
        // remaining components in base-128
        for (i in 2 until parts.size) {
            var value = parts[i].toLong()
            if (value == 0) {
                bytes.add(0)
            } else {
                val temp = mutableListOf<Byte>()
                while (value > 0) {
                    temp.add(0, (value and 0x7F).toByte())
                    value = value shr 7
                }
                for (j in 0 until temp.size - 1) {
                    temp[j] = (temp[j].toInt() or 0x80).toByte()
                }
                bytes.addAll(temp)
            }
        }
        return buildDER(0x06, bytes.size, *bytes.toByteArray())
    }

    private fun encodeInteger(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        // 需要处理前导零（如果是正数且最高位为1）
        return if (bytes[0].toInt() and 0x80 != 0 && value.signum() >= 0) {
            byteArrayOf(0x00) + bytes
        } else {
            bytes
        }.let { buildDER(0x02, it.size, *it) }
    }

    private fun encodeUTCTime(date: Date): ByteArray {
        val sdf = java.text.SimpleDateFormat("yyMMddHHmmss'Z'")
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val timeStr = sdf.format(date).toByteArray(Charsets.US_ASCII)
        return buildDER(0x17, timeStr.size, *timeStr)
    }

    private fun encodeBitString(bytes: ByteArray): ByteArray {
        // BIT STRING: tag 03, 0 unused bits at end
        val content = byteArrayOf(0x00) + bytes
        return buildDER(0x03, content.size, *content)
    }

    private fun buildDER(tag: Int, contentLen: Int, vararg content: ByteArray): ByteArray {
        val allContent = content.fold(ByteArray(0)) { acc, arr -> acc + arr }
        val lenBytes = encodeLength(allContent.size)
        return byteArrayOf(tag.toByte()) + lenBytes + allContent
    }

    private fun encodeLength(length: Int): ByteArray {
        return when {
            length < 0x80 -> byteArrayOf(length.toByte())
            length < 0x100 -> byteArrayOf(0x81.toByte(), length.toByte())
            length < 0x10000 -> byteArrayOf(0x82.toByte(), (length shr 8).toByte(), length.toByte())
            else -> byteArrayOf(0x83.toByte(), (length shr 16).toByte(), (length shr 8).toByte(), length.toByte())
        }
    }

    // ---- KeyStore I/O ----

    private fun createKeyStoreWithCert(
        alias: String,
        keyPair: KeyPair,
        cert: X509Certificate,
        password: String
    ): KeyStore {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(alias, keyPair.private, password.toCharArray(), arrayOf(cert))
        return ks
    }

    private fun saveKeyStore(ks: KeyStore, filename: String, password: String) {
        val file = File(getCertsDir(), filename)
        FileOutputStream(file).use { fos ->
            ks.store(fos, password.toCharArray())
        }
        Log.d(TAG, "KeyStore 已保存: ${file.absolutePath}")
    }

    private fun loadKeyStore(filename: String, password: String): KeyStore? {
        return try {
            val file = File(getCertsDir(), filename)
            if (!file.exists()) return null
            val ks = KeyStore.getInstance("PKCS12")
            FileInputStream(file).use { fis ->
                ks.load(fis, password.toCharArray())
            }
            ks
        } catch (e: Exception) {
            Log.e(TAG, "加载 KeyStore 失败: $filename", e)
            null
        }
    }

    private fun saveCertPEM(cert: X509Certificate, filename: String) {
        val file = File(getCertsDir(), filename)
        val base64 = Base64.encodeToString(cert.encoded, Base64.DEFAULT)
        file.writeText("-----BEGIN CERTIFICATE-----\n$base64-----END CERTIFICATE-----\n")
        Log.d(TAG, "证书 PEM 已保存: ${file.absolutePath}")
    }

    private fun certToBase64(cert: X509Certificate): String {
        return Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
    }
}
