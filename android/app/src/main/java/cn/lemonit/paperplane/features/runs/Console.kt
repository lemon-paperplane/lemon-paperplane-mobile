package cn.lemonit.paperplane.features.runs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import cn.lemonit.paperplane.core.Actions
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.core.ServerPhase
import cn.lemonit.paperplane.kit.ApiException
import cn.lemonit.paperplane.kit.OpCaller
import cn.lemonit.paperplane.kit.PaperplaneJson
import cn.lemonit.paperplane.kit.ansi.ANSIColor
import cn.lemonit.paperplane.kit.ansi.ANSIPalette
import cn.lemonit.paperplane.kit.ansi.ANSIParser
import cn.lemonit.paperplane.kit.ansi.ANSIStyle
import cn.lemonit.paperplane.kit.clearRun
import cn.lemonit.paperplane.kit.model.LogLine
import cn.lemonit.paperplane.kit.model.LogStream
import cn.lemonit.paperplane.kit.runInput
import cn.lemonit.paperplane.kit.runLogs
import cn.lemonit.paperplane.kit.runLogsPage
import cn.lemonit.paperplane.net.ConsoleStream
import cn.lemonit.paperplane.net.RemoteClient
import cn.lemonit.paperplane.ui.components.ButtonSize
import cn.lemonit.paperplane.ui.components.MenuEntry
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.ButtonKind
import cn.lemonit.paperplane.ui.components.PaperDivider
import cn.lemonit.paperplane.ui.components.PaperIconButton
import cn.lemonit.paperplane.ui.components.PaperMoreButton
import cn.lemonit.paperplane.ui.components.PaperSearchField
import cn.lemonit.paperplane.ui.components.PaperSpinner
import cn.lemonit.paperplane.ui.components.ShadowKind
import cn.lemonit.paperplane.ui.components.Toasts
import cn.lemonit.paperplane.ui.components.paperShadow
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.theme.PaperColors
import cn.lemonit.paperplane.ui.theme.PaperTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

// 控制台（对照 Mac Components/Console.swift）：ANSI 着色、自动跟随底部、搜索高亮与上一个 / 下一个、
// 往上滑到顶自动按页读更早的日志（直到服务器能读到的第一行，App 被杀掉再回来也能看完整日志）、
// 长按一行进入选择（再点另一行选中一段）复制、向运行中的进程输入、日志里的地址可点（localhost 先转发）。
// 实时部分用 ConsoleStream，App 切到后台时停、回前台从记住的位置续上（中间缺的按页补齐）。
// 内核在伪终端里跑的进度条等原地刷新的末尾几行作为「实时尾部」单独推送，整体替换后显示在所有已定型行后面
// （见 ConsoleModel.kt 开头）；续上后以新连接第一条消息里的 live 为准，控制台结束时清空。

// ---------------------------------------------------------------- 网络

/** 控制台的网络部分：首次加载、实时流、往前 / 往后按页读。都在主线程协程里调（解析大页时切到后台线程）。 */
internal object ConsoleLoader {
    /** 最近若干行 + 最新 seq */
    suspend fun initial(state: ConsoleState, api: OpCaller) {
        val id = state.consoleId ?: return
        try {
            val r = api.runLogs(id, tail = ConsoleState.INITIAL, raw = true)
            val parser = ANSIParser()
            val parsed = withContext(Dispatchers.Default) { ConsoleParse.lines(r.lines, parser) }
            state.reset(parsed, r.lastSeq)
            state.tailParser.style = parser.style
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.failed(errorText(e))
        }
    }

