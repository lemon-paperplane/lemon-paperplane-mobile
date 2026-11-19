# 柠檬纸飞机 Android 版：架构与分工

Android 客户端只连远程服务器（Mac / Windows 桌面版开了「允许其他设备连接」，或 Linux 无头版），不管理手机本机。
功能对齐 Mac 版界面（`../../lemon-paperplane-main/lemon-paperplane-main-macos/`），交互按手机习惯改。
接口约定见主仓库 `docs/remote.md`（远程协议、安全）和 `docs/api.md`（操作）。

## 1. 已定的方案（用户拍板）

- Kotlin + Jetpack Compose 原生；最低 Android 8.0（API 26），目标 API 36；包名 `cn.lemonit.paperplane`。
- 手机：底部标签栏「工程 / 启动 / 运行中 / 终端 / 更多」（更多 = 版本控制、工具链、插件、设置）；
  顶栏左边是服务器切换器（点开是底部弹出面板，样式对照 Mac 的 ServerSwitcher），右边搜索、导入；顶栏下面一行是工作区选择。
  平板横屏（宽度 ≥ 840dp）：改成桌面那样的常驻侧栏（工作区列表、运行中、工具链、插件、设置）+ 内容区顶部分段「启动 / 工程 / 版本控制 / 终端」。
- 添加服务器：扫码（连接码二维码）、粘贴连接码、账号密码（第一次连接核对证书指纹）。推送通知暂不做。
- 运行脚本后杀掉 App 再回来能看到完整日志：日志全在服务器内核里（`run.logs` 带 `from` 分页读完整日志，`terminal.history` 读终端完整输出），App 只是显示。

## 2. 和桌面的关系

桌面界面经本机内核的「远程网关」连服务器；手机上没有内核，App 自己直接连服务器：

| 桌面 | Android |
|---|---|
| 本机内核 `gateway.open` 开回环端口 | `net/RemoteClient`：OkHttp 直连 `https://服务器`，固定证书指纹（SPKI SHA-256，和 `remote.Fingerprint` 同算法）或按 CA 校验（trusted），每个请求带 `Authorization: Bearer <访问密钥>` |
| 编辑器 / 终端 / 插件网页走回环端口 | `net/LocalGateway`：App 内的回环 HTTP 服务（127.0.0.1:随机端口 + 本地令牌，规则同桌面网关）：`/editor/*` 用 APK 里的编辑器网页（assets/editor），其余转发给服务器（加密钥、去掉 token 参数、事件流不缓冲）。WebView 打开 `http://127.0.0.1:端口/editor/…?token=…` 即可，网页代码不用改 |
| `gateway.forward` 端口转发 | `net/PortForwarder`：本机回环端口 ⇄ `GET /api/v1/tunnel?port=N`（HTTP/1.1 Upgrade: paperplane-tunnel），只能在 App 内置浏览器里打开（切到后台连接会断） |
| `gateway.probe / login / decode` | `net/RemoteAuth`、`net/ConnectionCode` |
| 403 `remote_unauthorized` | 直连时服务器回 401 `unauthorized` = 密钥失效 → 进入「连不上」状态，提示重新登录；429 = 被封禁 |

## 3. 目录与分工（`app/src/main/java/cn/lemonit/paperplane/`）

