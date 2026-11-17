# 实时流式、过程折叠与缓存口径执行计划（v0.8.3 F03 / F04）

> 2026-11-18。依据 [UI/UX](ui-ux.md) 与[规格](spec.md)。按序执行，每步只改列出的文件；状态见末尾。

## 步骤

| # | 内容 | 文件 | 验证 |
|---|---|---|---|
| 1 | 过程步骤模型：字段校验、上限追加、元数据读取 | `backend/.../model/ProcessSegment.java` | `ProcessSegmentTest` |
| 2 | 句柄保存步骤；检查点、终态、重试沿用、状态与读取出口 | `ActiveTurnRegistry`、`ChatTurnService`、`ChatHistoryService` | 步骤 4 的路径测试 |
| 3 | 取消缓冲；工具响应结束时记步骤并发 `process`；结果视图带步骤 | `AiService` | 同上 |
| 4 | 控制器与托管流识别 `process`，运行态重置与重订阅补发 | `AiController`、`SessionDispatchService` | 工具路径与托管流测试 |
| 5 | 缓存推断与未命中字段 | `ChatUsageRepository`、`AiUpstreamClient` | 用量仓库与上游用量测试 |
| 6 | 前端解析与事件 | `lib/processSegment.ts`、`lib/aiStream.ts`、`hooks/useAiChatTurn.ts`、`hooks/useChatHistoryPages.ts`、`lib/types.ts` | 对应单元测试 |
| 7 | 消息状态与渲染顺序、过程内插入、样式 | `components/AiAssistant.tsx`、`components/ai-memory/ToolActivityGroup.tsx`、对应 CSS | 组件测试 |
| 8 | 相关前后端回归、TypeScript / Vite 构建、便携审计 | — | 结果记入本文件 |
| 9 | 同步接口契约、版本清单与交接记忆 | `docs/state/api-contracts.md`、`docs/state/v0.8.3-features.md`、根 `MEMORY.md` | — |

## 约束

- 不新增表、列或设置项；不改发给模型的消息与工具回执。
- 不提交、不推送、不改发行配置。
- 临时材料放根 `temp/`。

## 状态

2026-11-18 步骤 1–9 已完成。

- 后端相关 38 类 / 539 项通过，0 失败 / 错误 / 跳过；前端完整回归 1,064 项通过、4 项原有条件跳过。
- TypeScript / Vite 构建、隔离 JAR 打包、健康启动、配置重启读回、运行时便携审计与静态注册表审计通过；727 个应用 class 与 5 个资源匹配被测产物。
- 开发 JAR 与前端构建已同步，下次启动生效；旧产物、隔离 POM 和脚本在 `temp/live-stream-20261118/`。
- 待验收：真实供应商下的逐字节奏、过程折叠的实机观感。托管流重新订阅补发 `process` 帧没有独立用例；未重跑后端全量。