    /** 实时流，直到取消或控制台结束 */
    suspend fun stream(state: ConsoleState, client: RemoteClient, scope: CoroutineScope) {
        val id = state.consoleId ?: return
        val since = if (state.attached) state.hi else state.latestSeq
        ConsoleStream(client, id, since).updates().collect { u ->
            when (u) {
                is ConsoleStream.Update.Lines -> {
                    val logs = decodeLines(u.lines)
                    // 实时尾部：最多几十行，直接在主线程解析（每行各用新的解析器，不碰 tailParser）
                    val live = u.live?.let { ConsoleParse.live(decodeLines(it)) }
                    var gap = false
                    var behind = false
                    if (logs.isNotEmpty()) {
                        if (state.attached) {
                            // 实时批次一般很小（服务器 100ms 合并一次），直接在主线程解析；输出很猛、一批很多行时放到后台线程
                            // （collect 是一批一批顺序处理的，样式的先后不会乱）
                            val parsed = if (logs.size <= 200) {
                                ConsoleParse.lines(logs, state.tailParser)
                            } else {
                                withContext(Dispatchers.Default) { ConsoleParse.lines(logs, state.tailParser) }
                            }
                            gap = state.appendLive(parsed)
                        } else {
                            state.noteLatest(logs.last().seq)
                            behind = state.follow
                        }
                    }
                    // 同一条消息：先追加已定型行，再整体替换实时尾部。两步之间没有挂起点，界面上是一次更新
                    if (live != null) state.replaceLive(live)
                    if (gap) scope.launch { catchUp(state, client, untilAttached = state.follow) }
                    if (behind) scope.launch { catchUp(state, client, untilAttached = true) }
                }
                ConsoleStream.Update.Ended -> state.markEnded()
                is ConsoleStream.Update.Disconnected, ConsoleStream.Update.Connected -> {}
            }
        }
    }

    private fun decodeLines(list: List<JsonObject>): List<LogLine> =
        list.mapNotNull { runCatching { PaperplaneJson.decodeFromJsonElement(LogLine.serializer(), it) }.getOrNull() }

    /** 往前读一页 */
    suspend fun loadOlder(state: ConsoleState, api: OpCaller) {
        val id = state.consoleId ?: return
        if (state.loadingOlder || !state.hasOlder || System.currentTimeMillis() < state.olderRetryAt) return
        state.loadingOlder = true
        try {
            val min = maxOf(state.firstAvailable, state.floor + 1)
            val lo = state.lo
            val from = maxOf(min, lo - ConsoleState.PAGE)
            val limit = (lo - from).toInt().coerceIn(1, 5000)
            val r = api.runLogsPage(id, from, limit, raw = true)
            val parsed = withContext(Dispatchers.Default) { ConsoleParse.lines(r.lines, ANSIParser()) }
            state.prependOlder(parsed, r.firstSeq)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.olderRetryAt = System.currentTimeMillis() + RETRY_MS
            Toasts.error(e)
        } finally {
            state.loadingOlder = false
        }
    }

    /** 跳到日志开头（服务器能读到的第一行；清空过的从清空处开始） */
    suspend fun jumpToStart(state: ConsoleState, api: OpCaller) {
        val id = state.consoleId ?: return
        try {
            val from = maxOf(state.floor + 1, 1)
            val r = api.runLogsPage(id, from, ConsoleState.PAGE, raw = true)
            val parsed = withContext(Dispatchers.Default) { ConsoleParse.lines(r.lines, ANSIParser()) }
            state.jumpToStart(parsed, r.firstSeq, r.lastSeq)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Toasts.error(e)
        }
    }

    private const val RETRY_MS = 5000L

