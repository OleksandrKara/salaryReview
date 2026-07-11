import type { KbArticle } from '../lib/types';

// No hooks/state — a plain presentational badge, kept in its own (non-'use client') file so the
// standalone article page (/kb/[id], a server component) can render it without pulling in
// KbManager's whole client bundle (the markdown editor, etc.) just for this.
export function SyncBadge({ status }: { status: KbArticle['syncStatus'] }) {
  const map: Record<KbArticle['syncStatus'], [string, string]> = {
    NOT_SYNCED: ['Not synced', 'bg-zinc-100 text-zinc-600'],
    SYNCED: ['Synced', 'bg-green-50 text-green-700'],
    CHANGED: ['Update available', 'bg-amber-50 text-amber-700'],
    ERROR: ['Error', 'bg-red-50 text-red-700'],
  };
  const [label, cls] = map[status];
  return <span className={`rounded px-1.5 py-0.5 text-[10px] ${cls}`}>{label}</span>;
}
