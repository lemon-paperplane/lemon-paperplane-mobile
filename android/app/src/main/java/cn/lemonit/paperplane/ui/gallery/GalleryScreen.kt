package cn.lemonit.paperplane.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.ui.components.ButtonKind
import cn.lemonit.paperplane.ui.components.ButtonSize
import cn.lemonit.paperplane.ui.components.Dialogs
import cn.lemonit.paperplane.ui.components.MenuEntry
import cn.lemonit.paperplane.ui.components.PaperAlert
import cn.lemonit.paperplane.ui.components.PaperBadge
import cn.lemonit.paperplane.ui.components.PaperBottomSheet
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.PaperCard
import cn.lemonit.paperplane.ui.components.PaperCheckbox
import cn.lemonit.paperplane.ui.components.PaperCodeText
import cn.lemonit.paperplane.ui.components.PaperCountBadge
import cn.lemonit.paperplane.ui.components.PaperDialogHost
import cn.lemonit.paperplane.ui.components.PaperDivider
import cn.lemonit.paperplane.ui.components.PaperEmptyState
import cn.lemonit.paperplane.ui.components.PaperGroup
import cn.lemonit.paperplane.ui.components.PaperGroupDefaults
import cn.lemonit.paperplane.ui.components.PaperIconButton
import cn.lemonit.paperplane.ui.components.PaperInfoBubble
import cn.lemonit.paperplane.ui.components.PaperInfoButton
import cn.lemonit.paperplane.ui.components.PaperLink
import cn.lemonit.paperplane.ui.components.PaperListRow
import cn.lemonit.paperplane.ui.components.PaperLoadingPage
import cn.lemonit.paperplane.ui.components.PaperMenuPanel
import cn.lemonit.paperplane.ui.components.PaperMoreButton
import cn.lemonit.paperplane.ui.components.PaperProgressBar
import cn.lemonit.paperplane.ui.components.PaperRadio
import cn.lemonit.paperplane.ui.components.PaperSearchField
import cn.lemonit.paperplane.ui.components.PaperSectionHeader
import cn.lemonit.paperplane.ui.components.PaperSegmented
import cn.lemonit.paperplane.ui.components.PaperSelect
import cn.lemonit.paperplane.ui.components.PaperSelectSheet
import cn.lemonit.paperplane.ui.components.PaperSheetPreview
import cn.lemonit.paperplane.ui.components.PaperSpinner
import cn.lemonit.paperplane.ui.components.PaperStatusDot
import cn.lemonit.paperplane.ui.components.PaperSwitch
import cn.lemonit.paperplane.ui.components.PaperTextArea
import cn.lemonit.paperplane.ui.components.PaperTextField
import cn.lemonit.paperplane.ui.components.PaperToastHost
import cn.lemonit.paperplane.ui.components.PaperToastView
import cn.lemonit.paperplane.ui.components.PaperToggleRow
import cn.lemonit.paperplane.ui.components.SelectOption
import cn.lemonit.paperplane.ui.components.SelectRow
import cn.lemonit.paperplane.ui.components.ToastKind
import cn.lemonit.paperplane.ui.components.Toasts
import cn.lemonit.paperplane.ui.components.previewToast
import cn.lemonit.paperplane.ui.i18n.I18n
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperBrandImage
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.PaperplaneTheme
import cn.lemonit.paperplane.ui.theme.RunStates
import cn.lemonit.paperplane.ui.theme.SharedAssets
import cn.lemonit.paperplane.ui.theme.ThemeMode
import cn.lemonit.paperplane.ui.theme.Tone
import kotlinx.coroutines.delay

/** 陈列页的分区（截图测试按页分组渲染） */
enum class GallerySection {
    Header, Colors, Typography, Buttons, Badges, Cards, Groups, Inputs, Choices, Sheets, Toasts, Menus, Feedback, Alerts, Code, Icons,
}

/**
 * 组件陈列页：把 ui/components 里的所有组件按类别摆出来，顶部可切深浅色和语言，方便目测。
 * 调试用，正式框架做好后从 MainActivity 拿掉。
 */
