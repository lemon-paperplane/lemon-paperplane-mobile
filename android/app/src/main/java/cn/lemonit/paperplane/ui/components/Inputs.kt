package cn.lemonit.paperplane.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.theme.PaperTheme

/**
 * 输入框（单行 / 多行）：描边、圆角，聚焦时强调色描边加光圈，出错时红色描边并在下方显示错误（对照 Mac PPTextField）。
 *
 * ```
 * PaperTextField(name, { name = it }, label = tr("common.name"))
 * PaperTextField(pwd, { pwd = it }, password = true, error = if (bad) tr("host.users.password.rule") else null)
 * PaperTextField(port, { port = it }, prefix = "http://localhost:", keyboardType = KeyboardType.Number, mono = true)
 * ```
 *
 * @param label 输入框上方的字段名
 * @param error 非空时进入错误态；空字符串只标红不显示文字
 * @param password 显示为圆点，右侧带「显示密码」切换
 * @param clearable 有内容时右侧显示清除按钮
 * @param onSubmit 键盘上的完成 / 前往（单行时）
 */
@Composable
fun PaperTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    error: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    password: Boolean = false,
    mono: Boolean = false,
    leadingIcon: String? = null,
    prefix: String? = null,
    suffix: String? = null,
    clearable: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    keyboardType: KeyboardType = if (password) KeyboardType.Password else KeyboardType.Text,
    imeAction: ImeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
    onSubmit: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
) {
    val c = PaperTheme.colors
    val t = PaperTheme.typography
    val sp = PaperTheme.spacing
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var reveal by rememberSaveable { mutableStateOf(false) }
    val invalid = error != null
    val radius = PaperTheme.radius.lg
    val shape = RoundedCornerShape(radius)
    val textStyle = (if (mono) t.mono else t.body).copy(color = if (enabled) c.textPrimary else c.textDisabled)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(sp.xs)) {
        if (label != null) Text(label, style = t.label, color = c.textSecondary)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            enabled = enabled,
            readOnly = readOnly,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            textStyle = textStyle,
            cursorBrush = SolidColor(c.accent),
            visualTransformation = if (password && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction, autoCorrectEnabled = !password && !mono),
            keyboardActions = KeyboardActions(onAny = { onSubmit?.invoke() }),
            interactionSource = interaction,
            decorationBox = { inner ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = PaperTheme.sizes.controlHeight)
                        .focusRing(focused && !invalid, c.focusRing, radius)
                        .background(if (enabled) c.bgInput else c.bgInputDisabled, shape)
                        .border(
                            PaperTheme.sizes.border,
                            when {
                                invalid -> c.danger
                                focused -> c.borderFocus
                                else -> c.borderStrong
                            },
                            shape,
                        )
                        .padding(start = sp.lg, end = if (password || clearable || trailing != null) sp.xs else sp.lg),
                    horizontalArrangement = Arrangement.spacedBy(sp.sm),
                    verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                ) {
                    if (leadingIcon != null) {
                        PaperIcon(
                            leadingIcon,
                            size = PaperTheme.sizes.iconSmall,
                            tint = c.iconSecondary,
                            modifier = if (singleLine) Modifier else Modifier.padding(top = sp.md + 1.dp),
                        )
                    }
                    if (prefix != null) Text(prefix, style = textStyle.copy(color = c.textTertiary), maxLines = 1)
                    Box(
                        Modifier
                            .weight(1f)
                            .padding(vertical = if (singleLine) 0.dp else sp.md + 2.dp),
                        contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
                    ) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(placeholder, style = textStyle.copy(color = c.textTertiary), maxLines = if (singleLine) 1 else Int.MAX_VALUE)
                        }
                        inner()
                    }
                    if (suffix != null) Text(suffix, style = textStyle.copy(color = c.textTertiary), maxLines = 1)
                    if (clearable && value.isNotEmpty() && enabled && !readOnly) {
                        PaperIconButton("x", tr("common.clear"), onClick = { onValueChange("") }, size = ButtonSize.Small, tint = c.iconSecondary)
                    }
                    if (password) {
                        PaperIconButton(
                            if (reveal) "eye-off" else "eye",
                            if (reveal) tr("common.hidePassword") else tr("common.showPassword"),
                            onClick = { reveal = !reveal },
                            size = ButtonSize.Small,
                            tint = c.iconSecondary,
                        )
                    }
                    trailing?.invoke()
                }
            },
        )
        if (!error.isNullOrEmpty()) {
            Text(error, style = t.small, color = c.danger)
        }
    }
}

/**
 * 多行输入（提交说明、命令）。和 [PaperTextField] 同一外观，默认 3 行起。
 *
 * `PaperTextArea(message, { message = it }, placeholder = tr("vcs.commit.placeholder"), minLines = 4)`
 */
@Composable
fun PaperTextArea(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    error: String? = null,
    enabled: Boolean = true,
    mono: Boolean = false,
    minLines: Int = 3,
    maxLines: Int = 12,
) = PaperTextField(
    value = value,
    onValueChange = onValueChange,
    modifier = modifier,
    label = label,
    placeholder = placeholder,
    error = error,
    enabled = enabled,
    singleLine = false,
    minLines = minLines,
    maxLines = maxLines,
    mono = mono,
)

/**
 * 搜索框：浅灰底、无描边、左侧放大镜、有内容时右侧清除。
 *
 * `PaperSearchField(query, { query = it })`
 */
@Composable
fun PaperSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = tr("common.search"),
    onSubmit: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val shape = RoundedCornerShape(PaperTheme.radius.lg)
    val style = PaperTheme.typography.body.copy(color = c.textPrimary)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(c.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onSearch = { onSubmit?.invoke() }),
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(c.neutralSubtle, shape)
                    .padding(start = sp.md + 2.dp, end = sp.xxs),
                horizontalArrangement = Arrangement.spacedBy(sp.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PaperIcon("search", size = PaperTheme.sizes.iconSmall, tint = c.iconSecondary)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = style.copy(color = c.textTertiary), maxLines = 1)
                    inner()
                }
                if (value.isNotEmpty()) {
                    PaperIconButton("x", tr("common.clear"), onClick = { onValueChange("") }, size = ButtonSize.Small, tint = c.iconSecondary)
                }
            }
        },
    )
}

/** 聚焦光圈：画在控件外面 [width] 宽（不占布局） */
private fun Modifier.focusRing(show: Boolean, color: Color, radius: Dp, width: Dp = 2.dp): Modifier =
    if (!show) {
        this
    } else {
        drawBehind {
            val w = width.toPx()
            drawRoundRect(
                color = color,
                topLeft = Offset(-w / 2, -w / 2),
                size = Size(size.width + w, size.height + w),
                cornerRadius = CornerRadius(radius.toPx() + w / 2),
                style = Stroke(w),
            )
        }
    }
