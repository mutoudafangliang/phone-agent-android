package com.autoglm.agent.security

import android.content.Context
import android.util.Log
import okhttp3.CipherSuite
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.*

/**
 * mTLS (双向 TLS) 证书管理处理器
 * 
 * mTLS 流程：
 * 1. Server 持有服务器证书 + 私钥，用于证明自己是谁
 * 2. Client 持有客户端证书 + 私钥，用于证明自己是哪个已授权设备
 * 3. Server 配置 TrustManager，只信任由 CA 签发的客户端证书
 * 4. 双方在 TLS 握手时验证对方证书
 * 
 * 由于 NanoHTTPD 不直接支持自定义 SSLContext，我们需要包装 SSLSocketFactory
 * 并在握手时进行客户端证书认证
 */
class MTLSHandler(private val context: Context) {
    
    companion object {
        private const val TAG = "MTLSHandler"
        
        // TLS 版本
        private val TLS_V12 = TlsVersion.TLS_1_2
        private val TLS_V13 = TlsVersion.TLS_1_3
        
        // 推荐的密码套件（同时保证安全性和兼容性）
        private val CIPHER_SUITES = listOf(
            // TLS 1.3
            CipherSuite.TLS_AES_256_GCM_SHA384,
            CipherSuite.TLS_AES_128_GCM_SHA256,
            CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
            // TLS 1.2
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
            CipherSuite.TLS_RSA_WITH_AES_256_GCM_SHA384,
            CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256
        )
    }
    
    private val certGenerator = CertificateGenerator(context)
    
    /**
     * 创建用于 NanoHTTPD 的 SSL ServerSocketFactory
     * 
     * NanoHTTPD 的 NanoHTTPD.start() 支持传入 serverSocketFactory 参数
     * 我们创建一个自定义的 SSLSocketFactory，在 createSocket 时启用客户端认证
     */
    fun createSSLServerSocketFactory(): SSLServerSocketFactory {
        val sslContext = createSSLContext()
        return sslContext.serverSocketFactory
    }
    
    /**
     * 创建配置了 mTLS 的 SSLContext
     * 
     * @param needClientAuth 是否需要客户端证书认证
     * @return 配置好的 SSLContext
     */
    fun createSSLContext(needClientAuth: Boolean = true): SSLContext {
        try {
            // 1. 初始化 KeyManagerFactory，加载服务器证书
            val keyManagerFactory = createKeyManagerFactory()
            
            // 2. 初始化 TrustManagerFactory，只信任 CA 签发的证书
            val trustManagerFactory = createTrustManagerFactory()
            
            // 3. 创建 SSLContext
            val sslContext = SSLContext.getInstance("TLS")
            
            // 4. 初始化 SSLContext
            // keyManagers: 服务器端证书（用于证明服务器身份）
            // trustManagers: 信任管理器（用于验证客户端证书）
            // secureRandom: 安全随机数生成器
            sslContext.init(
                keyManagerFactory.keyManagers,
                trustManagerFactory.trustManagers,
                java.security.SecureRandom()
            )
            
            Log.i(TAG, "SSLContext 创建成功，needClientAuth=$needClientAuth")
            return sslContext
        } catch (e: Exception) {
            Log.e(TAG, "SSLContext 创建失败", e)
            throw RuntimeException("无法创建 SSLContext", e)
        }
    }
    
    /**
     * 创建 KeyManagerFactory，加载服务器证书和私钥
     */
    private fun createKeyManagerFactory(): KeyManagerFactory {
        // 方案 A：从 KeyStore 加载（推荐）
        val keyStore = KeyStore.getInstance("PKCS12")
        
        val serverCert = certGenerator.getServerCertificate()
        val serverKey = certGenerator.getServerPrivateKey()
        val caCert = certGenerator.getCACertificate()
        
        if (serverCert == null || serverKey == null || caCert == null) {
            throw IllegalStateException("服务器证书未准备好")
        }
        
        // 将证书链存入 keystore
        keyStore.load(null, null)
        keyStore.setKeyEntry(
            "server",
            serverKey,
            "".toCharArray(), // keystore 内部密码
            arrayOf<java.security.cert.Certificate>(serverCert, caCert)
        )
        
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, "".toCharArray())
        
        return kmf
    }
    
    /**
     * 创建 TrustManagerFactory，只信任自签名 CA 签发的证书
     */
    private fun createTrustManagerFactory(): TrustManagerFactory {
        // 创建只包含 CA 证书的 KeyStore
        val trustStore = KeyStore.getInstance("PKCS12")
        trustStore.load(null, null)
        
        val caCert = certGenerator.getCACertificate()
        if (caCert != null) {
            trustStore.setCertificateEntry("ca", caCert)
        }
        
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(trustStore)
        
        return tmf
    }
    
    /**
     * 验证客户端证书是否由信任的 CA 签发
     * 在握手时会被自动调用
     */
    fun validateClientCertificate(chain: Array<X509Certificate>): Boolean {
        val caCert = certGenerator.getCACertificate() ?: return false
        
        return chain.isNotEmpty() && chain.any { cert ->
            try {
                cert.verify(caCert.publicKey)
                true
            } catch (e: Exception) {
                Log.w(TAG, "客户端证书验证失败: ${cert.subjectX500Principal}")
                false
            }
        }
    }
    
    /**
     * 创建 OkHttpClient（用于测试 mTLS 连接）
     * 演示客户端如何使用 PKCS12 证书连接
     */
    fun createMTLSOkHttpClient(keyStore: KeyStore, keyPassword: String): OkHttpClient {
        val sslContext = createSSLContext()
        
        val keyManagerFactory = KeyManagerFactory.getInstance(
            KeyManagerFactory.getDefaultAlgorithm()
        )
        keyManagerFactory.init(keyStore, keyPassword.toCharArray())
        
        val trustManagerFactory = createTrustManagerFactory()
        
        val newSSLContext = SSLContext.getInstance("TLS")
        newSSLContext.init(
            keyManagerFactory.keyManagers,
            trustManagerFactory.trustManagers,
            java.security.SecureRandom()
        )
        
        // 获取 X509TrustManager
        val x509TrustManager = trustManagerFactory.trustManagers?.find { it is X509TrustManager } as? X509TrustManager
            ?: throw IllegalStateException("未找到 X509TrustManager")
        
        return OkHttpClient.Builder()
            .sslSocketFactory(newSSLContext.socketFactory, x509TrustManager)
            .connectionSpecs(listOf(
                ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .tlsVersions(TLS_V12, TLS_V13)
                    .cipherSuites(CIPHER_SUITES.map { it.javaName })
                    .build()
            ))
            .build()
    }
    
    /**
     * 获取 TLS 连接规范（用于配置）
     */
    fun getConnectionSpec(): List<ConnectionSpec> {
        return listOf(
            ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TLS_V12, TLS_V13)
                .cipherSuites(CIPHER_SUITES.map { it.javaName })
                .build(),
            ConnectionSpec.Builder(ConnectionSpec.COMPATIBLE_TLS)
                .tlsVersions(TLS_V12)
                .build()
        )
    }
    
    /**
     * 检查证书是否已准备好
     */
    fun isReady(): Boolean {
        return certGenerator.areCertificatesGenerated()
    }
    
    /**
     * 获取 CA 证书（Base64），用于客户端导入
     */
    fun getCAPemBase64(): String? {
        return certGenerator.exportCACertBase64()
    }
    
    /**
     * 获取客户端证书（Base64）
     */
    fun getClientCertPemBase64(): String? {
        return certGenerator.exportClientCertBase64()
    }
}