@Composable
fun GalleryScreen(modifier: Modifier = Modifier) {
    var mode by remember { mutableStateOf<ThemeMode?>(null) }
    val body: @Composable () -> Unit = {
        Box(modifier.fillMaxSize().background(PaperTheme.colors.bgChrome)) {
            val insets = WindowInsets.safeDrawing.asPaddingValues()
            LazyColumn(
                contentPadding = insets,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(GallerySection.entries) { s ->
                    Box(Modifier.padding(horizontal = PaperTheme.spacing.xl, vertical = PaperTheme.spacing.md)) {
                        if (s == GallerySection.Header) {
                            GalleryHeader(mode) { mode = it }
                        } else {
                            GallerySectionContent(s)
                        }
                    }
                }
            }
            PaperToastHost(Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
            PaperDialogHost()
        }
    }
    if (mode == null) body() else PaperplaneTheme(mode = mode, content = body)
}

/** 一组分区竖排（截图测试用） */
@Composable
fun GalleryPage(sections: List<GallerySection>, modifier: Modifier = Modifier) {
    Column(
        modifier.background(PaperTheme.colors.bgChrome).padding(PaperTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.xxl),
    ) {
        for (s in sections) {
            if (s == GallerySection.Header) GalleryHeader(null) {} else GallerySectionContent(s)
        }
    }
}

/** 陈列页的说明文字按当前语言给中英文（陈列页是调试页，不进文案表） */
@Composable
private fun g(zh: String, en: String): String = if (I18n.localeState.value == I18n.ZH) zh else en

@Composable
private fun GalleryHeader(mode: ThemeMode?, onMode: (ThemeMode?) -> Unit) {
    val lang by I18n.setting.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.lg)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.md)) {
            PaperBrandImage("app-icon", height = 40.dp)
            Column(Modifier.weight(1f)) {
                Text(tr("app.name"), style = PaperTheme.typography.display)
                Text(g("组件陈列", "Component gallery"), style = PaperTheme.typography.small, color = PaperTheme.colors.textSecondary)
            }
        }
        PaperSegmented(
            options = listOf<Pair<ThemeMode?, String>>(
                null to tr("settings.followSystem"),
                ThemeMode.Light to tr("settings.light"),
                ThemeMode.Dark to tr("settings.dark"),
            ),
            selected = mode,
            onSelect = onMode,
            fill = true,
            modifier = Modifier.fillMaxWidth(),
        )
        PaperSegmented(
            options = listOf(I18n.SYSTEM to tr("settings.followSystem"), I18n.ZH to "中文", I18n.EN to "English"),
            selected = lang,
            onSelect = { I18n.setSetting(it) },
            fill = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun GallerySectionContent(section: GallerySection) {
    when (section) {
        GallerySection.Header -> GalleryHeader(null) {}
        GallerySection.Colors -> ColorsSection()
        GallerySection.Typography -> TypographySection()
        GallerySection.Buttons -> ButtonsSection()
        GallerySection.Badges -> BadgesSection()
        GallerySection.Cards -> CardsSection()
        GallerySection.Groups -> GroupsSection()
        GallerySection.Inputs -> InputsSection()
        GallerySection.Choices -> ChoicesSection()
        GallerySection.Sheets -> SheetsSection()
        GallerySection.Toasts -> ToastsSection()
        GallerySection.Menus -> MenusSection()
        GallerySection.Feedback -> FeedbackSection()
        GallerySection.Alerts -> AlertsSection()
        GallerySection.Code -> CodeSection()
        GallerySection.Icons -> IconsSection()
    }
}

@Composable
private fun Section(title: String, info: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.lg)) {
        PaperSectionHeader(title, info = info)
        content()
    }
}

/** 白底面板（放一组控件） */
@Composable
private fun Panel(content: @Composable () -> Unit) {
    PaperCard(Modifier.fillMaxWidth()) { content() }
}

