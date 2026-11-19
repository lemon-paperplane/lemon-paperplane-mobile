package cn.lemonit.paperplane.kit

import cn.lemonit.paperplane.kit.model.RunStats

/** 运行的资源趋势与进程明细（最近 5 分钟），对照 Mac PaperplaneKit/API/ResourceOperations.swift */
suspend fun OpCaller.runStats(runId: String): RunStats = callAs("run.stats", RunStats.serializer(), jsonObjectOf("runId" to runId))
