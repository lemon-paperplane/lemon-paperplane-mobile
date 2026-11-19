package cn.lemonit.paperplane.ui.theme

/** 语气（徽标、提示条、进度条、插件界面的 tone）。颜色见 [PaperColors.tone]。 */
enum class Tone {
    Neutral, Info, Success, Warning, Danger;

    companion object {
        /** 插件 / 服务器给的字符串：success | warning | danger | error | info，其余当 neutral */
        fun of(s: String?): Tone = when (s?.lowercase()) {
            "success" -> Success
            "warning" -> Warning
            "danger", "error" -> Danger
            "info" -> Info
            else -> Neutral
        }

        /** 诊断严重度：error | warning | info */
        fun ofSeverity(s: String?): Tone = when (s) {
            "error" -> Danger
            "warning" -> Warning
            else -> Info
        }
    }
}
