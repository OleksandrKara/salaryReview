'use client';

import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../../lib/api';
import { Spinner } from '../../components/Spinner';
import KbSyncSection from './KbSyncSection';
import SopSyncSection from './SopSyncSection';
import type { RagAgentConfigDto, RagDocumentSummary } from '../../lib/types';

// Owner-only admin surface for the knowledge corpus: upload documents (land PENDING), approve them
// (runs ingestion: chunk → PII/relevance gate → embed), see chunk/quarantine counts, delete, and
// tune the answering agent's config (each save is a new version).
export default function RagAdminPage() {
  const [docs, setDocs] = useState<RagDocumentSummary[] | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  const load = useCallback(() => {
    api.listRagDocuments().then(setDocs).catch(() => setDocs([]));
  }, []);
  useEffect(() => { load(); }, [load]);

  async function act(key: string, fn: () => Promise<unknown>) {
    setBusy(key);
    setError(null);
    try {
      await fn();
      load();
    } catch {
      setError('Action failed. Please try again.');
    } finally {
      setBusy(null);
    }
  }

  async function upload(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    await act('upload', () => api.uploadRagDocument(file));
    if (fileRef.current) fileRef.current.value = '';
  }

  return (
    <main className="mx-auto max-w-3xl px-4 py-10">
      <div className="mb-6 flex items-center justify-between">
        <Link href="/reports" className="text-xs text-zinc-400 hover:text-zinc-600">← Reports</Link>
        <a href="/api/logout" className="text-xs text-zinc-400 hover:text-zinc-600">Log out</a>
      </div>
      <h1 className="text-lg font-semibold">Knowledge base — admin</h1>
      <p className="mt-1 text-sm text-zinc-500">
        Upload SOPs, policies, or pricing docs (PDF, Markdown, or text). Uploads land pending; review,
        then approve to index. A per-chunk PII/relevance check runs before anything is embedded.
      </p>

      <div className="mt-5 flex items-center gap-3">
        <input
          ref={fileRef}
          type="file"
          accept=".pdf,.md,.markdown,.txt"
          onChange={upload}
          disabled={busy === 'upload'}
          className="text-sm"
        />
        {busy === 'upload' ? <Spinner className="h-4 w-4 text-zinc-400" /> : null}
      </div>

      {error ? (
        <p className="mt-4 rounded-lg px-4 py-2 text-sm text-red-600 ring-1 ring-red-200">{error}</p>
      ) : null}

      <section className="mt-8">
        <h2 className="mb-2 text-sm font-semibold">Documents</h2>
        {docs === null ? (
          <div className="flex items-center gap-3 rounded-lg px-4 py-6 text-sm text-zinc-500 ring-1 ring-zinc-200">
            <Spinner className="h-5 w-5 text-zinc-400" /> Loading…
          </div>
        ) : docs.length === 0 ? (
          <p className="rounded-lg px-4 py-3 text-sm text-zinc-400 ring-1 ring-zinc-200">No documents yet.</p>
        ) : (
          <>
            {/* Mobile: one card per document (the table doesn't fit a phone) */}
            <ul className="space-y-2 sm:hidden">
              {docs.map((d) => (
                <li key={d.id} className="rounded-lg p-3 ring-1 ring-zinc-200">
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0">
                      <div className="break-words text-sm font-medium text-zinc-800">{d.filename}</div>
                      <div className="text-xs text-zinc-400">{d.sourceType}</div>
                    </div>
                    <StatusChip status={d.status} />
                  </div>
                  {d.status === 'FAILED' && d.statusDetail ? (
                    <div className="mt-1 text-xs text-red-500">{d.statusDetail}</div>
                  ) : null}
                  <div className="mt-2 flex gap-4 text-xs text-zinc-500">
                    <span>Indexed <span className="tabular-nums text-zinc-700">{d.indexedChunks}</span></span>
                    <span>Quarantined <span className="tabular-nums text-zinc-700">{d.quarantinedChunks}</span></span>
                  </div>
                  <div className="mt-3 flex items-center gap-2">
                    <DocActions d={d} busy={busy} act={act} />
                  </div>
                </li>
              ))}
            </ul>

            {/* Desktop: table */}
            <div className="hidden overflow-x-auto rounded-lg ring-1 ring-zinc-200 sm:block">
              <table className="w-full text-sm">
                <thead className="bg-zinc-50 text-left text-xs uppercase tracking-wide text-zinc-500">
                  <tr>
                    <th className="px-3 py-2">File</th>
                    <th className="px-3 py-2">Status</th>
                    <th className="px-3 py-2">Indexed</th>
                    <th className="px-3 py-2">Quarantined</th>
                    <th className="px-3 py-2"></th>
                  </tr>
                </thead>
                <tbody>
                  {docs.map((d) => (
                    <tr key={d.id} className="border-t border-zinc-100">
                      <td className="px-3 py-2">
                        <span className="font-medium text-zinc-800">{d.filename}</span>
                        <span className="ml-1 text-xs text-zinc-400">({d.sourceType})</span>
                        {d.status === 'FAILED' && d.statusDetail ? (
                          <div className="text-xs text-red-500">{d.statusDetail}</div>
                        ) : null}
                      </td>
                      <td className="px-3 py-2">
                        <StatusChip status={d.status} />
                      </td>
                      <td className="px-3 py-2 tabular-nums">{d.indexedChunks}</td>
                      <td className="px-3 py-2 tabular-nums">{d.quarantinedChunks}</td>
                      <td className="px-3 py-2">
                        <div className="flex justify-end gap-2">
                          <DocActions d={d} busy={busy} act={act} />
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </section>

      <KbSyncSection />

      <SopSyncSection />

      <ConfigEditor />
    </main>
  );
}

function StatusChip({ status }: { status: RagDocumentSummary['status'] }) {
  const styles: Record<RagDocumentSummary['status'], string> = {
    PENDING: 'bg-amber-50 text-amber-700',
    INDEXING: 'bg-blue-50 text-blue-700',
    INDEXED: 'bg-green-50 text-green-700',
    QUARANTINED: 'bg-zinc-100 text-zinc-600',
    FAILED: 'bg-red-50 text-red-700',
  };
  return <span className={`rounded px-2 py-0.5 text-xs ${styles[status]}`}>{status}</span>;
}

// Approve (when PENDING) + Delete — shared by the mobile cards and the desktop table.
function DocActions({
  d,
  busy,
  act,
}: {
  d: RagDocumentSummary;
  busy: string | null;
  act: (key: string, fn: () => Promise<unknown>) => void;
}) {
  return (
    <>
      {d.status === 'PENDING' ? (
        <button
          onClick={() => act(`approve-${d.id}`, () => api.approveRagDocument(d.id))}
          disabled={busy === `approve-${d.id}`}
          className="rounded px-2 py-1 text-xs ring-1 ring-zinc-200 disabled:opacity-50"
        >
          {busy === `approve-${d.id}` ? 'Indexing…' : 'Approve'}
        </button>
      ) : null}
      <button
        onClick={() => act(`delete-${d.id}`, () => api.deleteRagDocument(d.id))}
        disabled={busy === `delete-${d.id}`}
        className="rounded px-2 py-1 text-xs text-red-600 ring-1 ring-red-200 disabled:opacity-50"
      >
        Delete
      </button>
    </>
  );
}

// Agent config editor — load the active version, edit, save as a new version.
function ConfigEditor() {
  const [cfg, setCfg] = useState<RagAgentConfigDto | null>(null);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    api.getRagConfig().then(setCfg).catch(() => setCfg(null));
  }, []);

  async function save() {
    if (!cfg) return;
    setSaving(true);
    setSaved(false);
    try {
      const next = await api.updateRagConfig({
        systemPrompt: cfg.systemPrompt,
        model: cfg.model,
        temperature: cfg.temperature,
        k: cfg.k,
        distanceThreshold: cfg.distanceThreshold,
      });
      setCfg(next);
      setSaved(true);
    } finally {
      setSaving(false);
    }
  }

  if (!cfg) return null;

  return (
    <section className="mt-10">
      <h2 className="mb-2 text-sm font-semibold">
        Agent config <span className="text-xs font-normal text-zinc-400">(v{cfg.version})</span>
      </h2>
      <div className="space-y-3 rounded-lg p-4 ring-1 ring-zinc-200">
        <label className="block text-xs font-medium text-zinc-600">
          System prompt
          <textarea
            value={cfg.systemPrompt}
            onChange={(e) => setCfg({ ...cfg, systemPrompt: e.target.value })}
            rows={4}
            className="mt-1 w-full resize-none rounded px-2 py-1 text-sm ring-1 ring-zinc-200"
          />
        </label>
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <NumField label="Model" text value={cfg.model} onChange={(v) => setCfg({ ...cfg, model: v })} />
          <NumField label="Temperature" value={cfg.temperature} onChange={(v) => setCfg({ ...cfg, temperature: Number(v) })} />
          <NumField label="Top-k" value={cfg.k} onChange={(v) => setCfg({ ...cfg, k: Number(v) })} />
          <NumField label="Distance floor" value={cfg.distanceThreshold} onChange={(v) => setCfg({ ...cfg, distanceThreshold: Number(v) })} />
        </div>
        <p className="text-xs text-zinc-400">
          Temperature applies on Haiku/Sonnet models only; it is rejected on Opus 4.7+.
        </p>
        <button
          onClick={save}
          disabled={saving}
          className="inline-flex items-center gap-2 rounded-lg bg-zinc-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-40"
        >
          {saving ? <Spinner className="h-4 w-4 text-white" /> : null}
          Save as new version
        </button>
        {saved ? <span className="ml-2 text-xs text-green-600">Saved.</span> : null}
      </div>
    </section>
  );
}

function NumField({
  label,
  value,
  onChange,
  text,
}: {
  label: string;
  value: string | number;
  onChange: (v: string) => void;
  text?: boolean;
}) {
  return (
    <label className="block text-xs font-medium text-zinc-600">
      {label}
      <input
        type={text ? 'text' : 'number'}
        step="any"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="mt-1 w-full rounded px-2 py-1 text-sm tabular-nums ring-1 ring-zinc-200"
      />
    </label>
  );
}