| 包 | 内容 | 对照 Mac | 负责 |
|---|---|---|---|
| `kit/` | 模型（kotlinx.serialization）、全部操作的强类型调用（`OpCaller` 的扩展函数）、格式化、ANSI 解析、服务器路径、提交图几何 | `PaperplaneKit/Sources/PaperplaneKit/{Models,API/*Operations,Util,ANSI,Graph}` | 基础层「模型」 |
| `net/` | RemoteClient（实现 `OpCaller`）、证书固定、SSE 连接（事件流、日志流、终端流，断线续传）、RemoteAuth、ConnectionCode、LocalGateway、PortForwarder；另加 `core/ServerStore.kt` | `PaperplaneKit/.../API/{APIClient,SSE,Streams}`、内核 `internal/gateway` | 基础层「网络」 |
| `core/` | AppModel（当前服务器、连接状态、切到前台重连）、各 Store（StateFlow）、Actions；ServerStore（列表存 SharedPreferences，密钥用 Android Keystore AES-GCM 加密）由「网络」写 | `LemonPaperplane/Core/*` | 我（集成） |
| `ui/theme/`、`ui/icons/`、`ui/i18n/` | 设计变量（读 assets/shared/design-tokens.json，深浅色）、SVG 图标（AndroidSVG，按颜色着色缓存；插件图标的字节由调用方提供）、文案 `tr(key, args)`（读 assets/shared/i18n，`i18n/android` 覆盖） | `Design/*`、`Core/L10n.swift` | 基础层「界面」 |
| `ui/components/`、`ui/gallery/` | 按钮、徽标、状态点、卡片、分组、列表行、输入框、下拉、底部弹出面板、对话框、提示条（Toast）、空状态、加载、i 说明气泡、ANSI 文本、代码字体；调试版里的组件陈列页 | `Components/*` | 基础层「界面」 |
| `ui/shell/` | MainActivity 的框架：顶栏（服务器切换器、工作区选择、搜索、导入）、底部标签栏、平板侧栏、导航路由（各功能页的入口函数由各负责人提供） | `Features/Main/*` | 我（集成） |
| `features/servers/` | 切换器面板、添加服务器（扫码 / 连接码 / 账号密码 + 证书确认）、管理服务器、「连不上」页 | `Features/Servers/{ServerSwitcher,AddServerDialog}`、`MainView` | 我（集成） |
| `features/workspace/`、`features/runs/`、`features/launch/`、`features/importflow/` | 工程卡片列表、运行中、启动面板、运行控制台（颜色、搜索、跟随、往上翻读完整日志）、诊断与一键修复、运行地址（转发 / 服务器地址）、导入与扫描（选服务器上的文件夹） | `Features/{Workspace,Launch,Import,Resources}`、`Components/Console.swift`、`Servers/{RunURLLinks,ServerFolderPicker}` | 功能 1 |
| `features/project/`、`features/forms/`、`features/plugins/`、`features/toolchains/`、`features/settings/` | 工程详情（概览 / 运行 / 配置 / 依赖 / 插件视图）、运行配置编辑（表单渲染）、插件声明式界面渲染、插件网页、工具链、插件与商店、设置（服务器设置 +「允许其他设备连接」管理，生成密钥时显示二维码） | `Features/{Project,Plugins,Toolchains,Settings}`、`Renderers/*`、`Servers/ServerSettings` | 功能 2 |
| `features/vcs/` | 版本控制总览、改动与暂存、提交、分支、拉取推送、贮藏、冲突、历史（提交图）、差异 | `Features/VCS/*` | 功能 3 |
| `features/files/`、`features/editor/`、`features/terminal/`、`features/browser/` | 文件浏览与操作、编辑器（WebView 加载 /editor/）、终端（WebView xterm + 辅助键栏 Esc/Tab/Ctrl/方向键、中文输入、完整输出回看）、App 内置浏览器（打开端口转发地址） | `Features/{Files,Editor,Terminal}`、`Renderers/WebviewHost` | 功能 4 |

规则：只改自己那一行的包；需要改别人的包或公共部分（`kit/ net/ core/ ui/`）时改到最少并在汇报里逐条列出。

## 4. 界面约定

- 专业、无「AI 味」：图标只用 `assets/shared/icons/*.svg`（和桌面同一套），不用 emoji、不用 Material 图标；说明文字放进 i 图标点开的气泡，不在页面上堆小字。
- 颜色、字号、间距、圆角一律取设计变量，深浅色跟随系统；文案一律 `tr("key")`，键和桌面共用（`shared/i18n`），手机专属说法放 `shared/i18n/android/*.json`（只放和桌面不同的键）。
- 触控目标至少 44dp；列表长按出菜单（对应桌面右键 / ··· 菜单）；对话框优先用底部弹出面板；横向空间不够时换行或折叠，不做横向滚动页面（日志、代码除外）。
- 列表数据来自 Store（StateFlow），事件流推送后自动刷新；操作出错用提示条显示服务器给的 message。

## 5. 编译与测试

```bash
cd android
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew assembleDebug            # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest        # JVM 单元测试
```

需要旁边有 `lemon-paperplane-main`（共用资源和编辑器网页在构建时拷进 assets；编辑器网页要先 `npm ci && npm run build`）。

不用模拟器。要上机测试时由用户插真机（USB 调试），`adb install -r app-debug.apk`，截图 `adb exec-out screencap -p > x.png`，
点击 `adb shell input tap x y`，界面树 `adb shell uiautomator dump`。平时只编译和跑 JVM 单元测试（网络层可以在单元测试里连本机起的测试服务器）。

测试服务器（Mac 上起，别动默认数据目录）：

```bash
cd ../../lemon-paperplane-main/lemon-paperplane-main-core && go build -o /tmp/pp ./cmd/paperplane
PAPERPLANE_DATA_DIR=<临时目录> PAPERPLANE_NO_SHELL_ENV=1 /tmp/pp serve --listen 0.0.0.0:47391 &
PAPERPLANE_DATA_DIR=<临时目录> /tmp/pp server key add phone --address <Mac 的局域网 IP>:47391   # 打印连接码和二维码
```
