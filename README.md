# 柠檬纸飞机 手机版

[柠檬纸飞机](https://github.com/lemon-paperplane/lemon-paperplane-main) 的手机 / 平板客户端：连接开了「允许其他设备连接」的 Mac、Windows 桌面版，或 Linux 无头版服务器，在手机上管理服务器上的工程、运行、日志、终端、文件和版本控制。

手机版只做远程客户端，不管理手机本机。

| 平台 | 目录 | 状态 |
|---|---|---|
| Android 8.0+（手机、平板） | [`android/`](android/) | 开发中 |
| iOS / iPadOS | — | 计划中 |
| HarmonyOS | — | 计划中 |

## 添加服务器

在服务器上生成访问密钥（桌面版：设置 → 服务器 → 允许其他设备连接 → 新建访问密钥；无头版：`paperplane server key add <设备名>`），会给出连接码和二维码。手机上「添加服务器」扫码或粘贴连接码即可；服务器开了账号密码登录时也可以用账号密码，第一次连接核对证书指纹。

协议与安全说明见主仓库 [docs/remote.md](https://github.com/lemon-paperplane/lemon-paperplane-main/blob/main/docs/remote.md)。

## 编译 Android 版

需要 JDK 17 以上和 Android SDK（API 36），并把主仓库克隆到旁边（共用的图标、文案、设计变量和编辑器网页在编译时拷进来）：

```
lemon-paperplane/
  lemon-paperplane-main/      # 先在 lemon-paperplane-main-editor 下 npm ci && npm run build
  lemon-paperplane-mobile/    # 本仓库
```

```bash
cd android
./gradlew assembleDebug
```

产物在 `android/app/build/outputs/apk/debug/app-debug.apk`。架构与分工见 [android/ARCHITECTURE.md](android/ARCHITECTURE.md)。

## 许可

MIT
