'use client';

import { useCallback, useEffect, useState } from 'react';
import { api } from '../../lib/api';
import { Spinner } from '../../components/Spinner';
import { SyncBadge } from '../../kb/KbManager';
import LangIndexBadge from './LangIndexBadge';
import type { KbArticle } from '../../lib/types';

// KB → RAG sync. The bulk "Sync" button drives per-article syncs sequentially (live progress, each
// row updates as it finishes); a de-emphasized per-row sync pushes a single article. The backend
// returns the updated article even on ERROR (e.g. PII quarantine), so failures surface inline.
export default function KbSyncSection() {
  const [articles, setArticles] = useState<KbArticle[] | null>(null);
  const [current, setCurrent] = useState<number | null>(null); // id being processed
  const [running, setRunning] = useState(false);

  const load = useCallback(() => {
    api.listKbArticles().then(setArticles).catch(() => setArticles([]));
  }, []);
  useEffect(() => { load(); }, [load]);

  function replace(updated: KbArticle) {
    setArticles((xs) => (xs ? xs.map((x) => (x.id === updated.id ? updated : x)) : xs));
  }

  async function syncOne(id: number) {
    setCurrent(id);
    try {
      replace(await api.syncKbArticle(id));
    } finally {
      setCurrent(null);
    }
  }

  async function syncAll() {
    if (!articles || running) return;
    setRunning(true);
    try {
      for (const a of articles.filter((x) => x.syncStatus !== 'SYNCED')) {
        setCurrent(a.id);
        try {
          replace(await api.syncKbArticle(a.id));
        } catch {
          /* network error — leave the row as-is and continue */
        }
      }
    } finally {
      setCurrent(null);
      setRunning(false);
    }
  }

  const pending = (articles ?? []).filter((a) => a.syncStatus !== 'SYNCED').length;

  return (
    <section className="mt-10">
      <div className="mb-2 flex items-center justify-between gap-2">
        <h2 className="text-sm font-semibold">KB articles</h2>
        <div className="flex items-center gap-2">
          {articles && articles.length > 0 ? (
            <a
              href={api.kbArticleDownloadAllUrl()}
              className="inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm font-medium text-zinc-600 ring-1 ring-zinc-200 hover:bg-zinc-50"
              title="Download every KB article as a .zip of Markdown files"
            >
              <DownloadIcon className="h-3.5 w-3.5" /> Download all
            </a>
          ) : null}
          <button
            onClick={syncAll}
            disabled={running || pending === 0}
            className="inline-flex items-center gap-2 rounded-lg bg-zinc-900 px-3 py-1.5 text-sm font-medium text-white disabled:opacity-40"
          >
            {running ? <Spinner className="h-4 w-4 text-white" /> : null}
            {running ? 'Syncing…' : `Sync${pending ? ` (${pending})` : ''}`}
          </button>
        </div>
      </div>
      <p className="mb-3 text-xs text-zinc-500">
        Push Knowledge Base articles into the assistant. Both languages are indexed when an article has
        a Russian translation — <span className="text-green-700">EN&nbsp;+&nbsp;RU</span> means Russian
        is in the vector store, <span className="text-amber-700">RU&nbsp;pending</span> means it still
        needs a sync. Flagged content (PII) is rejected as a whole and shown as an error below.
      </p>

      {articles === null ? (
        <div className="flex items-center gap-3 rounded-lg px-4 py-6 text-sm text-zinc-500 ring-1 ring-zinc-200">
          <Spinner className="h-5 w-5 text-zinc-400" /> Loading…
        </div>
      ) : articles.length === 0 ? (
        <p className="rounded-lg px-4 py-3 text-sm text-zinc-400 ring-1 ring-zinc-200">
          No KB articles yet — create some on the Knowledge base page.
        </p>
      ) : (
        <ul className="space-y-2">
          {articles.map((a) => (
            <li key={a.id} className="rounded-lg p-3 ring-1 ring-zinc-200">
              <div className="flex items-center justify-between gap-3">
                <div className="min-w-0">
                  <span className="text-sm font-medium text-zinc-800">{a.title}</span>
                  <span className="ml-2 text-xs text-zinc-400">{a.category}</span>
                  <div className="mt-1 flex items-center gap-1.5">
                    <SyncBadge status={a.syncStatus} />
                    <LangIndexBadge hasRu={!!a.bodyRu} synced={a.syncStatus === 'SYNCED'} />
                    {current === a.id ? <span className="text-[10px] text-zinc-400">processing…</span> : null}
                  </div>
                </div>
                <div className="flex shrink-0 items-center gap-1.5">
                  <a
                    href={api.kbArticleDownloadUrl(a.id)}
                    title="Download as Markdown"
                    className="inline-flex items-center gap-1 rounded px-2 py-1 text-xs text-zinc-500 ring-1 ring-zinc-200 hover:bg-zinc-50"
                  >
                    <DownloadIcon className="h-3 w-3" /> Download
                  </a>
                  <button
                    onClick={() => syncOne(a.id)}
                    disabled={running || current === a.id}
                    className="rounded px-2 py-1 text-xs text-zinc-500 ring-1 ring-zinc-200 disabled:opacity-50"
                  >
                    Sync
                  </button>
                </div>
              </div>
              {a.syncStatus === 'ERROR' && a.lastSyncError ? (
                <p className="mt-2 border-t border-zinc-100 pt-2 text-xs text-red-600">{a.lastSyncError}</p>
              ) : null}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function DownloadIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" /><polyline points="7 10 12 15 17 10" /><line x1="12" y1="15" x2="12" y2="3" />
    </svg>
  );
}
