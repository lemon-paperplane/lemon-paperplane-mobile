package cn.lemonit.paperplane.ui.theme

import android.content.Context
import cn.lemonit.paperplane.PaperplaneApp

/**
 * 读 APK 里的三端共用资源（构建时从主仓库 shared/ 拷到 assets/shared/，见 app/build.gradle.kts 的 syncPaperplaneAssets）。
 *
 * 不需要显式初始化：第一次有界面传进 Context 时记住应用级 Context；
 * 在那之前（如非界面代码里调 tr()）用 [PaperplaneApp.instance]。
 */
object SharedAssets {
    @Volatile
    private var appContext: Context? = null

    /** 记住应用级 Context（PaperplaneTheme 首次组合时调用）。 */
    fun attach(context: Context) {
        if (appContext == null) appContext = context.applicationContext ?: context
    }

    /** 当前可用的 Context；单元测试里没有 Application 时返回 null。 */
    fun context(): Context? = appContext ?: try {
        PaperplaneApp.instance.also { appContext = it }
    } catch (_: UninitializedPropertyAccessException) {
        null
    }

    /** 读 assets/shared/<path> 的字节；不存在或没有 Context 时返回 null。 */
    fun bytes(path: String, context: Context? = null): ByteArray? {
        val c = context ?: context() ?: return null
        return try {
            c.assets.open("shared/$path").use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }

    fun text(path: String, context: Context? = null): String? = bytes(path, context)?.toString(Charsets.UTF_8)
}
