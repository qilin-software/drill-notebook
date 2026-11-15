# 上下文管理实现说明（v0.8.3）

> 2026-11-15。产品范围见[版本清单](../../state/v0.8.3-features.md)，预算和行为见[规格](spec.md)。本文只记录源码分工与维护边界。

## 1. 代码分工

| 模块 | 责任 |
|---|---|
| `ConversationProjection` | 按受理截止点读取原历史，核对有效检查点，合并加密步骤；管理路径不使用固定 32 轮 / Token 裁剪 |
| `ChatContextAssembler / ChatContextPlan` | 固定来源、授权、历史代数、模型视图与续接版本；组装当前输入、资料和附件 |
| `ChatContextEntryService / ChatContextEntryRepository` | 保存、读取有界加密工具步骤与续答片段，维护调用 / 结果配对、来源指纹、协议身份和容量 |
| `ContextWindowBudget` | 从最终模型窗口与实际输出分配计算硬预算、软门槛和压缩目标 |
| `AiUpstreamClient / PreparedRequest` | 完成协议转换、去除内部来源标记、冻结真实请求体与分析；发送与 HTTP 重试复用同一序列化正文 |
| `ContextCoordinator` | 校验检查点、按来源组压缩、验证摘要与回执、在内存中重建视图，预检通过后发布一次 |
| `ChatContextCheckpointRepository` | SQLite 租约、来源复核、CAS 发布与容量净增量；失效和清理不留下可用旧检查点 |
| `AiService / ChatTurnService` | 普通 / SSE / 工具 / 恢复请求接入同一准备路径；摘要请求共享停止、时间、输出和并发预算，单独记账 |
| `RequestContextAnalyzer / ModelContextSnapshot` | 继续只记录实际主请求及该次响应的窗口占用；辅助请求只进入请求明细与累计 |
| `ContextInspector` | 数值审计不含正文；显式预览 CONTINUATION / STEP 时重新核对来源与权限 |
| `AiRuntimeSettingsController / AiRuntimeSettingsRepository` | 保存上下文配置和 revision，兼容缺项旧客户端；配置继续位于便携数据库 |
| `AiRuntimeSettingsPanel / useAiChatTurn / AiAssistant` | 原运行设置中编辑开关 / 阈值，合并 SSE 与运行快照的压缩进度，沿用现有停止与上下文详情入口 |

## 2. 请求执行边界

1. 受理时冻结模型、运行设置、输入与页面快照。开始执行时获取合格历史，手动原地重试使用原轮截止点。
2. 每次普通、工具后续或断流续答请求先完成真实协议包装，实际输出分配参与窗口预算。
3. 超过软门槛时，协调器合并旧摘要和较早来源组；最新活动工具组、已确认部分回复及当前输入受保护，近期原文按剩余窗口保留。
4. 每段摘要都先校验格式、终态、允许来源与完整写回执；暂存候选不会成为持久检查点。最终请求满足硬预算、来源仍有效且运行未停止时，在短事务内 CAS 发布。
5. 冻结请求实际外发后才建立主占用快照。HTTP 重试使用同一正文；断流续答创建包含已确认步骤的新请求，不重复执行成功工具。

压缩请求关闭工具，使用本轮同一供应商、协议与模型，输出分配最多 4,096 Token，并继续受整轮剩余额度约束。其用途为 `CONTEXT_COMPACT`，不得调用替代供应商或改写主占用。

## 3. 持久化与失效

schema 22 的完整表与迁移职责见[数据模型](../../state/data-model.md#44-上下文步骤与续接检查点v22)。新步骤与检查点使用 `ChatMessageCipher`，元数据只保存身份、指纹、数字和安全状态；参数、结果及摘要正文均为密文。普通历史保持原位置，原正文不因压缩而删除。

步骤允许 OPEN → CLOSED，已闭合步骤不改写。工具写入以既有业务回执为准：先保留已确认活动，再保存上下文步骤；若后者因容量或存储失败而中断，业务成功仍保持成功，后续模型发送被阻止。压缩与恢复不会把这次成功写入重新执行。

检查点覆盖 TURN / STEP / LEGACY / ANCHOR 的真实版本与指纹。编辑、原地重试、正文清理、上下文换代和记忆授权撤回复用既有事务使相关派生状态失效；删除 / 清理步骤和检查点同步释放容量。数据库写锁不跨越模型网络调用。

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
| [Codex history.rs](https://github.com/openai/codex/blob/81b995a6c9a4c978f399775cfa5ce50702368749/codex-rs/core/src/context_manager/history.rs) | 完整历史与模型视图分开，保留工具配对和必要协议状态；本版未接原生 compaction |
| [pi 实现](https://github.com/earendil-works/pi/tree/1cedd32724abfcb0915f76cc61b6827e2c16dbad)、[压缩文档](https://github.com/earendil-works/pi/blob/f6df849c4ac3d89d68d227f73264de768232446c/packages/coding-agent/docs/compaction.md) | 近期原文与摘要组合，按窗口保留；本项目使用自身窗口预算，不照搬固定 20k 尾部 |
| [第三方 Claude Code 重建](https://github.com/claude-code-best/claude-code/tree/77a7934e15d69da13879112ed7db695c9ee7a52a) | 核对压缩互斥、工具内容管理与会话状态维护；不是 Anthropic 官方源码，不据此承诺官方行为 |

参考源码只用于阅读，没有作为应用运行依赖或执行入口。已执行的回归与真实供应商 / 实机验收边界见[本切片 QA](qa.md)。
