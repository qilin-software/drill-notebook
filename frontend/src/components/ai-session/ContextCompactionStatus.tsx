import { ListCollapse } from 'lucide-react';
import type { ContextCompaction } from '../../lib/aiStream';
import './context-compaction-status.css';

export function ContextCompactionStatus({ state }: { state?: ContextCompaction }): JSX.Element | null {
  if (!state?.running && !state?.completed) return null;
  return <p className="context-compaction-status" role={state.running ? 'status' : undefined}>
    <ListCollapse size={14} strokeWidth={1.4} aria-hidden="true" />
    <span>{state.running ? '正在压缩上下文…' : '上下文已自动压缩'}</span>
  </p>;
}
