package cn.lemonit.paperplane.net

import android.content.res.AssetManager
import java.io.File
import java.io.FileNotFoundException

/** 本地网关 `/editor/…` 的文件来源。path 已清理过（相对路径，不含 ..）。找不到返回 null。 */
fun interface AssetSource {
    fun read(path: String): ByteArray?
}

/** APK 里的 assets/<root>/（生产用；编辑器网页在 assets/editor/） */
class AndroidAssetSource(private val assets: AssetManager, private val root: String = "editor") : AssetSource {
    override fun read(path: String): ByteArray? = try {
        assets.open("$root/$path").use { it.readBytes() }
    } catch (e: FileNotFoundException) {
        null
    } catch (e: java.io.IOException) {
        null
    }
}

/** 磁盘目录（JVM 测试用） */
class DirectoryAssetSource(dir: File) : AssetSource {
    private val root = dir.canonicalFile

    override fun read(path: String): ByteArray? {
        val f = File(root, path).canonicalFile
        if (!f.path.startsWith(root.path + File.separator) || !f.isFile) return null
        return f.readBytes()
    }
}
