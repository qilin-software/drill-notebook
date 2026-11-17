# 上下文管理实现说明（v0.8.3）

> 2026-11-17。产品范围见[版本清单](../../state/v0.8.3-features.md)，预算和行为见[规格](spec.md)。本文记录源码分工、有界恢复、原话 / 回执保护、长历史读取和发送收尾的维护边界。

## 1. 代码分工

| 模块 | 责任 |
|---|---|
| `ConversationProjection` | 按受理截止点读取原历史，核对有效检查点，合并加密步骤；管理路径不使用固定 32 轮 / Token 裁剪 |
| `ChatContextAssembler / ChatContextPlan` | 固定来源、授权、历史代数、模型视图与续接版本；组装当前输入、资料和附件 |
| `ChatContextService.matchesForDispatch` | 发送前复核区分实际版本 / 授权变化与本地读取异常；后者返回 CONTEXT_STORAGE_FAILED，工具轻量复核保持原行为 |
| `ChatContextEntryService / ChatContextEntryRepository` | 保存、读取有界加密工具步骤与续答片段，维护调用 / 结果配对、来源指纹、协议身份和容量 |
| `ContextWindowBudget` | 从最终模型窗口与实际输出分配计算硬预算、软门槛和压缩目标 |
| `AiUpstreamClient / PreparedRequest` | 完成协议转换、去除内部来源标记、冻结真实请求体与分析；发送与 HTTP 重试复用同一序列化正文 |
| `ContextCoordinator` | 校验检查点、按来源组或完整有序分片压缩、验证摘要与回执；处理供应商窗口超限，保留完整可用候选，预检通过后发布一次 |
| `ContextEvidence / ContextRetainedState` | 从明确标记的用户正文保护关键原句；从确认工具结果维护完整回执，模型只接收有界操作状态；与覆盖一起加密和失效 |
| `AiCompactionBudget` | 限定摘要准备 / 单次请求时间，为最终回答保留输出和时间；分配检查不提前预留主请求额度 |
| `ChatContextCheckpointRepository` | SQLite 租约、来源复核、CAS 发布与容量净增量；失效和清理不留下可用旧检查点 |
| `AiService / ChatTurnService` | 普通 / SSE / 工具 / 恢复请求接入同一准备路径；摘要冻结重试独立于主重试，共享整轮预算并单独记账；检查点成功后保存完成进度 |
| `ContextCompaction / ActiveTurnRegistry / ChatHistoryService / SessionDispatchService` | 区分 running 与 completed；运行快照、轮次终态和历史载入恢复同一成功事实 |
| `AiRequestDiagnostics / ChatTurnService.Failure` | 保存失败时的阶段与主 / 摘要请求到达 HTTP 发送边界的事实；明确未发主请求，旧记录缺项保持未知 |
| `RequestContextAnalyzer / ModelContextSnapshot` | 继续只记录实际主请求及该次响应的窗口占用；辅助请求只进入请求明细与累计 |
| `SessionContextOccupancyService` | 跳过没有主请求快照的准备轮次；仅允许未修改的 begin / end 追加保留旧快照，历史修改或较新实际请求不可读仍返回未知 |
| `ContextInspector` | 数值审计不含正文；显式预览 CONTINUATION / STEP 时重新核对来源与权限 |
| `AiRuntimeSettingsController / AiRuntimeSettingsRepository` | 保存上下文配置和 revision，兼容缺项旧客户端；配置继续位于便携数据库 |
| `AiRuntimeSettingsPanel / useAiChatTurn / AiAssistant / ContextCompactionStatus` | 原运行设置中编辑开关 / 阈值，合并 SSE、运行快照与历史完成状态；显示灰色压缩中提示及持久完成标识，沿用现有停止与上下文详情入口 |

## 2. 请求执行边界

1. 受理时冻结模型、运行设置、输入与页面快照。开始执行时获取合格历史，手动原地重试使用原轮截止点。
2. 每次普通、工具后续或断流续答请求先完成真实协议包装，预计输出参与窗口预检，此时不预留主请求输出额度。
3. 超过软门槛时，协调器合并旧摘要和较早来源组；大组按角色、序号及偏移完整分片，全部必要片段成功后才覆盖该来源。最新活动工具组、已确认部分回复及当前输入受保护，近期原文按剩余窗口保留。
4. 每段摘要都先校验格式、终态、允许来源及空回执字段，再合并程序提取的用户原话和写入回执；暂存候选不会成为持久检查点。后续可选压缩失败可保留完整且满足硬预算的候选；停止、来源变化或存储异常不能借此发布。最终在短事务内 CAS 发布，再保存完成进度。
5. 摘要结束后重新计算剩余主输出、包装并预检最终请求，再预留实际额度。等待上游并发槽后，在 HTTP 发送边界重新核对来源和取消状态；HTTP 客户端接受请求后才登记用量和主占用快照。HTTP 重试使用同一正文；断流续答创建包含已确认步骤的新请求，不重复执行成功工具。

