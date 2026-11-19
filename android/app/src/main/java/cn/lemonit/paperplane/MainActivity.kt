package cn.lemonit.paperplane

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cn.lemonit.paperplane.ui.gallery.GalleryScreen
import cn.lemonit.paperplane.ui.theme.PaperplaneTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // 暂时显示组件陈列页；正式框架（ui/shell）做好后换掉
            PaperplaneTheme { GalleryScreen() }
        }
    }
}
