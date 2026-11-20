package cn.lemonit.paperplane.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.Headers
import okhttp3.Response
import java.io.IOException
import java.security.cert.X509Certificate
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 已读完的响应（状态、头、体、服务器叶子证书） */
internal class RawResponse(val code: Int, val headers: Headers, val body: ByteArray, val leaf: X509Certificate? = null)

/**
 * 发出请求并在 OkHttp 的回调线程里读完响应体。协程取消时取消 Call（不会被包成 ApiException，取消照常传播）。
 */
internal suspend fun Call.awaitRaw(): RawResponse = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val r = try {
                response.use {
                    RawResponse(it.code, it.headers, it.body?.bytes() ?: ByteArray(0),
                        it.handshake?.peerCertificates?.firstOrNull() as? X509Certificate)
                }
            } catch (e: IOException) {
                cont.resumeWithException(e)
                return
            }
            cont.resume(r)
        }
    })
}

/** 发出请求，拿到响应头就返回（不读响应体，长连接用）。取消时关掉已到的响应。 */
internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (!cont.isActive) {
                response.close()
                return
            }
            cont.resume(response) { _, r, _ -> r.close() }
        }
    })
}

/**
 * 记录一次请求有没有开始发送：没开始发送就失败（连不上、TLS 失败）时可以放心换地址重试，
 * 发出去之后失败就不重试（操作可能已经执行了）。挂在 Request 的 tag 上，由 [FACTORY] 生成的监听器填写。
 */
internal class CallTrace {
    @Volatile
    var requestSent = false

    companion object {
        val FACTORY = EventListener.Factory { call ->
            val t = call.request().tag(CallTrace::class.java) ?: return@Factory EventListener.NONE
            object : EventListener() {
                override fun requestHeadersStart(call: Call) {
                    t.requestSent = true
                }
            }
        }
    }
}

/** URL 路径段百分号编码（只保留 RFC 3986 的非保留字符） */
internal fun encodeSegment(s: String): String {
    val sb = StringBuilder()
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt() and 0xff
        val ch = c.toChar()
        if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~') {
            sb.append(ch)
        } else {
            sb.append('%').append("0123456789ABCDEF"[c ushr 4]).append("0123456789ABCDEF"[c and 0x0f])
        }
    }
    return sb.toString()
}
