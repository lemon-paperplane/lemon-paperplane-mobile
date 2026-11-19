package cn.lemonit.paperplane.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.lemonit.paperplane.ui.components.Dialogs
import cn.lemonit.paperplane.ui.components.MenuEntry
import cn.lemonit.paperplane.ui.components.PaperDialogHost
import cn.lemonit.paperplane.ui.components.PaperListRow
import cn.lemonit.paperplane.ui.components.PaperSelect
import cn.lemonit.paperplane.ui.components.PaperToastHost
import cn.lemonit.paperplane.ui.components.PaperToggleRow
import cn.lemonit.paperplane.ui.components.SelectOption
import cn.lemonit.paperplane.ui.components.Toasts
import cn.lemonit.paperplane.ui.i18n.I18n
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.theme.PaperplaneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 组件交互冒烟测试：提示条自动消失、全局确认面板、长按菜单、选择器、开关行、文案覆盖层 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ComponentsBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    private fun host(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            PaperplaneTheme {
                Box(Modifier.fillMaxSize()) {
                    content()
                    PaperToastHost()
                    PaperDialogHost()
                }
            }
        }
    }

    @Test
    fun toastShowsAndExpires() {
        Toasts.clear()
        host {}
        Toasts.show("hello toast")
        compose.onNodeWithText("hello toast").assertExists()
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()
        compose.onNodeWithText("hello toast").assertDoesNotExist()
    }

    @Test
    fun confirmSheetRunsAction() {
        var ran = false
        host {}
        Dialogs.confirm("Stop everything?", confirmText = "Stop now") { ran = true }
        compose.onNodeWithText("Stop everything?").assertExists()
        compose.onNodeWithText("Stop now").performClick()
        compose.waitForIdle()
        assertTrue(ran)
        compose.onNodeWithText("Stop everything?").assertDoesNotExist()
    }

    @Test
    fun longPressOpensMenu() {
        var picked = ""
        host {
            PaperListRow("row title", onClick = {}, menu = {
                listOf(MenuEntry.Item("Copy path", "copy") { picked = "copy" }, MenuEntry.Divider, MenuEntry.Item("Remove", destructive = true) {})
            })
        }
        compose.onNodeWithText("row title").performTouchInput { longClick() }
        compose.onNodeWithText("Copy path").assertExists().performClick()
        compose.waitForIdle()
        assertEquals("copy", picked)
        compose.onNodeWithText("Copy path").assertDoesNotExist()
    }

    @Test
    fun selectPicksOption() {
        var value by mutableStateOf("a")
        host {
            PaperSelect(listOf(SelectOption("a", "Alpha"), SelectOption("b", "Beta")), selected = value, onSelect = { value = it }, title = "Pick one")
        }
        compose.onNodeWithText("Alpha").performClick()
        compose.onNodeWithText("Beta").assertExists().performClick()
        compose.waitForIdle()
        assertEquals("b", value)
    }

    @Test
    fun toggleRowToggles() {
        host {
            var on by remember { mutableStateOf(false) }
            Column { PaperToggleRow("Auto sync", checked = on, onCheckedChange = { on = it }) }
        }
        compose.onNodeWithText("Auto sync").assertExists()
        compose.onNode(androidx.compose.ui.test.isToggleable() and androidx.compose.ui.test.hasText("Auto sync", substring = true, ignoreCase = false)).assertIsOff()
            .performClick().assertIsOn()
    }

    @Test
    fun androidOverlayOverridesDesktopText() {
        I18n.setSetting(I18n.ZH)
        assertTrue(tr("launch.info").contains("长按"))
        assertEquals("显示密码", tr("common.showPassword"))
        I18n.setSetting(I18n.EN)
        assertEquals("Show password", tr("common.showPassword"))
        assertEquals("3 changes", tr("card.changes", 3))
    }
}
