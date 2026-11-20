package cn.lemonit.paperplane

import android.app.Application

/** 应用入口：全局只有一个 [cn.lemonit.paperplane.core.AppModel]（当前服务器、连接、数据）。 */
class PaperplaneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        cn.lemonit.paperplane.core.AppModel.init(this)
    }

    companion object {
        lateinit var instance: PaperplaneApp
            private set
    }
}
