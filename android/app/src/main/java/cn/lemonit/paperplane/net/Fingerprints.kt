package cn.lemonit.paperplane.net

import java.security.MessageDigest
import java.security.cert.X509Certificate

/**
 * 证书指纹，与内核 `remote.Fingerprint` 同算法：证书里公钥部分（SubjectPublicKeyInfo，原始 DER）的 SHA-256，
 * 写成 "sha256:<64 位小写十六进制>"。换证书但不换私钥时不变。
 */
object Fingerprints {
    const val PREFIX = "sha256:"

    /** 叶子证书的指纹 */
    fun of(cert: X509Certificate): String = PREFIX + hex(sha256(spki(cert)))

    /**
     * 证书里原样的 SubjectPublicKeyInfo 字节（Go 的 RawSubjectPublicKeyInfo）。
     * 直接从 TBSCertificate 的 DER 里切出来，不用 publicKey.encoded（那是重新编码的结果，个别密钥类型可能和原文不同）；
     * 解析不了时才退回 publicKey.encoded。
     */
    fun spki(cert: X509Certificate): ByteArray = try {
        spkiFromTbs(cert.tbsCertificate)
    } catch (e: Exception) {
        cert.publicKey.encoded
    }

    /** 排成 16 组 4 位大写，方便人眼核对（同 `remote.FingerprintDisplay`） */
    fun display(fp: String): String {
        val h = fp.trim().removePrefixIgnoreCase(PREFIX).uppercase()
        val parts = ArrayList<String>()
        var i = 0
        while (i + 4 <= h.length) {
            parts += h.substring(i, i + 4)
            i += 4
        }
        return parts.joinToString(" ")
    }

    /** 前 8 位大写（列表里显示用） */
    fun short(fp: String): String = fp.trim().removePrefixIgnoreCase(PREFIX).take(8).uppercase()

    /** 恒定时间比较两个指纹（大小写、空格、冒号不敏感，同 `remote.SameFingerprint`）；不是 64 位时一律不相等 */
    fun same(a: String, b: String): Boolean {
        val x = normalize(a)
        val y = normalize(b)
        return x.length == 64 && MessageDigest.isEqual(x.toByteArray(), y.toByteArray())
    }

    /** 去掉前缀与分隔符后的 64 位小写十六进制 */
    fun normalize(fp: String): String =
        fp.trim().removePrefixIgnoreCase(PREFIX).lowercase().replace(" ", "").replace(":", "")

    internal fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    internal fun hex(b: ByteArray): String {
        val digits = "0123456789abcdef"
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt() and 0xff
            sb.append(digits[v ushr 4]).append(digits[v and 0x0f])
        }
        return sb.toString()
    }

    private fun String.removePrefixIgnoreCase(p: String): String =
        if (regionMatches(0, p, 0, p.length, ignoreCase = true)) substring(p.length) else this

    // ---------------------------------------------------------------- 最小 DER 解析

    private class Tlv(val tag: Int, val headerLen: Int, val length: Int) {
        val total get() = headerLen + length
    }

    private fun readTlv(b: ByteArray, off: Int): Tlv {
        require(off + 2 <= b.size) { "der: truncated" }
        val tag = b[off].toInt() and 0xff
        val first = b[off + 1].toInt() and 0xff
        if (first < 0x80) return Tlv(tag, 2, first).also { require(off + it.total <= b.size) { "der: truncated" } }
        val n = first and 0x7f
        require(n in 1..4 && off + 2 + n <= b.size) { "der: bad length" }
        var len = 0
        for (i in 0 until n) len = (len shl 8) or (b[off + 2 + i].toInt() and 0xff)
        require(len >= 0) { "der: bad length" }
        return Tlv(tag, 2 + n, len).also { require(off + it.total <= b.size) { "der: truncated" } }
    }

    /**
     * TBSCertificate ::= SEQUENCE { [0] version 可选, serialNumber, signature, issuer, validity, subject, subjectPublicKeyInfo, ... }
     */
    private fun spkiFromTbs(tbs: ByteArray): ByteArray {
        val outer = readTlv(tbs, 0)
        require(outer.tag == 0x30) { "der: tbs is not a sequence" }
        var p = outer.headerLen
        var t = readTlv(tbs, p)
        if (t.tag == 0xA0) {
            p += t.total
        }
        repeat(5) {
            t = readTlv(tbs, p)
            p += t.total
        }
        t = readTlv(tbs, p)
        require(t.tag == 0x30) { "der: spki is not a sequence" }
        return tbs.copyOfRange(p, p + t.total)
    }
}