    /**
     * 往后读（窗口脱离了最新时）：untilAttached 时一直读到接上实时（缺口太大且在跟随时直接跳到最新），否则只读一页。
     */
    suspend fun catchUp(state: ConsoleState, api: OpCaller, untilAttached: Boolean) {
        val id = state.consoleId ?: return
        if (state.loadingNewer || System.currentTimeMillis() < state.newerRetryAt) return
        state.loadingNewer = true
        try {
            while (!state.attached) {
                if (untilAttached && state.latestSeq - state.hi > ConsoleState.MAX_LINES / 2) {
                    val r = api.runLogs(id, tail = ConsoleState.INITIAL, raw = true)
                    val parser = ANSIParser()
                    val parsed = withContext(Dispatchers.Default) { ConsoleParse.lines(r.lines, parser) }
                    state.jumpToTail(parsed, r.lastSeq)
                    state.tailParser.style = parser.style
                    if (!state.attached) continue
                    break
                }
                val before = state.hi
                val r = api.runLogsPage(id, state.hi + 1, ConsoleState.PAGE, raw = true)
                val parser = ANSIParser().also { it.style = state.tailParser.style }
                val parsed = withContext(Dispatchers.Default) { ConsoleParse.lines(r.lines, parser) }
                state.appendNewer(parsed, r.lastSeq)
                state.tailParser.style = parser.style
                if (state.hi == before && !state.attached) break // 没有进展（数据异常），别死循环
                if (!untilAttached) break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 往后补失败不提示（实时流还连着时会不停触发），冷却后再试
            state.newerRetryAt = System.currentTimeMillis() + RETRY_MS
        } finally {
            state.loadingNewer = false
        }
    }

    fun errorText(e: Exception): String =
        if (e is ApiException && e.code == ApiException.NOT_FOUND) tr("mobile.console.unavailable") else (e.message ?: tr("common.failed"))
}

/**
 * 某个控制台（运行 id 或任务 id）的状态：首次显示时读最近的日志，前台时连着实时流。
 * 同一页面里切换 consoleId 会换一个新的状态。
 */
@Composable
fun rememberConsoleState(consoleId: String?): ConsoleState {
    val state = remember(consoleId) { ConsoleState(consoleId) }
    val fg = AppModel.foreground
    val ready = AppModel.phase == ServerPhase.Ready
    LaunchedEffect(state, fg, ready) {
        if (state.consoleId == null || !fg || !ready) return@LaunchedEffect
        val client = AppModel.client ?: return@LaunchedEffect
        if (!state.initialized) ConsoleLoader.initial(state, client)
        if (state.error != null) return@LaunchedEffect
        if (!state.attached && state.follow) launch { ConsoleLoader.catchUp(state, client, untilAttached = true) }
        if (state.ended) return@LaunchedEffect
        ConsoleLoader.stream(state, client, this)
    }
    return state
}

// ---------------------------------------------------------------- 外观

/** 控制台的偏好（自动换行），本次打开 App 期间记住 */
object ConsolePrefs {
    var wrap by mutableStateOf(true)
}

/**
 * 控制台（运行页、任务日志、插件视图的 console 节点共用）。
 *
 * ```
 * ConsoleView(run.id, runId = run.id, inputEnabled = run.isActive, projectId = run.projectId, configId = run.configId)
 * ConsoleView(task.id)   // 任务日志
 * ```
 *
 * @param consoleId 运行 id 或任务 id；为空时显示空白
 * @param runId 有值时「清空」也清服务器的内存缓冲，并且 inputEnabled 时底部可以向进程输入
 * @param projectId / configId 日志里 localhost 地址按这个运行配置的远程打开方式打开
 * @param toolbarLeading 工具栏左侧的内容（运行状态等）
 */
@Composable
fun ConsoleView(
    consoleId: String?,
    modifier: Modifier = Modifier,
    runId: String? = null,
    inputEnabled: Boolean = false,
    projectId: String? = null,
    configId: String? = null,
    showToolbar: Boolean = true,
    toolbarLeading: (@Composable RowScope.() -> Unit)? = null,
) {
    val state = rememberConsoleState(consoleId)
    ConsoleView(state, modifier, runId, inputEnabled, projectId, configId, showToolbar, toolbarLeading)
}

/** 控制台本体（状态由调用方给：截图测试用 [ConsoleState.preview]） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConsoleView(
    state: ConsoleState,
    modifier: Modifier = Modifier,
    runId: String? = null,
    inputEnabled: Boolean = false,
    projectId: String? = null,
    configId: String? = null,
    showToolbar: Boolean = true,
    toolbarLeading: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = PaperTheme.colors
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var searching by rememberSaveable(state.consoleId) { mutableStateOf(state.query.isNotEmpty()) }
    // 只有用户点开搜索时才弹键盘（页面重建、带着搜索词恢复时不弹）
    var focusSearch by remember { mutableStateOf(false) }
    val wrap = ConsolePrefs.wrap
    val haptics = LocalHapticFeedback.current

    ConsoleScrollEffects(state, listState)
    ConsoleSearchEffect(state, listState)

    BackHandler(enabled = state.selecting) { state.cancelSelection() }
    BackHandler(enabled = searching && !state.selecting) {
        searching = false
        state.query = ""
    }

    Column(modifier.background(c.consoleBg)) {
        if (showToolbar) {
            ConsoleToolbar(
                state = state,
                searching = searching,
                onToggleSearch = {
                    searching = !searching
                    focusSearch = searching
                    if (!searching) state.query = ""
                },
                onScrollToEnd = { scope.launch { scrollToEnd(state, listState) } },
                onClear = {
                    if (runId != null) AppModel.perform { api -> api.clearRun(runId) }
                    state.clear()
                },
                onJumpToStart = {
                    val client = AppModel.client
                    if (client != null) {
                        scope.launch {
                            ConsoleLoader.jumpToStart(state, client)
                            listState.scrollToItem(0)
                        }
                    }
                },
                leading = toolbarLeading,
            )
            if (searching) ConsoleSearchBar(state, listState, autoFocus = focusSearch)
            PaperDivider(subtle = true)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ConsoleLines(
                state = state,
                listState = listState,
                wrap = wrap,
                onLink = { url -> RunLinkActions.openDefault(url, projectId, configId, runId) },
                onLongPress = { seq ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    state.startSelection(seq)
                },
            )
            when {
                state.loading -> PaperSpinner(Modifier.align(Alignment.Center), tint = c.iconSecondary)
                state.error != null -> Text(
                    state.error.orEmpty(),
                    style = PaperTheme.typography.body,
                    color = c.textSecondary,
                    modifier = Modifier.align(Alignment.Center).padding(PaperTheme.spacing.xxl),
                )
                !state.hasContent && !state.hasOlder && !state.loadingNewer -> Text(
                    tr("mobile.console.empty"),
                    style = PaperTheme.typography.small,
                    color = c.textTertiary,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            if (!state.follow && state.hasContent) {
                JumpToEndButton(
                    Modifier.align(Alignment.BottomEnd).padding(PaperTheme.spacing.lg),
                    onClick = {
                        state.follow = true
                        val client = AppModel.client
                        if (!state.attached && client != null) scope.launch { ConsoleLoader.catchUp(state, client, untilAttached = true) }
                        scope.launch { scrollToEnd(state, listState) }
                    },
                )
            }
        }
        if (inputEnabled && runId != null) {
            PaperDivider(subtle = true)
            ConsoleInput(runId)
        }
    }
}

/** 滚到列表最底部（底部那一项：顶部一项 + 已定型行 + 实时尾部之后） */
private suspend fun scrollToEnd(state: ConsoleState, listState: LazyListState) {
    if (!state.hasContent) return
    listState.scrollToItem(state.rowCount + 1)
}

/** 跟随、往上 / 往下翻页 */
@Composable
private fun ConsoleScrollEffects(state: ConsoleState, listState: LazyListState) {
    val userScrolling = remember { mutableStateOf(false) }
    // 用户自己拖动（不是程序滚动）
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) userScrolling.value = true }
    }
    // 用户滚动时：离开底部就停止跟随，滚回底部恢复
    LaunchedEffect(listState, state) {
        snapshotFlow { listState.canScrollForward }.collect { canFwd ->
            if (!userScrolling.value) return@collect
            if (canFwd) state.follow = false else if (state.attached) state.follow = true
        }
    }
    LaunchedEffect(listState, state) {
        snapshotFlow { listState.isScrollInProgress }.collect { busy ->
            if (!busy && userScrolling.value) {
                userScrolling.value = false
                if (!listState.canScrollForward && state.attached) state.follow = true
            }
        }
    }
    // 跟随时新行进来（或实时尾部变化）就贴到底部
    LaunchedEffect(listState, state) {
        snapshotFlow { state.version to state.follow }.collect { (_, follow) ->
            if (follow && !userScrolling.value) scrollToEnd(state, listState)
        }
    }
    // 接近顶部：往前读
    LaunchedEffect(listState, state) {
        snapshotFlow { state.initialized && listState.firstVisibleItemIndex < 40 && state.hasOlder && !state.loadingOlder }
            .filter { it }
            .collect {
                val api = AppModel.client ?: return@collect
                // 列表按第一个可见项的 key 保持位置；第一个可见项是顶部的转圈时要自己把原来那一行放回原处
                val anchor = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key is Long }
                val anchorSeq = anchor?.key as? Long
                val anchorOffset = anchor?.offset ?: 0
                ConsoleLoader.loadOlder(state, api)
                // 失败了：冷却后如果还停在顶部附近就再试
                while (state.olderRetryAt > System.currentTimeMillis()) {
                    delay(state.olderRetryAt - System.currentTimeMillis())
                    if (listState.firstVisibleItemIndex >= 40 || !state.hasOlder) break
                    ConsoleLoader.loadOlder(state, api)
                }
                if (anchorSeq != null && listState.firstVisibleItemIndex == 0) {
                    val i = state.indexOf(anchorSeq)
                    if (i >= 0) {
                        listState.scrollToItem(i + 1)
                        listState.scrollBy(-anchorOffset.toFloat())
                    }
                }
            }
    }
    // 接近底部且窗口脱离了最新：往后读
    LaunchedEffect(listState, state) {
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            state.initialized && !state.attached && !state.loadingNewer && last >= state.lines.size - 40
        }
            .filter { it }
            .collect {
                val api = AppModel.client ?: return@collect
                ConsoleLoader.catchUp(state, api, untilAttached = false)
                while (!state.attached && state.newerRetryAt > System.currentTimeMillis()) {
                    delay(state.newerRetryAt - System.currentTimeMillis())
                    ConsoleLoader.catchUp(state, api, untilAttached = false)
                }
            }
    }
}