// ---------------------------------------------------------------------------
// 颜色

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorsSection() {
    Section(g("颜色", "Colors"), info = g("取自 shared/design-tokens.json，深浅两套。下面左右是同一组组件在浅色、深色下的对照。", "From shared/design-tokens.json. Below: the same components in light and dark.")) {
        Row(horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.md)) {
            for (m in listOf(ThemeMode.Light, ThemeMode.Dark)) {
                Box(Modifier.weight(1f)) {
                    PaperplaneTheme(mode = m, systemBars = false) { ThemeSample() }
                }
            }
        }
        val c = PaperTheme.colors
        val swatches = listOf(
            "bgChrome" to c.bgChrome, "bgSurface" to c.bgSurface, "bgSubtle" to c.bgSubtle, "bgSelected" to c.bgSelected,
            "border" to c.border, "borderStrong" to c.borderStrong, "textPrimary" to c.textPrimary, "textSecondary" to c.textSecondary,
            "textTertiary" to c.textTertiary, "iconPrimary" to c.iconPrimary, "accent" to c.accent, "accentSubtle" to c.accentSubtle,
            "success" to c.success, "warning" to c.warning, "danger" to c.danger, "info" to c.info,
            "statusRunning" to c.statusRunning, "statusStarting" to c.statusStarting, "statusFailed" to c.statusFailed, "statusIdle" to c.statusIdle,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm)) {
            for ((name, color) in swatches) Swatch(name, color)
        }
    }
}

@Composable
private fun Swatch(name: String, color: Color) {
    val shape = RoundedCornerShape(PaperTheme.radius.md)
    Column(Modifier.width(76.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .background(color, shape)
                .border(PaperTheme.sizes.border, PaperTheme.colors.border, shape),
        )
        Text(name, style = PaperTheme.typography.caption, color = PaperTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ThemeSample() {
    val c = PaperTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.bgChrome, RoundedCornerShape(PaperTheme.radius.lg))
            .padding(PaperTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm),
    ) {
        PaperCard(Modifier.fillMaxWidth(), padding = PaperTheme.spacing.md) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm)) {
                PaperStatusDot(RunStates.RUNNING)
                Text("web-app", style = PaperTheme.typography.title, maxLines = 1)
            }
            Text("~/Code/web-app", style = PaperTheme.typography.small, color = c.textTertiary, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.xs)) {
                PaperBadge("Vite")
                PaperBadge("3", tone = Tone.Warning)
            }
        }
        PaperButton(tr("action.run"), onClick = {}, kind = ButtonKind.Primary, size = ButtonSize.Small, icon = "play", modifier = Modifier.fillMaxWidth())
        PaperButton(tr("action.stop"), onClick = {}, size = ButtonSize.Small, icon = "square", modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            PaperSwitch(true, onCheckedChange = {})
            Spacer(Modifier.width(PaperTheme.spacing.sm))
            PaperSwitch(false, onCheckedChange = {})
        }
    }
}

// ---------------------------------------------------------------------------
// 字号

