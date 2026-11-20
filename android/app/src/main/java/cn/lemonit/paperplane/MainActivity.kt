package cn.lemonit.paperplane

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.features.servers.AddMode
import cn.lemonit.paperplane.features.servers.AddServerRoute
import cn.lemonit.paperplane.ui.shell.AppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppRoot() }
        handleDebugExtras(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugExtras(intent)
    }

    /**
     * 调试版：预填「添加服务器」表单（真机联调时 adb 打字会被中文输入法吞掉）。用 data 链接传参（有的系统会丢掉 am start 的 extras）：
     * adb shell am start -n cn.lemonit.paperplane/.MainActivity -d "ppdebug://add?address=192.168.1.10:47290&user=tester"
     */
    private fun handleDebugExtras(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        val uri = intent?.data ?: return
        if (uri.scheme != "ppdebug" || uri.host != "add") return
        intent.data = null
        val code = uri.getQueryParameter("code")
        AppModel.nav.push(
            AddServerRoute(
                mode = if (code != null) AddMode.CODE else AddMode.PASSWORD,
                code = code,
                address = uri.getQueryParameter("address"),
                user = uri.getQueryParameter("user"),
            ),
        )
    }
}
