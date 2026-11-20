package cn.lemonit.paperplane.net

import okhttp3.OkHttpClient
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/** 服务器证书指纹和保存的不一致（actual 是服务器现在的指纹）。 */
class FingerprintMismatchException(val actual: String, cause: Throwable? = null) :
    CertificateException("certificate fingerprint mismatch: $actual", cause)

/**
 * 到一台服务器的 TLS 校验方式（对照桌面网关 tlsConfig）：
 * - 固定指纹：只认叶子证书 SPKI SHA-256 等于 [fingerprint]，不看主机名（地址多是 IP），等同 SSH 的 known_hosts；
 * - trusted（证书由系统信任的 CA 签发，账号密码添加时探测得出）：按系统证书链 + 主机名校验。
 *
 * 两者都给时任一通过即可：指纹对得上的连接不看主机名（局域网 IP 也能连），对不上时再按 CA 校验
 * （CA 证书续期换了密钥后经域名仍能连上）。
 */
class TlsPolicy(
    val fingerprint: String,
    val trusted: Boolean,
    /** 系统信任库（测试时可注入自己的根证书） */
    internal val system: X509TrustManager? = null,
) {
    init {
        require(trusted || fingerprint.isNotBlank()) { "fingerprint required unless the certificate is CA-trusted" }
    }

    internal val trustManager: X509ExtendedTrustManager by lazy {
        ServerTrustManager(fingerprint, trusted, if (trusted) system ?: Tls.systemTrustManager() else null)
    }

    internal val hostnameVerifier: HostnameVerifier by lazy { ServerHostnameVerifier(fingerprint, trusted) }

    internal val socketFactory: SSLSocketFactory by lazy { Tls.socketFactory(trustManager) }

    internal fun apply(b: OkHttpClient.Builder): OkHttpClient.Builder =
        b.sslSocketFactory(socketFactory, trustManager).hostnameVerifier(hostnameVerifier)
}

internal object Tls {
    fun systemTrustManager(): X509TrustManager {
        val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        f.init(null as KeyStore?)
        return f.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    fun socketFactory(tm: X509TrustManager): SSLSocketFactory {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), null)
        return ctx.socketFactory
    }

    /** OkHttp 默认的主机名校验（公开 API 取得，避免引用 okhttp3.internal） */
    val defaultHostnameVerifier: HostnameVerifier by lazy { OkHttpClient.Builder().build().hostnameVerifier }

    fun leaf(session: SSLSession?): X509Certificate? = try {
        session?.peerCertificates?.firstOrNull() as? X509Certificate
    } catch (e: Exception) {
        null
    }
}

/** 指纹固定（+ 可选 CA 校验）的 TrustManager。 */
internal class ServerTrustManager(
    private val pin: String,
    private val trusted: Boolean,
    private val system: X509TrustManager?,
) : X509ExtendedTrustManager() {

    private inline fun check(chain: Array<out X509Certificate>?, systemCheck: (X509TrustManager) -> Unit) {
        if (chain.isNullOrEmpty()) throw CertificateException("no certificate")
        val got = Fingerprints.of(chain[0])
        if (pin.isNotBlank() && Fingerprints.same(got, pin)) return
        if (trusted && system != null) {
            try {
                systemCheck(system)
                return
            } catch (e: CertificateException) {
                if (pin.isBlank()) throw e
                throw FingerprintMismatchException(got, e)
            }
        }
        throw FingerprintMismatchException(got)
    }

    @Suppress("UNCHECKED_CAST")
    private fun arr(chain: Array<out X509Certificate>) = chain as Array<X509Certificate>

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        check(chain) { it.checkServerTrusted(arr(chain!!), authType) }

    // Android 的系统信任库（网络安全配置）要求用带主机信息的重载，所以优先转调 socket / engine 版本
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
        check(chain) {
            if (it is X509ExtendedTrustManager) it.checkServerTrusted(arr(chain!!), authType, socket)
            else it.checkServerTrusted(arr(chain!!), authType)
        }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
        check(chain) {
            if (it is X509ExtendedTrustManager) it.checkServerTrusted(arr(chain!!), authType, engine)
            else it.checkServerTrusted(arr(chain!!), authType)
        }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("client certificates are not accepted")

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
        throw CertificateException("client certificates are not accepted")

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
        throw CertificateException("client certificates are not accepted")

    override fun getAcceptedIssuers(): Array<X509Certificate> = system?.acceptedIssuers ?: emptyArray()
}

/** 指纹对得上就不看主机名；否则只有 trusted 时按 OkHttp 默认规则校验主机名。 */
internal class ServerHostnameVerifier(
    private val pin: String,
    private val trusted: Boolean,
    private val delegate: HostnameVerifier = Tls.defaultHostnameVerifier,
) : HostnameVerifier {
    override fun verify(hostname: String?, session: SSLSession?): Boolean {
        val leaf = Tls.leaf(session) ?: return false
        if (pin.isNotBlank() && Fingerprints.same(Fingerprints.of(leaf), pin)) return true
        return trusted && delegate.verify(hostname, session)
    }
}

/**
 * 记录型 TrustManager：第一次用账号密码连接时还不知道指纹，先接受任何证书并记下叶子证书，
 * 把指纹交给界面让用户核对（先信任后固定）。只用于 hello 这种不带凭据的请求，连接不复用给其他请求。
 */
class RecordingTrustManager : X509ExtendedTrustManager() {
    private val last = AtomicReference<X509Certificate?>(null)

    /** 最近一次握手看到的叶子证书 */
    val leaf: X509Certificate? get() = last.get()

    private fun record(chain: Array<out X509Certificate>?) {
        if (chain.isNullOrEmpty()) throw CertificateException("no certificate")
        last.set(chain[0])
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = record(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = record(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = record(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("client certificates are not accepted")
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
        throw CertificateException("client certificates are not accepted")
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
        throw CertificateException("client certificates are not accepted")
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
