package cn.lemonit.paperplane.features.servers

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.ui.components.ButtonKind
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.PaperInfoButton
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperBrandImage
import cn.lemonit.paperplane.ui.shell.LocalEdgeToEdge
import cn.lemonit.paperplane.ui.shell.LocalPageBack
import cn.lemonit.paperplane.ui.theme.PaperTheme

/** 还没添加任何服务器：品牌图形 + 一句话 + 扫码 / 粘贴连接码 / 账号密码。添加页在这里内嵌打开（这时还没有主框架）。 */
@Composable
fun WelcomePage() {
    var adding by rememberSaveable { mutableStateOf<AddMode?>(null) }
    // 别处压进来的添加页（调试版启动参数等）
    val pushed = cn.lemonit.paperplane.core.AppModel.nav.top as? AddServerRoute
    if (pushed != null) {
        val nav = cn.lemonit.paperplane.core.AppModel.nav
        BackHandler { nav.pop() }
        CompositionLocalProvider(LocalPageBack provides { nav.pop() }, LocalEdgeToEdge provides true) {
            AddServerPage(pushed, onDone = { nav.popToRoot() })
        }
        return
    }
    val mode = adding
    if (mode != null) {
        BackHandler { adding = null }
        CompositionLocalProvider(LocalPageBack provides { adding = null }, LocalEdgeToEdge provides true) {
            AddServerPage(AddServerRoute(mode = mode), onDone = { adding = null })
        }
        return
    }
    val c = PaperTheme.colors
    val s = PaperTheme.spacing
    Column(
        Modifier
            .fillMaxSize()
            .background(c.bgContent)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = s.xxxl, vertical = s.huge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(s.xl, Alignment.CenterVertically),
    ) {
        PaperBrandImage("app-icon", height = 88.dp)
        Text(tr("app.name"), style = PaperTheme.typography.display, textAlign = TextAlign.Center)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("mobile.welcome.title"), style = PaperTheme.typography.body, color = c.textSecondary)
            PaperInfoButton(tr("mobile.welcome.info"))
        }
        Column(
            Modifier.widthIn(max = 360.dp).fillMaxWidth().padding(top = s.lg),
            verticalArrangement = Arrangement.spacedBy(s.md),
        ) {
            PaperButton(tr("server.add.scan"), onClick = { adding = AddMode.SCAN }, icon = "scan-line", kind = ButtonKind.Primary, modifier = Modifier.fillMaxWidth())
            PaperButton(tr("server.add.paste"), onClick = { adding = AddMode.CODE }, icon = "clipboard", modifier = Modifier.fillMaxWidth())
            PaperButton(tr("server.add.viaPassword"), onClick = { adding = AddMode.PASSWORD }, icon = "key-round", modifier = Modifier.fillMaxWidth())
        }
    }
}
