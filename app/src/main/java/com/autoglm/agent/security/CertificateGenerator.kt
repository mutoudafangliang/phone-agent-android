package com.autoglm.agent.security

import android.content.Context
import android.util.Log
import com.autoglm.agent.util.Constants
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.*
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.PEMParser
import java.io.*
import java.math.BigInteger
import java.security.*
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.*
import javax.security.auth.x500.X500Principal

/**
 * 证书生成器
 * 使用 BouncyCastle 生成自签名证书
 *
 * 生成的证书体系：
 * 1. CA 证书（ca.crt）- 根证书，自己签名自己
 * 2. 服务器证书（server.crt + server.key）- 由 CA 签名，用于 HTTPS Server
 * 3. 客户端证书（client.crt + client.key + client.p12）- 由 CA 签名，用于客户端认证
 *
 * 证书有效期：10 年
 * 签名算法：SHA256withRSA
 */
class CertificateGenerator(private val context: Context) {

    companion object {
        private const val TAG = "CertificateGenerator"

        // 证书参数
        private const val KEY_SIZE = 2048
        private const val VALIDITY_DAYS = 365 * 10 // 10 年
        private const val SIG_ALGORITHM = "SHA256WithRSAEncryption"
        private const val CA_COMMON_NAME = "PhoneAgent CA"
        private const val SERVER_COMMON_NAME = "PhoneAgent Server"
        private const val CLIENT_COMMON_NAME = "PhoneAgent Client"
        private const val ORGANIZATION = "AutoGLM"
        private const val ORGANIZATIONAL_UNIT = "PhoneAgent"

        // Keystore 密码
        private const val P12_PASSWORD = "changeit123"
    }

    init {
        // 注册 BouncyCastle Provider
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
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
     * @return true 如果生成成功
     */
    fun generateAllCertificates(): Boolean {
        return try {
            val certsDir = File(getCertsDir())
            if (!certsDir.exists()) {
                certsDir.mkdirs()
            }

            // 1. 生成 CA 密钥对
            val caKeyPair = generateKeyPair()
            Log.d(TAG, "CA 密钥对生成成功")

            // 2. 生成自签名 CA 证书
            val caCert = generateSelfSignedCert(caKeyPair, CA_COMMON_NAME, null)
            Log.d(TAG, "CA 证书生成成功")

            // 3. 保存 CA 证书和密钥
            saveCertificate(caCert, Constants.CA_CERT_FILE)
            savePrivateKey(caKeyPair.private, Constants.SERVER_KEY_FILE) // CA 密钥复用

            // 4. 生成服务器证书（由 CA 签名）
            val serverKeyPair = generateKeyPair()
            val serverCert = generateSignedCert(serverKeyPair, SERVER_COMMON_NAME, caCert, caKeyPair)
            Log.d(TAG, "服务器证书生成成功")

            // 5. 保存服务器证书和密钥
            saveCertificate(serverCert, Constants.SERVER_CERT_FILE)
            savePrivateKey(serverKeyPair.private, Constants.SERVER_KEY_FILE)

            // 6. 生成客户端证书（由 CA 签名）
            val clientKeyPair = generateKeyPair()
            val clientCert = generateSignedCert(clientKeyPair, CLIENT_COMMON_NAME, caCert, caKeyPair)
            Log.d(TAG, "客户端证书生成成功")

            // 7. 保存客户端证书、密钥和 PKCS12
            saveCertificate(clientCert, Constants.CLIENT_CERT_FILE)
            savePrivateKey(clientKeyPair.private, Constants.CLIENT_KEY_FILE)
            savePKCS12(clientKeyPair, clientCert, caCert, Constants.CLIENT_P12_FILE)

            Log.i(TAG, "所有证书生成完成")
            true
        } catch (e: Exception) {
            Log.e(TAG, "证书生成失败", e)
            false
        }
    }

    /**
     * 获取证书目录
     */
    private fun getCertsDir(): String {
        return File(context.filesDir, Constants.CERTS_DIR).absolutePath
    }

    /**
     * 生成 RSA 密钥对
     */
    private fun generateKeyPair(): KeyPair {
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(KEY_SIZE, SecureRandom())
        return keyGen.generateKeyPair()
    }

    /**
     * 生成自签名证书（CA 证书使用）
     */
    private fun generateSelfSignedCert(
        keyPair: KeyPair,
        commonName: String,
        issuer: X509Certificate?
    ): X509Certificate {
        val startDate = Date()
        val calendar = Calendar.getInstance()
        calendar.time = startDate
        calendar.add(Calendar.DAY_OF_YEAR, VALIDITY_DAYS)
        val endDate = calendar.time

        val issuerName = if (issuer != null) {
            issuer.subjectX500Principal.name
        } else {
            "CN=$commonName,O=$ORGANIZATION,OU=$ORGANIZATIONAL_UNIT"
        }

        val subject = X500Name("CN=$commonName,O=$ORGANIZATION,OU=$ORGANIZATIONAL_UNIT")
        val issuerX500Name = X500Name(issuerName)

        val certBuilder = JcaX509v3CertificateBuilder(
            issuerX500Name,
            BigInteger.valueOf(System.currentTimeMillis()),
            startDate,
            endDate,
            subject,
            keyPair.public
        )

        // 添加 CA 基本约束
        certBuilder.addExtension(
            Extension.basicConstraints,
            true,
            BasicConstraints(true)
        )

        // 添加密钥用途
        certBuilder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign)
        )