/**
 * 搜索：输入停顿 150ms 后在已加载的行和显示中的实时尾部里找；换了搜索词时滚到最新的一处。
 * 日志一直在刷时最多每 300ms 重算一次（conflate：忙的时候只留最新的变化，不会一直被打断而算不出来）。
 */
@Composable
private fun ConsoleSearchEffect(state: ConsoleState, listState: LazyListState) {
    LaunchedEffect(state) {
        var last = ""
        snapshotFlow { state.query to state.version }.conflate().collect { (q, _) ->
            if (q.isEmpty()) {
                last = ""
                if (state.matches.isNotEmpty()) state.setMatches(emptyList(), keepIndex = false)
                return@collect
            }
            if (q != last) {
                delay(150)
                if (state.query != q) return@collect // 还在输入
            }
            val snapshot = state.rowsSnapshot()
            // 行少时直接找，行多时放到后台线程
            val found = if (snapshot.size < 3000) ConsoleSearch.find(snapshot, q) else withContext(Dispatchers.Default) { ConsoleSearch.find(snapshot, q) }
            val changed = q != last
            last = q
            state.setMatches(found, keepIndex = !changed)
            if (changed) {
                state.currentMatch?.let { scrollToSeq(state, listState, it.seq) }
            } else {
                delay(300)
            }
        }
    }
}