压缩请求关闭工具，使用本轮同一供应商、协议与模型，输出分配最多 4,096 Token，并继续受整轮剩余额度约束。其用途为 `CONTEXT_COMPACT`，不得调用替代供应商或改写主占用。

`runCompaction` 单独持有 `AiRetryPolicy`，摘要断流 / 网络失败只重发该冻结摘要请求，不冒泡到主回答重试循环。`AiCompactionBudget` 对一次准备限制总共 120 秒、单次最多 60 秒，并保留最终回答输出及 5–60 秒时间；实际取整轮剩余预算内更小的值。供应商窗口超限交给协调器减少批次，分片最多 3 次按约 2/3 缩小输入，重新从尚未完成的偏移处理。合法回退后 `AiService` 清除过时的摘要 recovery，硬失败继续保留具体错误。

`ContextCoordinator.callSummary` 对 `CONTEXT_SUMMARY_INVALID` 最多修复一次。重建请求仍使用相同完整来源，增强首条格式指令，不携带失败摘要正文；重新核对硬窗口和剩余输出，调用前后校验停止及来源状态。该修复与网络层冻结重试分别受既有工作上限约束，不能绕过总时间 / 输出预算。

压缩专用的 `memoryBoundary / anchor` 延迟到确需压缩时读取。循环内的 SQL 异常进入既有合法候选 / 原视图回退；发布阶段失败只能恢复来源仍有效、满足硬预算的完整原视图，不发送未发布摘要。不能回退的本地读写失败返回 503 / CONTEXT_STORAGE_FAILED，并保留脱敏原因。回退后结束压缩运行状态，未成功发布不记 completed。

`AiService` 在普通和 SSE 异常出口统一调用 `ChatTurnService.abandon(lease, reason, error)`；准备函数不再提前重复收尾。`Failure` 校验错误码、复用上游脱敏 / 限长，再加密保存到现有 result metadata，空失败回答也创建助手消息身份。状态查询和助手历史恢复该错误，清理后不返回。前端 `settleOrDropAssistant` 将报错视为可展示内容；非流式备用通道错误也查询同一请求身份，只有确认 `TURN_NOT_FOUND` 且没有受理 / 输出事实的重试才恢复原回答。

`ActiveTurnRegistry.Handle` 保存不可变的 `AiRequestDiagnostics` 快照；阶段切换保留同轮既有主 / 摘要请求尝试标记，重试不清零。传输层在 HTTP 调用边界记录尝试，不把它当作供应商接收确认。`runTurn` 捕获有部分正文的致命异常后也保存 `Failure`，`chatResponse` 和 `StreamFailure` 返回同一份安全错误。前端只透传合法诊断字段，沿用原错误行和重新生成入口；未知阶段不使旧错误失效，不新增展示控件。

`AiUpstreamClient.callPrepared` 分离 `beforeSend` 复核与 `onSend` 观测；缓存兼容重发也执行复核。地址 / 请求构建失败、发送前取消和来源失效不创建已发送用量，不覆盖上一窗口；回调失败清理 deadline、传输与取消钩子。`Dispatch` 单独保存最近实际发送的计划，结果不把未发送的新计划标成 sent。前端保留最终 `failure`，有 recovery 时沿用重试区显示最终错误并保留次数，不让旧上游报错遮住本地失败。

主请求窗口拒绝走独立的有界恢复分支，最多两次将输入目标收紧至上次的 75%；普通重试继续复用冻结正文。SSE 的空输出拒绝与 HTTP 拒绝都可恢复，有部分输出、未确认插话、取消或设置关闭时不自动重建。执行预算释放没有输出和用量的窗口拒绝预留，供应商用量仍保留未知。

摘要截断或格式无效时，在同一完整来源上最多重做一次；截断重做要求简短且完整，输出不会超过模型配置、4,096 或剩余额度。持续截断转完整分片，输入按 2/3 减量，最多三次，保留原始失败原因；不发布半份 JSON。共用整轮输出、每次准备 120 秒与 32 次调用预算。

## 3. 持久化与失效