        // 添加扩展密钥用途
        certBuilder.addExtension(
            Extension.extendedKeyUsage,
            false,
            ExtendedKeyUsage(arrayOf(
                KeyPurposeId.id_kp_serverAuth,
                KeyPurposeId.id_kp_clientAuth
            ))
        )

        val signer: ContentSigner = JcaContentSignerBuilder(SIG_ALGORITHM)
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(keyPair.private)

        return JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(certBuilder.build(signer))
    }

    /**
     * 生成由 CA 签名的证书（服务器/客户端证书）
     */
    private fun generateSignedCert(
        keyPair: KeyPair,
        commonName: String,
        caCert: X509Certificate,
        caKeyPair: KeyPair
    ): X509Certificate {
        val startDate = Date()
        val calendar = Calendar.getInstance()
        calendar.time = startDate
        calendar.add(Calendar.DAY_OF_YEAR, VALIDITY_DAYS)
        val endDate = calendar.time

        val subject = X500Name("CN=$commonName,O=$ORGANIZATION,OU=$ORGANIZATIONAL_UNIT")

        val certBuilder = JcaX509v3CertificateBuilder(
            caCert.subjectX500Principal,
            BigInteger.valueOf(System.currentTimeMillis()),
            startDate,
            endDate,
            subject,
            keyPair.public
        )

        // 非 CA 证书
        certBuilder.addExtension(
            Extension.basicConstraints,
            true,
            BasicConstraints(false)
        )

        // 密钥用途
        val keyUsage = KeyUsage(
            KeyUsage.digitalSignature or KeyUsage.keyEncipherment
        )
        certBuilder.addExtension(Extension.keyUsage, true, keyUsage)

        // 扩展密钥用途
        certBuilder.addExtension(
            Extension.extendedKeyUsage,
            false,
            ExtendedKeyUsage(arrayOf(
                KeyPurposeId.id_kp_serverAuth,
                KeyPurposeId.id_kp_clientAuth
            ))
        )

        // 主体备用名称
        certBuilder.addExtension(
            Extension.subjectAlternativeName,
            false,
            SubjectAlternativeName(listOf(
                GeneralName(GeneralName.iPAddress, "0.0.0.0"),
                GeneralName(GeneralName.dNSName, "localhost"),
                GeneralName(GeneralName.dNSName, "*.local")
            ))
        )

        val signer: ContentSigner = JcaContentSignerBuilder(SIG_ALGORITHM)
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(caKeyPair.private)

        return JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(certBuilder.build(signer))
    }

    /**
     * 保存证书为 PEM 格式
     */
    private fun saveCertificate(cert: X509Certificate, filename: String) {
        val file = File(getCertsDir(), filename)
        JcaPEMWriter(FileWriter(file)).use { writer ->
            writer.writeObject(cert)
        }
        Log.d(TAG, "证书已保存: ${file.absolutePath}")
    }

    /**
     * 保存私钥为 PEM 格式
     */
    private fun savePrivateKey(key: PrivateKey, filename: String) {
        val file = File(getCertsDir(), filename)
        JcaPEMWriter(FileWriter(file)).use { writer ->
            writer.writeObject(key)
        }
        file.setReadable(false, false)
        file.setReadable(true, true)
        Log.d(TAG, "私钥已保存: ${file.absolutePath}")
    }

    /**
     * 保存 PKCS12 格式的客户端证书（包含私钥）
     */
    private fun savePKCS12(
        keyPair: KeyPair,
        clientCert: X509Certificate,
        caCert: X509Certificate,
        filename: String
    ) {
        val file = File(getCertsDir(), filename)

        val keystore = KeyStore.getInstance("PKCS12")
        keystore.load(null, null)

        val chain = arrayOf(clientCert, caCert)
        keystore.setKeyEntry("client", keyPair.private, P12_PASSWORD.toCharArray(), chain)

        FileOutputStream(file).use { fos ->
            keystore.store(fos, P12_PASSWORD.toCharArray())
        }

        Log.d(TAG, "PKCS12 证书已保存: ${file.absolutePath}")
    }

    /**
     * 获取服务器证书
     */
    fun getServerCertificate(): X509Certificate? {
        return try {
            val file = File(getCertsDir(), Constants.SERVER_CERT_FILE)
            if (!file.exists()) return null

            val cf = CertificateFactory.getInstance("X.509")
            cf.generateCertificate(FileInputStream(file)) as X509Certificate
        } catch (e: Exception) {
            Log.e(TAG, "读取服务器证书失败", e)
            null
        }
    }

    /**
     * 获取服务器私钥
     */
    fun getServerPrivateKey(): PrivateKey? {
        return try {
            val file = File(getCertsDir(), Constants.SERVER_KEY_FILE)
            if (!file.exists()) return null

            val pemParser = PEMParser(FileReader(file))
            val key = pemParser.readObject()
            pemParser.close()

            val converter = JcaPEMKeyConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME)
            when (val obj = key) {
                is KeyPair -> obj.private
                is org.bouncycastle.asn1.pkcs.PrivateKeyInfo -> converter.getPrivateKey(obj)
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取服务器私钥失败", e)
            null
        }
    }

    /**
     * 获取 CA 证书
     */
    fun getCACertificate(): X509Certificate? {
        return try {
            val file = File(getCertsDir(), Constants.CA_CERT_FILE)
            if (!file.exists()) return null

            val cf = CertificateFactory.getInstance("X.509")
            cf.generateCertificate(FileInputStream(file)) as X509Certificate
        } catch (e: Exception) {
            Log.e(TAG, "读取 CA 证书失败", e)
            null
        }
    }

    /**
     * 获取客户端 PKCS12 keystore
     */
    fun getClientKeyStore(): KeyStore? {
        return try {
            val file = File(getCertsDir(), Constants.CLIENT_P12_FILE)
            if (!file.exists()) return null

            val keystore = KeyStore.getInstance("PKCS12")
            keystore.load(FileInputStream(file), P12_PASSWORD.toCharArray())
            keystore
        } catch (e: Exception) {
            Log.e(TAG, "读取客户端 keystore 失败", e)
            null
        }
    }

    /**
     * 导出客户端证书（Base64 编码）
     */
    fun exportClientCertBase64(): String? {
        return try {
            val file = File(getCertsDir(), Constants.CLIENT_CERT_FILE)
            if (!file.exists()) return null

            val pem = file.readText()
            pem.replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replace("\n", "")
                .replace("\r", "")
        } catch (e: Exception) {
            Log.e(TAG, "导出客户端证书失败", e)
            null
        }
    }

    /**
     * 导出 CA 证书（Base64 编码）
     */
    fun exportCACertBase64(): String? {
        return try {
            val file = File(getCertsDir(), Constants.CA_CERT_FILE)
            if (!file.exists()) return null

            val pem = file.readText()
            pem.replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replace("\n", "")
                .replace("\r", "")
        } catch (e: Exception) {
            Log.e(TAG, "导出 CA 证书失败", e)
            null
        }
    }
}
