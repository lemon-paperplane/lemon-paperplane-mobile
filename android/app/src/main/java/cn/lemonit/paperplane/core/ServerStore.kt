package cn.lemonit.paperplane.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import cn.lemonit.paperplane.net.ConnectionCode
import cn.lemonit.paperplane.net.Fingerprints
import cn.lemonit.paperplane.net.ServerAddress
import cn.lemonit.paperplane.net.ServerTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 添加服务器的方式 */
@Serializable
enum class AddMethod {
    /** 粘贴连接码 */
    @SerialName("code")
    CODE,

    /** 扫连接码二维码 */
    @SerialName("scan")
    SCAN,

    /** 账号密码登录（服务器发了一条设备专用密钥） */
    @SerialName("password")
    PASSWORD,
}

/**
 * 保存的一台远程服务器（对照 Mac SavedServer；访问密钥不在这里，加密后另存，用 [ServerStore.token] 取明文）。
 */
@Serializable
data class ServerEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** 全部地址（https://主机:端口，已规范化），连接时依次（错开并行）试 */
    val urls: List<String>,
    /** 上次能连上的地址（先试它） */
    val lastUrl: String? = null,
    /** 证书指纹 "sha256:…"（trusted 时也保存，用于显示和优先匹配） */
    val fingerprint: String = "",
    /** 证书由系统信任的 CA 签发：按证书链 + 主机名校验 */
    val trusted: Boolean = false,
    /** 访问密钥编号（服务器「访问密钥 / 已登录的设备」列表里的 id） */
    val keyId: String = "",
    /** 账号密码登录时的账号（重新登录时预填） */
    val user: String = "",
    val addedVia: AddMethod = AddMethod.CODE,
    /** 添加时间（毫秒） */
    val addedAt: Long = 0,
    /** 最后一次连上的时间（毫秒） */
    val lastUsedAt: Long? = null,
    /** 服务器系统：darwin / windows / linux（system.info 校正） */
    val os: String = "",
    val hostname: String = "",
) {
    /** 显示和主地址：上次能用的，否则第一个 */
    val url: String get() = lastUrl ?: urls.firstOrNull().orEmpty()

    /** 地址显示（去掉 https://） */
    val displayAddress: String get() = ServerAddress.display(url)

    /** 主机（运行地址换成服务器地址时用；IPv6 不带方括号） */
    val host: String get() = ServerAddress.host(url)

    /** 指纹前 8 位大写 */
    val shortFingerprint: String get() = Fingerprints.short(fingerprint)

    /** 连接参数 */
    fun target(): ServerTarget = ServerTarget.of(urls, fingerprint, trusted, lastUrl)

    /** 同一台服务器：任一地址相同且指纹相同（CA 证书的不比指纹） */
    fun isSameServer(otherUrls: List<String>, otherFingerprint: String): Boolean {
        val mine = ServerAddress.normalizeAll(urls + listOfNotNull(lastUrl)).toSet()
        val overlap = ServerAddress.normalizeAll(otherUrls).any { it in mine }
        return overlap && (trusted || otherFingerprint.isBlank() || fingerprint.isBlank() || Fingerprints.same(fingerprint, otherFingerprint))
    }
}

/** 访问密钥的加密存储（生产用 [KeystoreSecretBox]，测试用 [InMemorySecretBox]） */
interface SecretBox {
    /** 加密；aad 是绑定的附加数据（服务器 id），解密时必须一样 */
    fun seal(plain: ByteArray, aad: ByteArray): ByteArray

    /** 解密；密钥丢了、数据被改过、aad 不同时抛异常 */
    fun open(sealed: ByteArray, aad: ByteArray): ByteArray
}

/** AES-256-GCM：格式 = 版本(1) + IV 长度(1) + IV + 密文和标签 */
open class AesGcmSecretBox(private val keyProvider: () -> SecretKey) : SecretBox {
    override fun seal(plain: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keyProvider()) // IV 由 Cipher（Keystore）随机生成
        c.updateAAD(aad)
        val ct = c.doFinal(plain)
        val iv = c.iv
        return byteArrayOf(1, iv.size.toByte()) + iv + ct
    }

    override fun open(sealed: ByteArray, aad: ByteArray): ByteArray {
        require(sealed.size > 2 && sealed[0] == 1.toByte()) { "unknown format" }
        val n = sealed[1].toInt() and 0xff
        require(n in 12..16 && sealed.size > 2 + n) { "bad iv" }
        val iv = sealed.copyOfRange(2, 2 + n)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return c.doFinal(sealed, 2 + n, sealed.size - 2 - n)
    }
}

/** 内存里的随机密钥（测试用；换一个实例就解不开，可模拟 Keystore 密钥丢失） */
class InMemorySecretBox(key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()) :
    AesGcmSecretBox({ key })

/** Android Keystore 里的 AES-GCM 密钥（不可导出；第一次用时生成） */
class KeystoreSecretBox(private val alias: String = "cn.lemonit.paperplane.server-tokens") : SecretBox {
    @Volatile
    private var cached: SecretKey? = null

    private fun key(): SecretKey {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val k = (ks.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
                generateKey()
            }
            cached = k
            return k
        }
    }

    private val box = AesGcmSecretBox { key() }
    override fun seal(plain: ByteArray, aad: ByteArray) = box.seal(plain, aad)
    override fun open(sealed: ByteArray, aad: ByteArray) = box.open(sealed, aad)
}

/**
 * 服务器列表与当前选中的服务器（对照 Mac ServerStore）。存在 App 私有目录的一个 JSON 文件里（原子写入），
 * 访问密钥用 [SecretBox] 加密（绑定服务器 id）后存在同一文件，不落明文。线程安全。
 */
