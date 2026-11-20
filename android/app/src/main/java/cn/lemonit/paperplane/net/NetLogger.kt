package cn.lemonit.paperplane.net

/**
 * 网络层日志（可注入）：网络层核心代码不依赖 android.*，App 里传 `NetLogger { Log.d("paperplane.net", it) }`，
 * JVM 单元测试里传 println 或不传。
 */
fun interface NetLogger {
    fun log(message: String)

    companion object {
        val NONE: NetLogger = NetLogger { }
    }
}
