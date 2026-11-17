/** 以工具调用结束的一次模型响应留下的思考与过渡正文；只用于展示过程。 */
export interface ProcessSegment {
  /** 步骤结束时已有的工具活动数：显示在序号大于它的工具行之前。 */
  anchor: number;
  reasoning: string;
  text: string;
  truncated: boolean;
}

export const MAX_PROCESS_SEGMENTS = 256;
const MAX_ANCHOR = 1200;

/** 只接收已知字段；非法项丢弃，顺序按服务端给出的发生顺序保留。 */
export function readProcessSegments(value: unknown): ProcessSegment[] {
  if (!Array.isArray(value)) return [];
  const rows: ProcessSegment[] = [];
  for (const row of value) {
    if (rows.length >= MAX_PROCESS_SEGMENTS) break;
    if (!row || typeof row !== 'object' || !Number.isInteger(row.anchor) || row.anchor < 0 || row.anchor > MAX_ANCHOR) continue;
    const reasoning = typeof row.reasoning === 'string' ? row.reasoning : '';
    const text = typeof row.text === 'string' ? row.text : '';
    if (!reasoning.trim() && !text.trim()) continue;
    rows.push({ anchor: row.anchor, reasoning, text, truncated: row.truncated === true });
  }
  return rows;
}