private suspend fun scrollToSeq(state: ConsoleState, listState: LazyListState, seq: Long) {
    val i = state.indexOf(seq)
    if (i < 0) return
    state.follow = false
    // 让命中的行不贴在最上面
    listState.scrollToItem((i + 1 - 3).coerceAtLeast(0))
}

@Composable
private fun ConsoleToolbar(
    state: ConsoleState,
    searching: Boolean,
    onToggleSearch: () -> Unit,
    onScrollToEnd: () -> Unit,
    onClear: () -> Unit,
    onJumpToStart: () -> Unit,
    leading: (@Composable RowScope.() -> Unit)?,
) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    Row(
        Modifier.fillMaxWidth().height(PaperTheme.sizes.touchTarget).background(c.bgContent).padding(start = sp.md, end = sp.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.selecting) {
            Text(
                tr("mobile.console.selected", state.selectedCount),
                style = PaperTheme.typography.label,
                color = c.textPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            PaperButton(tr("console.copy"), onClick = {
                copyText(state.selectedText())
                state.cancelSelection()
            }, kind = ButtonKind.Text, size = ButtonSize.Small)
            PaperButton(tr("mobile.console.selectAll"), onClick = { state.selectAll() }, kind = ButtonKind.Text, size = ButtonSize.Small)
            PaperIconButton("x", tr("common.cancel"), onClick = { state.cancelSelection() }, tint = c.iconSecondary)
            return@Row
        }
        if (leading != null) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, content = leading)
        } else {
            Spacer(Modifier.weight(1f))
        }
        PaperIconButton("search", tr("console.search"), onClick = onToggleSearch, active = searching)
        PaperIconButton("wrap-text", tr("console.wrap"), onClick = { ConsolePrefs.wrap = !ConsolePrefs.wrap }, active = ConsolePrefs.wrap)
        PaperIconButton("arrow-down-to-line", tr("console.follow"), onClick = {
            state.follow = !state.follow
            if (state.follow) onScrollToEnd()
        }, active = state.follow)
        PaperMoreButton({
            listOf(
                MenuEntry.Item(tr("mobile.console.copyLoaded"), icon = "copy", enabled = state.hasContent) { copyText(state.selectedText()) },
                MenuEntry.Item(tr("mobile.console.select"), icon = "text-cursor-input", enabled = state.hasContent) {
                    state.lastRow?.let { state.startSelection(it.seq) }
                },
                MenuEntry.Item(tr("mobile.console.jumpToStart"), icon = "arrow-up", enabled = state.consoleId != null, onClick = onJumpToStart),
                MenuEntry.Divider,
                MenuEntry.Item(tr("console.clear"), icon = "eraser", onClick = onClear),
            )
        })
    }
}