schema 22 的完整表与迁移职责见[数据模型](../../state/data-model.md#44-上下文步骤与续接检查点v22)。新步骤与检查点使用 `ChatMessageCipher`，元数据只保存身份、指纹、数字和安全状态；参数、结果及摘要正文均为密文。普通历史保持原位置，原正文不因压缩而删除。

检查点 continuation-v2 的加密 payload schema 为 2，增加 retained.userExcerpts / receipts；旧策略从原历史重建。完整回执独立于模型摘要，最多 65,536 条且受检查点 16 MiB 与会话容量限制；模型操作视图只含分类计数与最近八条。用户原话用来源、消息序号和完整文本保存，规则识别范围与硬上限见规格；不是通用语义分类器，不能把引用内容升级成用户授权。ContextBudgeter 标记真实用户正文，协议序列化和摘要输入去除该内部标记。

ConversationProjection 先分页读取身份，再按批取未覆盖正文并校验前后版本一致；工具步骤先看身份 / 字节，再取未覆盖密文。检查点 TURN / STEP 验证批量读取元数据，revision 查询不再反复读取整个加密 payload。覆盖支持 65,536 个精确来源，不用最大轮次推断中间全部覆盖；正文扫描和元数据扫描各有独立保护。LEGACY 的原内容指纹验证继续保留。

步骤允许 OPEN → CLOSED，已闭合步骤不改写。工具写入以既有业务回执为准：先保留已确认活动，再保存上下文步骤；若后者因容量或存储失败而中断，业务成功仍保持成功，后续模型发送被阻止。压缩与恢复不会把这次成功写入重新执行。

`ContextCompaction` 的 `running` 是内存进度，`completed` 是已发布检查点的成功事实；`settled()` 只清除 running。完成进度写入现有加密 result metadata，不新增表或迁移。普通响应、SSE、状态查询、派发快照和历史助手消息统一返回该状态；旧记录缺少 completed 时按未完成处理。前端在运行时显示「正在压缩上下文…」，成功后保留「上下文已自动压缩」，不会在 done 或重新加载历史时抹去。

检查点覆盖 TURN / STEP / LEGACY / ANCHOR 的真实版本与指纹。编辑、原地重试、正文清理、上下文换代和记忆授权撤回复用既有事务使相关派生状态失效；删除 / 清理步骤和检查点同步释放容量。数据库写锁不跨越模型网络调用。

主窗口统计保持最后实际请求语义。后续准备轮次没有 CHAT 请求和快照时继续查找上一份有效快照；其后的未修改运行轮次 `source_revision=1` 解释一次 begin 增量，终态 `source_revision=2` 解释 begin / end 两次增量。其它版本差、清理或换代不放宽；较新实际 CHAT 请求或损坏快照构成阻断点，不能越过它借用更早数值。

主占用使用发送计划冻结的 generation / historyRevision；收尾只增加自身终态事务的一次版本增量，不读取最新历史版本来替旧请求换版本。带 `modelContext` 的新格式记录若校验失败，不进入缺少版本信息的 legacy 用量回退。

记忆敏感步骤在权限或 vault 边界改变后不再外发。确认遗忘时使相关摘要失效；后续投影恢复合格原历史，不能继续把遗忘内容藏在摘要中。此处理不会扣减已发生的供应商用量。

## 4. 与长期记忆的分工

新上下文续接独立于记忆启用、自动提取及使用权限；其中涉及记忆的内容仍受原授权限制。管理路径不读取旧 vault 摘要来代替新检查点，也不自动把压缩结果写成长期记忆。

`MemoryMaintenanceWorker / MaintenanceQueue` 在管理路径取消旧 SUMMARY 作业，只维护记忆提取；旧表和兼容夹具继续保留原行为。会话续接只有 `ContextCoordinator` 一个发布者。

## 5. 维护要求

- 新增模型请求或修改协议包装时，必须走最终请求预检；不能在冻结后再追加工具、图片、推理状态或其它输入。
- 新增工具结果保存时，继续使用加密步骤和同一容量账本；安全活动与数值用量不得附带原始参数或结果。
- 调整压缩来源时，同步维护来源指纹、覆盖和回执校验；不能仅凭轮次水位宣布所有记录已覆盖。
- 实测用量缺失仍为未知；估算器可以做窗口预检，不能填补供应商未回报的累计数字。
- 新功能写盘仍经 `PortablePathResolver`，测试 / 开发材料仍放根 `temp/`。正式发行配置本轮不变。

## 6. 已核对的参考代码

| 参考 | 采用的机制与边界 |
|---|---|
| [Codex history.rs](https://github.com/openai/codex/blob/81b995a6c9a4c978f399775cfa5ce50702368749/codex-rs/core/src/context_manager/history.rs)、[compact.rs](https://github.com/openai/codex/blob/81b995a6c9a4c978f399775cfa5ce50702368749/codex-rs/core/src/compact.rs) | 完整历史与模型视图分开，保留工具配对；参考独立压缩重试、窗口超限后减量与开始 / 完成状态。本版未接原生 compaction |
| [pi agent-session.ts](https://github.com/earendil-works/pi/blob/1cedd32724abfcb0915f76cc61b6827e2c16dbad/packages/coding-agent/src/core/agent-session.ts)、[压缩文档](https://github.com/earendil-works/pi/blob/f6df849c4ac3d89d68d227f73264de768232446c/packages/coding-agent/docs/compaction.md) | 近期原文与摘要组合，压缩与重试的生命周期明确，结束后续接原任务；本项目使用自身窗口预算，不照搬固定 20k 尾部 |
| [第三方 Claude Code 重建](https://github.com/claude-code-best/claude-code/tree/77a7934e15d69da13879112ed7db695c9ee7a52a/src/services/compact) | 核对 compact / autoCompact / reactiveCompact 的互斥、减量恢复与会话状态维护；不是 Anthropic 官方源码，不据此承诺官方行为 |

上述机制适配到本项目 Java 请求、用量、来源与加密边界，没有直接加入参考源码依赖或执行入口。已执行的回归与真实供应商 / 实机验收边界见[本切片 QA](qa.md)。
