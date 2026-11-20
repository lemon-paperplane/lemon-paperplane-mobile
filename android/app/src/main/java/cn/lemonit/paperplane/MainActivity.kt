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
     * 调试版：用启动参数预填「添加服务器」表单（真机联调时 adb 打字会被中文输入法吞掉）。
     * adb shell am start -n cn.lemonit.paperplane/.MainActivity --es pp.add.address 192.168.1.10:47290 --es pp.add.user tester
     */
    private fun handleDebugExtras(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        val address = intent.getStringExtra("pp.add.address")
        val code = intent.getStringExtra("pp.add.code")
        if (address == null && code == null) return
        intent.removeExtra("pp.add.address")
        intent.removeExtra("pp.add.code")
        AppModel.nav.push(
            AddServerRoute(
                mode = if (code != null) AddMode.CODE else AddMode.PASSWORD,
                code = code,
                address = address,
                user = intent.getStringExtra("pp.add.user"),
            ),
        )
    }
}