/** 复制（剪贴板一次放不下太多：超过时只复制最后一段） */
private fun copyText(all: String) {
    val max = 300_000
    if (all.length <= max) {
        Actions.copy(all)
        return
    }
    var cut = all.length - max
    val nl = all.indexOf('\n', cut)
    if (nl in cut until all.length - 1) cut = nl + 1
    val text = all.substring(cut)
    Actions.copy(text, tr("mobile.console.copiedTail", text.count { it == '\n' } + 1))
}

@Composable
private fun ConsoleSearchBar(state: ConsoleState, listState: LazyListState, autoFocus: Boolean) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (autoFocus) runCatching { focus.requestFocus() } }
    fun go(delta: Int) {
        val seq = state.step(delta) ?: return
        scope.launch { scrollToSeq(state, listState, seq) }
    }
    Row(
        Modifier.fillMaxWidth().background(c.bgSubtle).padding(start = sp.md, end = sp.xxs, top = sp.xs, bottom = sp.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.xs),
    ) {
        PaperSearchField(
            state.query,
            { state.query = it },
            modifier = Modifier.weight(1f),
            placeholder = tr("console.search"),
            onSubmit = { go(1) },
            focusRequester = focus,
        )
        val label = when {
            state.query.isEmpty() -> ""
            state.matches.isEmpty() -> tr("console.noMatch")
            else -> "${state.matchIndex + 1}/${state.matches.size}"
        }
        Text(label, style = PaperTheme.typography.small, color = c.textTertiary, maxLines = 1)
        PaperIconButton("chevron-up", tr("console.prev"), onClick = { go(-1) }, enabled = state.matches.isNotEmpty())
        PaperIconButton("chevron-down", tr("console.next"), onClick = { go(1) }, enabled = state.matches.isNotEmpty())
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConsoleLines(
    state: ConsoleState,
    listState: LazyListState,
    wrap: Boolean,
    onLink: (String) -> Unit,
    onLongPress: (Long) -> Unit,
) {
    val c = PaperTheme.colors
    val style = PaperTheme.typography.monoSmall
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val charWidth = remember(style, density) { with(density) { measurer.measure("0", style).size.width.toDp() } }
    val hPad = PaperTheme.spacing.md
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val contentWidth = if (wrap) maxWidth else max(maxWidth, min(charWidth * state.contentWidth + hPad * 2 + 8.dp, 12000.dp))
        val hScroll = rememberScrollState()
        Box(Modifier.fillMaxSize().then(if (wrap) Modifier else Modifier.horizontalScroll(hScroll))) {
            LazyColumn(state = listState, modifier = Modifier.width(contentWidth).fillMaxHeight()) {
                item(key = "head", contentType = "head") { ConsoleHead(state) }
                // 已定型行之后是实时尾部（只在窗口接上最新时有）；两段的 seq 不重叠（实时行用界面编号），key 不会冲突
                val lines = state.lines
                val live = state.liveRows
                val row: @Composable (ConsoleLine) -> Unit = { line ->
                    ConsoleRow(
                        line = line,
                        colors = c,
                        style = style,
                        wrap = wrap,
                        matches = state.matchesBySeq[line.seq],
                        current = state.currentMatch?.takeIf { it.seq == line.seq },
                        selected = state.isSelected(line.seq),
                        hPad = hPad,
                        onClick = { if (state.selecting) state.extendSelection(line.seq) },
                        onLongClick = { onLongPress(line.seq) },
                        onLink = onLink,
                    )
                }
                items(count = lines.size, key = { lines[it].seq }, contentType = { "line" }) { i -> row(lines[i]) }
                items(count = live.size, key = { live[it].seq }, contentType = { "line" }) { i -> row(live[i]) }
                item(key = "foot", contentType = "foot") {
                    Box(Modifier.fillMaxWidth().heightIn(min = PaperTheme.spacing.sm), contentAlignment = Alignment.Center) {
                        if (state.loadingNewer) PaperSpinner(Modifier.padding(PaperTheme.spacing.md), size = PaperTheme.sizes.iconSmall, tint = c.iconSecondary)
                    }
                }
            }
        }
    }
}

