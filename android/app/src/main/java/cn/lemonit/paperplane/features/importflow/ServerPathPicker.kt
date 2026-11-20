package cn.lemonit.paperplane.features.importflow

/**
 * 选服务器上的路径（导入、添加工具链、克隆目标、启动项添加文件等共用）。对照 Mac Features/Servers/ServerFolderPicker。
 * 打开一个全屏选择页（fs.browse 浏览、可新建文件夹），选好后回调服务器上的绝对路径。
 *
 * @param start 起始目录；为空时从用户主目录开始
 * @param files true 时选文件（否则选文件夹）
 */
object ServerPathPicker {
    fun pick(title: String, start: String? = null, files: Boolean = false, confirmText: String? = null, onPicked: (String) -> Unit) {}
}
