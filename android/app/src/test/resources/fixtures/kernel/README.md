按内核 lemon-paperplane-main-core/internal/ops 与 internal/api/server.go 手写的响应（Mac 版夹具没覆盖的操作）：
run.logs 分页（from/limit，firstSeq）、terminal.history、terminal.list、launch.list、remote.*、fs.browse（Windows 服务器）、
vcs.* 的标签/远程/blame/工作树/子模块/提交结果，以及终端流、日志流的 SSE 原文。字段与 omitempty 行为照 Go 结构体。