/** 列表顶部：读更早的日志时转圈；读到服务器上第一行时标出「日志开头」 */
@Composable
private fun ConsoleHead(state: ConsoleState) {
    val c = PaperTheme.colors
    Box(Modifier.fillMaxWidth().heightIn(min = PaperTheme.spacing.xs), contentAlignment = Alignment.Center) {
        when {
            state.loadingOlder || (state.hasOlder && state.lines.isNotEmpty()) ->
                PaperSpinner(Modifier.padding(PaperTheme.spacing.md), size = PaperTheme.sizes.iconSmall, tint = c.iconSecondary)
            state.initialized && state.hasContent && !state.hasOlder && state.floor == 0L ->
                Text(
                    tr("mobile.console.start"),
                    style = PaperTheme.typography.caption,
                    color = c.textTertiary,
                    modifier = Modifier.padding(vertical = PaperTheme.spacing.sm),
                )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConsoleRow(
    line: ConsoleLine,
    colors: PaperColors,
    style: androidx.compose.ui.text.TextStyle,
    wrap: Boolean,
    matches: List<ConsoleMatch>?,
    current: ConsoleMatch?,
    selected: Boolean,
    hPad: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onLink: (String) -> Unit,
) {
    val text = remember(line, colors, matches, current) { consoleText(line, colors, matches, current, onLink) }
    Text(
        text,
        style = style,
        softWrap = wrap,
        maxLines = if (wrap) Int.MAX_VALUE else 1,
        overflow = TextOverflow.Clip,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.consoleSelection else Color.Transparent)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = hPad, vertical = 1.dp),
    )
}

private val urlRegex = Regex("https?://[^\\s\"'<>\\u001b]+")

/** 一行的带颜色文字：ANSI 段落 → 颜色，地址可点，搜索命中加底色 */
internal fun consoleText(
    line: ConsoleLine,
    colors: PaperColors,
    matches: List<ConsoleMatch>?,
    current: ConsoleMatch?,
    onLink: ((String) -> Unit)?,
): AnnotatedString = buildAnnotatedString {
    val base = when (line.stream) {
        LogStream.STDERR -> colors.consoleStderr
        LogStream.SYSTEM -> colors.consoleSystem
        else -> colors.consoleText
    }
    val spans = line.spans
    if (spans == null) {
        withStyle(SpanStyle(color = base)) { append(line.text) }
    } else {
        for (s in spans) withStyle(spanStyle(s.style, base, colors)) { append(s.text) }
    }
    if (onLink != null && line.text.contains("://")) {
        for (m in urlRegex.findAll(line.text)) {
            var u = m.value
            while (u.isNotEmpty() && u.last() in ".,;:)]}") u = u.dropLast(1)
            if (u.isEmpty()) continue
            addLink(
                LinkAnnotation.Clickable(
                    tag = u,
                    styles = TextLinkStyles(SpanStyle(color = colors.textLink, textDecoration = TextDecoration.Underline)),
                    linkInteractionListener = { onLink(u) },
                ),
                m.range.first,
                m.range.first + u.length,
            )
        }
    }
    if (matches != null) {
        for (m in matches) {
            val end = minOf(m.end, line.text.length)
            if (m.start >= end) continue
            addStyle(SpanStyle(background = if (m == current) colors.searchMatchCurrent else colors.searchMatch), m.start, end)
        }
    }
}

private fun spanStyle(st: ANSIStyle, base: Color, colors: PaperColors): SpanStyle {
    var fg = ansiColor(st.fg, base, colors)
    var bg: Color? = if (st.bg == ANSIColor.Default) null else ansiColor(st.bg, Color.Transparent, colors)
    if (st.inverse) {
        val f = fg
        fg = bg ?: colors.consoleBg
        bg = f
    }
    if (st.dim) fg = fg.copy(alpha = 0.6f)
    val deco = when {
        st.underline && st.strikethrough -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
        st.underline -> TextDecoration.Underline
        st.strikethrough -> TextDecoration.LineThrough
        else -> null
    }
    return SpanStyle(
        color = fg,
        background = bg ?: Color.Unspecified,
        fontWeight = if (st.bold) FontWeight.Bold else null,
        fontStyle = if (st.italic) FontStyle.Italic else null,
        textDecoration = deco,
    )
}

/** ANSI 颜色 → 主题色：0–15 取设计变量的 ansi 调色板，16–255 与真彩直接换算 */
internal fun ansiColor(c: ANSIColor, fallback: Color, colors: PaperColors): Color = when (c) {
    ANSIColor.Default -> fallback
    is ANSIColor.Palette -> if (c.index < 16) {
        colors.ansi.getOrNull(c.index) ?: fallback
    } else {
        ANSIPalette.rgb(c.index)?.let { Color(it.r, it.g, it.b) } ?: fallback
    }
    is ANSIColor.Rgb -> Color(c.r, c.g, c.b)
}

@Composable
private fun JumpToEndButton(modifier: Modifier, onClick: () -> Unit) {
    val c = PaperTheme.colors
    Box(
        modifier
            .size(PaperTheme.sizes.touchTarget)
            .paperShadow(ShadowKind.Popup, CircleShape)
            .clip(CircleShape)
            .background(c.bgElevated)
            .border(PaperTheme.sizes.border, c.border, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        PaperIconButton("arrow-down-to-line", tr("mobile.console.jumpToEnd"), onClick = onClick, tint = c.iconPrimary)
    }
}

/** 向运行中的进程输入一行（run.input，自动补换行） */
@Composable
private fun ConsoleInput(runId: String) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    var text by rememberSaveable(runId) { mutableStateOf("") }
    val style = PaperTheme.typography.mono.copy(color = c.consoleText)
    fun send() {
        if (text.isEmpty()) return
        val t = text
        text = ""
        AppModel.perform { api -> api.runInput(runId, t) }
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).background(c.consoleBg).padding(start = sp.md, end = sp.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.sm),
    ) {
        PaperIcon("chevron-right", size = PaperTheme.sizes.iconSmall, tint = c.iconSecondary)
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Send,
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None,
            ),
            keyboardActions = KeyboardActions(onSend = { send() }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) Text(tr("mobile.console.inputPlaceholder"), style = style.copy(color = c.textTertiary), maxLines = 1)
                    inner()
                }
            },
        )
        PaperIconButton("corner-down-left", tr("console.send"), onClick = { send() }, enabled = text.isNotEmpty(), tint = c.iconPrimary)
    }
}
