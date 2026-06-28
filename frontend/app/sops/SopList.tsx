'use client';

import dynamic from 'next/dynamic';
import { useState } from 'react';
import { api } from '../lib/api';
import { Spinner } from '../components/Spinner';
import type { Role, Sop } from '../lib/types';

const Markdown = dynamic(
  () => import('@uiw/react-md-editor').then((m) => ({ default: m.default.Markdown })),
  { ssr: false },
);

const fmt = (iso: string | null) =>
  iso ? new Date(iso).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' }) : '';

// Shared read + acknowledge list for managers/providers (owners view it read-only). The acknowledge
// button arms only after the SOP's content has been opened this session — a light UX gate.
export default function SopList({ role, initialSops }: { role: Role; initialSops: Sop[] }) {
  const [sops, setSops] = useState<Sop[]>(initialSops);
  const [openId, setOpenId] = useState<number | null>(null);
  const [viewed, setViewed] = useState<Set<number>>(new Set());
  const [busy, setBusy] = useState<number | null>(null);
  const canAck = role === 'MANAGER' || role === 'PROVIDER';

  function toggle(s: Sop) {
    setOpenId((cur) => (cur === s.id ? null : s.id));
    setViewed((v) => new Set(v).add(s.id));
  }

  async function acknowledge(s: Sop) {
    setBusy(s.id);
    try {
      const updated = await api.acknowledgeSop(s.id);
      setSops((xs) => xs.map((x) => (x.id === updated.id ? updated : x)));
    } finally {
      setBusy(null);
    }
  }

  if (sops.length === 0) {
    return <p className="mt-6 rounded-lg px-4 py-3 text-sm text-zinc-400 ring-1 ring-zinc-200">No SOPs yet.</p>;
  }

  return (
    <ul className="mt-6 space-y-2">
      {sops.map((s) => (
        <li key={s.id} className="rounded-lg p-3 ring-1 ring-zinc-200">
          <div className="flex items-start justify-between gap-3">
            <button className="text-left" onClick={() => toggle(s)}>
              <span className="text-sm font-medium text-zinc-800">{s.title}</span>
              <span className="ml-2 text-xs text-zinc-400">
                {s.category} · v{s.currentVersion?.versionNumber ?? '—'}
              </span>
            </button>
            <div className="shrink-0">
              {s.acknowledged ? (
                <span className="rounded bg-green-50 px-2 py-1 text-xs text-green-700">
                  ✅ Acknowledged {fmt(s.acknowledgedAt)}
                </span>
              ) : canAck ? (
                <button
                  onClick={() => acknowledge(s)}
                  disabled={busy === s.id || !viewed.has(s.id)}
                  title={viewed.has(s.id) ? '' : 'Open the SOP first'}
                  className="inline-flex items-center gap-1.5 rounded-lg bg-zinc-900 px-3 py-1.5 text-xs font-medium text-white disabled:opacity-40"
                >
                  {busy === s.id ? <Spinner className="h-3.5 w-3.5 text-white" /> : null}
                  I have read and agree to follow this SOP
                </button>
              ) : null}
            </div>
          </div>
          {openId === s.id ? (
            <div data-color-mode="light" className="mt-3 border-t border-zinc-100 pt-3 text-sm">
              <Markdown source={s.currentVersion?.body || '_(no content)_'} />
            </div>
          ) : null}
        </li>
      ))}
    </ul>
  );
}
