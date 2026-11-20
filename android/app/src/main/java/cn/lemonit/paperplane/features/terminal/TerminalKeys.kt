package cn.lemonit.paperplane.features.terminal

// 辅助键栏发给终端的字节序列（和 xterm 一致）。纯函数，单元测试覆盖。

/** 辅助键栏上的特殊键 */
internal enum class TermKey { ESC, TAB, UP, DOWN, LEFT, RIGHT, HOME, END, PAGE_UP, PAGE_DOWN }

/** 粘滞修饰键的状态：点一下对下一个键生效（ONCE），连点两下锁定（LOCKED），再点一下关掉 */
internal enum class ModState { OFF, ONCE, LOCKED;

    val on: Boolean get() = this != OFF
}

internal object TermKeys {
    private const val ESC = "\u001b"

    /** xterm 的修饰参数：1 + Shift(1) + Alt(2) + Ctrl(4) */
    fun modParam(ctrl: Boolean, alt: Boolean, shift: Boolean = false): Int =
        1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)

    /**
     * 特殊键的序列。appCursor：程序打开了「应用光标键」模式（vim、less、tmux 等，CSI ? 1 h），方向键和 Home/End 发 ESC O x。
     */
    fun sequence(key: TermKey, ctrl: Boolean = false, alt: Boolean = false, appCursor: Boolean = false): String {
        val m = modParam(ctrl, alt)
        fun cursor(letter: Char): String = when {
            m > 1 -> "$ESC[1;$m$letter"
            appCursor -> "${ESC}O$letter"
            else -> "$ESC[$letter"
        }
        fun tilde(n: Int): String = if (m > 1) "$ESC[$n;$m~" else "$ESC[$n~"
        return when (key) {
            TermKey.ESC -> if (alt) ESC + ESC else ESC
            TermKey.TAB -> if (alt) "$ESC\t" else "\t"
            TermKey.UP -> cursor('A')
            TermKey.DOWN -> cursor('B')
            TermKey.RIGHT -> cursor('C')
            TermKey.LEFT -> cursor('D')
            TermKey.HOME -> cursor('H')
            TermKey.END -> cursor('F')
            TermKey.PAGE_UP -> tilde(5)
            TermKey.PAGE_DOWN -> tilde(6)
        }
    }

    /** 普通字符加修饰键：Ctrl 把第一个字符换成控制字符，Alt 在前面加 ESC（只作用于第一个字符） */
    fun text(s: String, ctrl: Boolean, alt: Boolean): String {
        if (s.isEmpty() || (!ctrl && !alt)) return s
        val n = Character.charCount(s.codePointAt(0))
        var head = s.substring(0, n)
        if (ctrl && n == 1) head = ctrlChar(head[0])
        if (alt) head = ESC + head
        return head + s.substring(n)
    }

    /** Ctrl+字符（和 xterm / 系统终端一致：Ctrl+C = 0x03，Ctrl+[ = ESC，Ctrl+空格 = NUL，Ctrl+? = DEL） */
    fun ctrlChar(c: Char): String = when (c) {
        in 'a'..'z' -> (c.code - 96).toChar().toString()
        in '@'..'_' -> (c.code - 64).toChar().toString() // @ A-Z [ \ ] ^ _
        ' ', '2' -> "\u0000"
        '3' -> ESC
        '4' -> "\u001c"
        '5' -> "\u001d"
        '6' -> "\u001e"
        '7', '-', '/' -> "\u001f"
        '8', '?' -> "\u007f"
        else -> c.toString()
    }
}