@Composable
private fun TypographySection() {
    val t = PaperTheme.typography
    Section(g("字号", "Typography")) {
        Panel {
            for ((name, style) in listOf(
                "display" to t.display, "heading" to t.heading, "title" to t.title, "bodyStrong" to t.bodyStrong,
                "body" to t.body, "label" to t.label, "small" to t.small, "caption" to t.caption, "mono" to t.mono, "monoSmall" to t.monoSmall,
            )) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = t.caption, color = PaperTheme.colors.textTertiary, modifier = Modifier.width(84.dp))
                    Text(
                        if (name.startsWith("mono")) "npm run dev --port 5173" else g("柠檬纸飞机 Paperplane", "Lemon Paperplane"),
                        style = style,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 按钮

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ButtonsSection() {
    val sp = PaperTheme.spacing
    Section(g("按钮", "Buttons")) {
        Panel {
            for (size in ButtonSize.entries) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(sp.sm), verticalArrangement = Arrangement.spacedBy(sp.sm)) {
                    PaperButton(tr("action.run"), onClick = {}, kind = ButtonKind.Primary, size = size, icon = "play")
                    PaperButton(tr("action.restart"), onClick = {}, size = size, icon = "rotate-cw")
                    PaperButton(tr("common.more"), onClick = {}, kind = ButtonKind.Text, size = size)
                    PaperButton(tr("common.delete"), onClick = {}, kind = ButtonKind.Danger, size = size, icon = "trash-2")
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(sp.sm), verticalArrangement = Arrangement.spacedBy(sp.sm)) {
                PaperButton(tr("common.save"), onClick = {}, kind = ButtonKind.Primary, loading = true)
                PaperButton(tr("common.save"), onClick = {}, kind = ButtonKind.Primary, enabled = false)
                PaperButton(tr("common.cancel"), onClick = {}, enabled = false)
            }
            PaperButton(tr("common.continue"), onClick = {}, kind = ButtonKind.Primary, modifier = Modifier.fillMaxWidth())
            PaperDivider(subtle = true)
            Row(horizontalArrangement = Arrangement.spacedBy(sp.xs), verticalAlignment = Alignment.CenterVertically) {
                PaperIconButton("play", tr("action.run"), onClick = {}, tint = PaperTheme.colors.success)
                PaperIconButton("rotate-cw", tr("action.restart"), onClick = {})
                PaperIconButton("square", tr("action.stop"), onClick = {}, tint = PaperTheme.colors.danger)
                PaperIconButton("wrap-text", tr("console.wrap"), onClick = {}, active = true)
                PaperIconButton("search", tr("common.search"), onClick = {}, enabled = false)
                PaperIconButton("refresh-cw", tr("common.loading"), onClick = {}, loading = true)
                PaperMoreButton({ sampleMenu() })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(sp.xs), verticalAlignment = Alignment.CenterVertically) {
                PaperIconButton("play", tr("action.run"), onClick = {}, size = ButtonSize.Small, tint = PaperTheme.colors.success)
                PaperIconButton("rotate-cw", tr("action.restart"), onClick = {}, size = ButtonSize.Small)
                PaperIconButton("square", tr("action.stop"), onClick = {}, size = ButtonSize.Small, tint = PaperTheme.colors.danger)
                PaperMoreButton({ sampleMenu() }, size = ButtonSize.Small)
                Spacer(Modifier.width(sp.md))
                PaperLink("http://localhost:5173", onClick = {}, mono = true, icon = "external-link")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 徽标、状态

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BadgesSection() {
    val sp = PaperTheme.spacing
    Section(g("徽标与状态", "Badges & status")) {
        Panel {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(sp.xs), verticalArrangement = Arrangement.spacedBy(sp.xs)) {
                PaperBadge("TypeScript")
                PaperBadge("Node 22", icon = "box")
                PaperBadge(tr("deps.ok"), tone = Tone.Success)
                PaperBadge(tr("deps.outdated"), tone = Tone.Warning)
                PaperBadge(tr("deps.missing"), tone = Tone.Danger)
                PaperBadge("v1.4.0", tone = Tone.Info)
                PaperBadge("main", icon = "git-branch", mono = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(sp.sm), verticalAlignment = Alignment.CenterVertically) {
                PaperCountBadge(3)
                PaperCountBadge(12, tone = Tone.Info)
                PaperCountBadge(2, tone = Tone.Danger)
                PaperCountBadge(128, tone = Tone.Success)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(sp.lg), verticalArrangement = Arrangement.spacedBy(sp.sm)) {
                for (s in listOf(RunStates.RUNNING, RunStates.PARTIAL, RunStates.STARTING, RunStates.PREPARING, RunStates.STOPPING, RunStates.FAILED, RunStates.EXITED, RunStates.IDLE)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(sp.sm)) {
                        PaperStatusDot(s)
                        Text(RunStates.title(s), style = PaperTheme.typography.small, color = PaperTheme.colors.textSecondary)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 卡片

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardsSection() {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    Section(g("卡片", "Cards"), info = g("工程卡片的样子：点开、长按出菜单，右上角「更多」同一份菜单。", "Project card: tap to open, long-press or ··· for the menu.")) {
        PaperCard(Modifier.fillMaxWidth(), onClick = {}, menu = { sampleMenu() }) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(sp.sm)) {
                PaperStatusDot(RunStates.RUNNING, Modifier.padding(top = 7.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("lemon-paperplane-web", style = PaperTheme.typography.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("~/Code/lemon-paperplane/web", style = PaperTheme.typography.small, color = c.textTertiary, maxLines = 1)
                }
                PaperMoreButton({ sampleMenu() }, size = ButtonSize.Small)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(sp.xs), verticalArrangement = Arrangement.spacedBy(sp.xs)) {
                PaperBadge("Vite", icon = "zap")
                PaperBadge("Vue 3")
                PaperBadge("pnpm", icon = "package")
            }
            PaperDivider(subtle = true)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(sp.xs)) {
                PaperSelect(
                    options = listOf(SelectOption("dev", "dev", icon = "play"), SelectOption("build", "build", icon = "hammer")),
                    selected = "dev",
                    onSelect = {},
                    size = ButtonSize.Small,
                    modifier = Modifier.width(140.dp),
                )
                Spacer(Modifier.weight(1f))
                PaperIconButton("rotate-cw", tr("action.restart"), onClick = {}, size = ButtonSize.Small)
                PaperIconButton("square", tr("action.stop"), onClick = {}, size = ButtonSize.Small, tint = c.danger)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(sp.md)) {
                PaperLink("localhost:5173", onClick = {}, mono = true, icon = "globe")
                Spacer(Modifier.weight(1f))
                Text("CPU 3%  ·  182 MB", style = PaperTheme.typography.monoSmall, color = c.textSecondary)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(sp.sm)) {
                PaperIcon("git-branch", size = PaperTheme.sizes.iconSmall, tint = c.iconSecondary)
                Text("main", style = PaperTheme.typography.small, color = c.textSecondary)
                Text(tr("card.changes", 4), style = PaperTheme.typography.small, color = c.warning)
            }
        }
        PaperCard(Modifier.fillMaxWidth(), selected = true) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(sp.sm)) {
                PaperStatusDot(RunStates.FAILED)
                Text("api-server", style = PaperTheme.typography.title, modifier = Modifier.weight(1f))
                PaperBadge(tr("status.failed"), tone = Tone.Danger)
            }
            Text(tr("card.noConfig"), style = PaperTheme.typography.small, color = c.textTertiary)
        }
    }
}

// ---------------------------------------------------------------------------
// 分组、列表行

@Composable
private fun GroupsSection() {
    var autoDeps by remember { mutableStateOf(true) }
    var autoSync by remember { mutableStateOf(false) }
    Section(g("分组与列表行", "Groups & rows")) {
        PaperGroup(tr("settings.general"), dividerInset = PaperGroupDefaults.IconRowInset) {
            PaperListRow(tr("settings.language"), icon = "languages", value = "中文", showChevron = true, onClick = {})
            PaperListRow(tr("settings.theme"), icon = "sun", value = tr("settings.followSystem"), showChevron = true, onClick = {})
            PaperListRow(tr("sidebar.settings"), icon = "server", subtitle = "192.168.31.99:47391 · macOS", showChevron = true, onClick = {}, menu = { sampleMenu() })
        }
        PaperGroup(tr("settings.behavior"), info = tr("settings.autoDeps.info")) {
            PaperToggleRow(tr("settings.autoDeps"), checked = autoDeps, onCheckedChange = { autoDeps = it })
            PaperToggleRow(tr("settings.autoSync"), checked = autoSync, onCheckedChange = { autoSync = it }, info = tr("settings.autoSync.info"))
            PaperListRow(tr("settings.logLines"), value = "5000", showChevron = true, onClick = {})
            PaperToggleRow(tr("settings.autoToolchains"), checked = true, onCheckedChange = {}, enabled = false)
        }
        PaperGroup(g("工程列表", "Projects"), trailing = { PaperCountBadge(3) }) {
            PaperListRow(
                "lemon-paperplane-web",
                subtitle = "~/Code/lemon-paperplane/web",
                leading = { PaperStatusDot(RunStates.RUNNING) },
                trailing = { PaperBadge("Vite") },
                onClick = {},
                menu = { sampleMenu() },
            )
            PaperListRow(
                "api-server",
                subtitle = "~/Code/api",
                leading = { PaperStatusDot(RunStates.STARTING) },
                selected = true,
                onClick = {},
            )
            PaperListRow(tr("common.delete"), icon = "trash-2", destructive = true, onClick = {})
        }
    }
}

// ---------------------------------------------------------------------------
// 输入

@Composable
private fun InputsSection() {
    var name by remember { mutableStateOf("lemon-paperplane") }
    var empty by remember { mutableStateOf("") }
    var pwd by remember { mutableStateOf("secret-pass") }
    var port by remember { mutableStateOf("5173") }
    var bad by remember { mutableStateOf("ab") }
    var msg by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    var q2 by remember { mutableStateOf("vite") }
    Section(g("输入", "Inputs")) {
        Panel {
            PaperTextField(name, { name = it }, label = tr("common.name"), clearable = true)
            PaperTextField(empty, { empty = it }, placeholder = "user@example.com", leadingIcon = "user")
            PaperTextField(pwd, { pwd = it }, label = tr("server.add.password"), password = true)
            PaperTextField(port, { port = it }, prefix = "http://localhost:", suffix = "/", mono = true)
            PaperTextField(bad, { bad = it }, label = tr("host.users.password"), error = tr("host.users.password.rule"))
            PaperTextField("192.168.31.99", {}, enabled = false)
            PaperTextArea(msg, { msg = it }, placeholder = g("提交说明", "Commit message"))
            PaperSearchField(q, { q = it })
            PaperSearchField(q2, { q2 = it })
        }
    }
}

// ---------------------------------------------------------------------------
// 选择

@Composable
private fun ChoicesSection() {
    var lang by remember { mutableStateOf("system") }
    var sw by remember { mutableStateOf(true) }
    var seg by remember { mutableStateOf("auto") }
    var check1 by remember { mutableStateOf(true) }
    var check2 by remember { mutableStateOf(false) }
    var radio by remember { mutableStateOf("a") }
    val sp = PaperTheme.spacing
    Section(g("选择与开关", "Choices")) {
        Panel {
            PaperSelect(
                options = listOf(
                    SelectOption("system", tr("settings.followSystem")),
                    SelectOption("zh-Hans", "中文"),
                    SelectOption("en", "English"),
                ),
                selected = lang,
                onSelect = { lang = it },
                label = tr("settings.language"),
            )
            PaperSelect(options = listOf(SelectOption(1, "one")), selected = null, onSelect = {}, label = tr("config.toolchains"))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(sp.lg)) {
                PaperSwitch(sw, onCheckedChange = { sw = it })
                PaperSwitch(!sw, onCheckedChange = { sw = !it })
                PaperSwitch(true, onCheckedChange = {}, enabled = false)
            }
            PaperSegmented(
                listOf("auto" to tr("settings.mirror.auto"), "china" to tr("settings.mirror.china"), "global" to tr("settings.mirror.global")),
                selected = seg,
                onSelect = { seg = it },
            )
            PaperSegmented(
                listOf("auto" to tr("settings.mirror.auto"), "china" to tr("settings.mirror.china"), "global" to tr("settings.mirror.global")),
                selected = seg,
                onSelect = { seg = it },
                fill = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Column {
                PaperCheckbox(check1, onCheckedChange = { check1 = it }, label = tr("deps.cleanInstall"))
                PaperCheckbox(check2, onCheckedChange = { check2 = it }, label = tr("console.wrap"))
                PaperCheckbox(true, onCheckedChange = {}, label = tr("console.follow"), enabled = false)
            }
            Column {
                PaperRadio(radio == "a", onClick = { radio = "a" }, label = tr("settings.mirror.auto"))
                PaperRadio(radio == "b", onClick = { radio = "b" }, label = tr("settings.mirror.china"))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 面板、对话框

@Composable
private fun SheetsSection() {
    var sheet by remember { mutableStateOf(false) }
    var select by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf("dev") }
    val sp = PaperTheme.spacing
    Section(g("底部面板与对话框", "Sheets & dialogs"), info = g("手机上对话框一律从底部弹出。", "Dialogs slide up from the bottom on phones.")) {
        Panel {
            Column(verticalArrangement = Arrangement.spacedBy(sp.sm)) {
                PaperButton(g("打开面板", "Open sheet"), onClick = { sheet = true }, modifier = Modifier.fillMaxWidth())
                PaperButton(g("选择列表", "Select sheet"), onClick = { select = true }, modifier = Modifier.fillMaxWidth())
                PaperButton(g("确认（危险）", "Confirm (danger)"), onClick = {
                    Dialogs.confirm(tr("card.stopAll"), message = tr("deps.cleanInstall.confirm"), confirmText = tr("action.stop"), danger = true) {
                        delay(800)
                    }
                }, modifier = Modifier.fillMaxWidth())
                PaperButton(g("确认（失败）", "Confirm (fails)"), onClick = {
                    Dialogs.confirm(tr("action.restart"), confirmText = tr("action.restart")) {
                        delay(500)
                        error("connection refused")
                    }
                }, modifier = Modifier.fillMaxWidth())
                val newWs = g("新建工作区", "New workspace")
                PaperButton(g("输入", "Input"), onClick = {
                    Dialogs.input(newWs, label = tr("common.name"), validate = { if (it.length < 2) "≥ 2" else null }) {
                        delay(500)
                        Toasts.success(tr("common.saved"), it)
                    }
                }, modifier = Modifier.fillMaxWidth())
            }
        }
        PaperSheetPreview(title = tr("card.stopAll"), showClose = false) {
            Text(tr("deps.cleanInstall.confirm"), style = PaperTheme.typography.body, color = PaperTheme.colors.textSecondary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(sp.md)) {
                PaperButton(tr("common.cancel"), onClick = {}, modifier = Modifier.weight(1f))
                PaperButton(tr("action.stop"), onClick = {}, kind = ButtonKind.Danger, modifier = Modifier.weight(1f))
            }
        }
        PaperSheetPreview(title = tr("card.moveTo"), edgeToEdge = true) {
            SelectRow(SelectOption("a", g("默认工作区", "Default"), icon = "folder"), selected = true) {}
            SelectRow(SelectOption("b", "Side projects", icon = "folder", detail = "12"), selected = false) {}
        }
    }
    if (sheet) {
        PaperBottomSheet(onDismiss = { sheet = false }, title = g("添加服务器", "Add server")) { dismiss ->
            var addr by remember { mutableStateOf("") }
            PaperTextField(addr, { addr = it }, label = g("地址", "Address"), placeholder = "192.168.1.10:47391", mono = true)
            PaperButton(tr("common.continue"), onClick = dismiss, kind = ButtonKind.Primary, modifier = Modifier.fillMaxWidth())
        }
    }
    if (select) {
        PaperSelectSheet(
            options = (1..20).map { SelectOption("cfg$it", "config-$it", icon = "play", detail = if (it % 3 == 0) "npm" else null) },
            selected = picked,
            title = g("运行配置", "Run configuration"),
            onDismiss = { select = false },
        ) { picked = it }
    }
}

// ---------------------------------------------------------------------------
// 提示条

@Composable
private fun ToastsSection() {
    val sp = PaperTheme.spacing
    Section(g("提示条", "Toasts")) {
        Row(horizontalArrangement = Arrangement.spacedBy(sp.sm)) {
            PaperButton("Info", onClick = { Toasts.show(tr("common.copied")) }, size = ButtonSize.Small)
            PaperButton("Success", onClick = { Toasts.success(tr("common.saved")) }, size = ButtonSize.Small)
            PaperButton("Error", onClick = { Toasts.error(IllegalStateException("port 5173 is already in use"), title = tr("action.run.failed")) }, size = ButtonSize.Small)
        }
        PaperToastView(previewToast(tr("common.saved"), ToastKind.Success), onClose = {})
        PaperToastView(previewToast(tr("action.run.failed"), ToastKind.Error, detail = "port 5173 is already in use", actionText = tr("diagnosis.detail")), onClose = {})
        PaperToastView(previewToast(tr("connection.reconnecting"), ToastKind.Warning), onClose = {})
    }
}

// ---------------------------------------------------------------------------
// 菜单、说明气泡

@Composable
private fun MenusSection() {
    Section(g("菜单与说明气泡", "Menus & info")) {
        Row(horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.lg), verticalAlignment = Alignment.Top) {
            PaperMenuPanel(sampleMenu(), onItemClick = {})
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.md)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("settings.mirrors"), style = PaperTheme.typography.body)
                    PaperInfoButton(tr("settings.mirrors.info"))
                }
                PaperInfoBubble(tr("config.env.info"))
            }
        }
    }
}

private fun sampleMenu(): List<MenuEntry> = listOf(
    MenuEntry.Header("lemon-paperplane-web"),
    MenuEntry.Item(tr("card.open"), "app-window") {},
    MenuEntry.Item(tr("editor.action.copyPath"), "copy") {},
    MenuEntry.Item(tr("console.wrap"), checked = true) {},
    MenuEntry.Divider,
    MenuEntry.Item(tr("card.moveTo"), "folder-input", enabled = false) {},
    MenuEntry.Item(tr("common.remove"), "trash-2", destructive = true) {},
)

// ---------------------------------------------------------------------------
// 空状态、加载、进度

@Composable
private fun FeedbackSection() {
    var p by remember { mutableFloatStateOf(0.35f) }
    val sp = PaperTheme.spacing
    Section(g("空状态、加载、进度", "Empty, loading, progress")) {
        Panel {
            PaperEmptyState("folder-open", g("还没有工程", "No projects yet"), actionText = g("导入工程", "Import"), actionIcon = "plus", onAction = {})
        }
        Panel {
            Row(horizontalArrangement = Arrangement.spacedBy(sp.lg), verticalAlignment = Alignment.CenterVertically) {
                PaperSpinner()
                PaperSpinner(size = PaperTheme.sizes.iconSmall, tint = PaperTheme.colors.accent)
                Text(tr("connection.connecting"), style = PaperTheme.typography.small, color = PaperTheme.colors.textSecondary)
            }
            PaperProgressBar(p, Modifier.padding(vertical = sp.xs))
            PaperProgressBar(1f, tone = Tone.Success)
            PaperProgressBar(0.6f, tone = Tone.Danger)
            PaperProgressBar(null)
            Row(horizontalArrangement = Arrangement.spacedBy(sp.sm)) {
                PaperButton("-", onClick = { p = (p - 0.1f).coerceAtLeast(0f) }, size = ButtonSize.Small)
                PaperButton("+", onClick = { p = (p + 0.1f).coerceAtMost(1f) }, size = ButtonSize.Small)
            }
        }
        Box(Modifier.fillMaxWidth().height(160.dp).background(PaperTheme.colors.bgSurface, RoundedCornerShape(PaperTheme.radius.lg))) {
            PaperLoadingPage()
        }
    }
}

// ---------------------------------------------------------------------------
// 页内提示

@Composable
private fun AlertsSection() {
    Section(g("页内提示", "Alerts")) {
        PaperAlert(Tone.Danger, tr("action.run.failed"), message = "Error: listen EADDRINUSE: address already in use :::5173")
        PaperAlert(Tone.Warning, tr("deps.outdated"), actions = { PaperButton(tr("deps.install"), onClick = {}, size = ButtonSize.Small) })
        PaperAlert(Tone.Success, tr("common.saved"))
        PaperAlert(Tone.Info, tr("connection.reconnecting"))
    }
}

// ---------------------------------------------------------------------------
// 分隔线、代码

@Composable
private fun CodeSection() {
    Section(g("代码与分隔线", "Code & dividers")) {
        PaperCodeText("PAPERPLANE_DATA_DIR=/tmp/pp pp serve --listen 0.0.0.0:47391")
        PaperCodeText("SHA256:9f:2c:41:7a:0b:e3:55:c8:1d:aa:73:6e:02:9b:4f:d1:88:30:c6:5e:17:2a:b9:e4", small = true, wrap = false)
        PaperDivider()
        PaperDivider(subtle = true)
        Row(Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.md)) {
            Text("main", style = PaperTheme.typography.small)
            PaperDivider(vertical = true)
            Text("↑2 ↓0", style = PaperTheme.typography.monoSmall)
            PaperDivider(vertical = true, subtle = true)
            Text(tr("card.changes", 4), style = PaperTheme.typography.small, fontWeight = FontWeight.Medium)
        }
    }
}

// ---------------------------------------------------------------------------
// 图标

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IconsSection() {
    val names = remember {
        SharedAssets.context()?.assets?.list("shared/icons")?.filter { it.endsWith(".svg") }?.map { it.removeSuffix(".svg") }?.sorted() ?: emptyList()
    }
    Section(g("图标（${names.size}）", "Icons (${names.size})"), info = g("shared/icons，和桌面同一套。单色图标按前景色着色。", "shared/icons, same set as desktop. Monochrome icons follow the content color.")) {
        Panel {
            Row(horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                PaperBrandImage("app-icon", height = 48.dp)
                PaperBrandImage("menubar", height = 24.dp, tint = PaperTheme.colors.textPrimary)
                PaperIcon("rocket", size = PaperTheme.sizes.iconEmpty, tint = PaperTheme.colors.accent)
                PaperIcon("rocket", size = PaperTheme.sizes.iconLarge)
                PaperIcon("rocket")
                PaperIcon("rocket", size = PaperTheme.sizes.iconSmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (n in names) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        PaperIcon(n, tint = PaperTheme.colors.iconPrimary, contentDescription = n)
                    }
                }
            }
        }
    }
}
