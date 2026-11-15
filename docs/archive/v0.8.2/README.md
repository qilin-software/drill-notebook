# v0.8.2 文档归档

2026-11-15，按用户要求在开始 v0.8.3 前，将[本版功能清单](features.md)、三个施工目录及交互草图移入本目录，共 16 份原有文件。归档保留实现、调查、验证结果及未验收范围，不改写历史结论。

| 切片 | 归档材料 |
|---|---|
| 笔记混合检索接入资料工具 | [需求](material-hybrid-search/requirements.md)、[UI/UX](material-hybrid-search/ui-ux.md)、[规格](material-hybrid-search/spec.md)、[实施与验证](material-hybrid-search/plan.md) |
| 联网搜索与网页读取 | [需求](web-search/requirements.md)、[UI/UX](web-search/ui-ux.md)、[规格](web-search/spec.md)、[实施](web-search/plan.md)、[QA](web-search/qa.md)、[工具设置草图](web-search/settings-preview.html)、[助手草图](web-search/assistant-preview.html) |
| 会话交互、原地重试、独立选模与上下文统计 | [UI/UX](ai-conversation/ui-ux.md)、[规格](ai-conversation/spec.md)、[实施与验证](ai-conversation/plan.md)、[会话草图](ai-conversation/conversation-preview.html) |

`ai-conversation/` 沿用了 v0.8.1 的施工文档，包含失败重连、会话跳转及 v0.8.2 后续修正。本次按最后施工版本统一收纳，原 v0.8.1 记录和引用保留，不复制为第二份事实来源；[v0.8.1 清单](../../state/v0.8.1-features.md)继续作为历史入口。

本目录只作历史参考。文档里的「当前」、尚未发布与未验收均为当时状态；真实供应商 Token 对账、持钥 / 原生联网账户、Electron 实机和完整发行验收等边界按原记录保留。归档不代表确认 GitHub 已发布。

长期[架构](../../state/architecture.md)、[数据模型](../../state/data-model.md)、[接口](../../state/api-contracts.md)原位维护；[发布说明](../../release/v0.8.2-release-notes.md)留在 `docs/release/`，[测试清单](../../test/QA_CHECKLIST.md)留在 `docs/test/`。本地审计、截图和隔离测试产物继续留在忽略目录。

当前开发转入 [v0.8.3](../../state/v0.8.3-features.md)：先建立统一上下文管理机制，再实现窗口阈值压缩；[机制设计](../../work/ai-context-management/spec.md)为待审阅方案。