class ServerStore(
    private val file: File,
    private val secrets: SecretBox,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    private data class Saved(
        val version: Int = 1,
        val current: String? = null,
        val servers: List<ServerEntry> = emptyList(),
        /** 服务器 id → base64(加密后的访问密钥) */
        val secrets: Map<String, String> = emptyMap(),
    )

    private val lock = Any()
    private val _servers: MutableStateFlow<List<ServerEntry>>
    private val _currentId: MutableStateFlow<String?>
    private val sealed = HashMap<String, String>()

    /** 服务器列表（按添加顺序） */
    val servers: StateFlow<List<ServerEntry>>

    /** 当前选中的服务器 id（null = 还没有 / 没选） */
    val currentId: StateFlow<String?>

    init {
        val s = load()
        sealed.putAll(s.secrets.filterKeys { id -> s.servers.any { it.id == id } })
        _servers = MutableStateFlow(s.servers)
        _currentId = MutableStateFlow(s.current?.takeIf { id -> s.servers.any { it.id == id } })
        servers = _servers.asStateFlow()
        currentId = _currentId.asStateFlow()
    }

    val current: ServerEntry? get() = _currentId.value?.let { server(it) }

    fun server(id: String): ServerEntry? = _servers.value.firstOrNull { it.id == id }

    /** 访问密钥明文；没有或解不开（Keystore 密钥丢了）时返回 null → 需要重新登录 */
    fun token(id: String): String? {
        val b64 = synchronized(lock) { sealed[id] } ?: return null
        return try {
            secrets.open(Base64.getDecoder().decode(b64), id.toByteArray()).decodeToString()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 新增或更新（同 id 替换）。token 不为 null 时加密保存（替换旧密钥）；地址会规范化。
     * 新增时 addedAt 为 0 则填当前时间。返回保存后的条目。
     */
    fun save(entry: ServerEntry, token: String? = null): ServerEntry {
        synchronized(lock) {
            var e = entry.copy(
                urls = ServerAddress.normalizeAll(entry.urls),
                lastUrl = entry.lastUrl?.let { ServerAddress.normalize(it) },
            )
            if (e.keyId.isEmpty() && token != null) e = e.copy(keyId = ConnectionCode.keyIdOf(token))
            val list = _servers.value.toMutableList()
            val i = list.indexOfFirst { it.id == e.id }
            if (i >= 0) {
                list[i] = e
            } else {
                if (e.addedAt == 0L) e = e.copy(addedAt = clock())
                list += e
            }
            if (token != null) {
                sealed[e.id] = Base64.getEncoder().encodeToString(secrets.seal(token.toByteArray(), e.id.toByteArray()))
            }
            _servers.value = list
            persist()
            return e
        }
    }

    /** 改一条（找不到返回 null） */
    fun update(id: String, transform: (ServerEntry) -> ServerEntry): ServerEntry? {
        synchronized(lock) {
            val list = _servers.value.toMutableList()
            val i = list.indexOfFirst { it.id == id }
            if (i < 0) return null
            val e = transform(list[i]).copy(id = id)
            if (e == list[i]) return e
            list[i] = e
            _servers.value = list
            persist()
            return e
        }
    }

    fun rename(id: String, name: String) {
        update(id) { it.copy(name = name.trim().ifEmpty { it.name }) }
    }

    /** 连上了：记下能用的地址和时间（RemoteClient.Listener.onActiveUrlChanged 里调） */
    fun markUsed(id: String, url: String? = null) {
        update(id) { e ->
            val n = url?.let { ServerAddress.normalize(it) }
            e.copy(
                lastUsedAt = clock(),
                lastUrl = n ?: e.lastUrl,
                urls = if (n != null && n !in e.urls) e.urls + n else e.urls,
            )
        }
    }

    /** 连上后用服务器的 system.info 校正系统与主机名 */
    fun updateInfo(id: String, os: String, hostname: String) {
        update(id) { e -> e.copy(os = os.ifEmpty { e.os }, hostname = hostname.ifEmpty { e.hostname }) }
    }

    /** 只删本机记录和加密的密钥（不动服务器上的密钥） */
    fun remove(id: String) {
        synchronized(lock) {
            _servers.value = _servers.value.filterNot { it.id == id }
            sealed.remove(id)
            if (_currentId.value == id) _currentId.value = null
            persist()
        }
    }

    fun setCurrent(id: String?) {
        synchronized(lock) {
            val v = id?.takeIf { x -> _servers.value.any { it.id == x } }
            if (_currentId.value == v) return
            _currentId.value = v
            persist()
        }
    }

    /** 已添加过的同一台服务器（地址有重合且指纹相同） */
    fun existing(urls: List<String>, fingerprint: String, excluding: String? = null): ServerEntry? =
        _servers.value.firstOrNull { it.id != excluding && it.isSameServer(urls, fingerprint) }

    // ---------------------------------------------------------------- 文件

    private fun load(): Saved {
        if (!file.isFile) return Saved()
        return try {
            json.decodeFromString(Saved.serializer(), file.readText())
        } catch (e: Exception) {
            // 坏文件留个备份，别悄悄覆盖掉
            file.renameTo(File(file.parentFile, file.name + ".bad"))
            Saved()
        }
    }

    private fun persist() {
        val s = Saved(current = _currentId.value, servers = _servers.value, secrets = HashMap(sealed))
        val text = json.encodeToString(Saved.serializer(), s)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("cannot write $file")
        }
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
            coerceInputValues = true
            prettyPrint = false
        }

        /** App 用：files/servers.json + Android Keystore */
        fun create(context: Context): ServerStore =
            ServerStore(File(context.filesDir, "servers.json"), KeystoreSecretBox())
    }
}
