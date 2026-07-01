'use client';

import dynamic from 'next/dynamic';
import { useState } from 'react';
import { api } from '../lib/api';
import { Spinner } from '../components/Spinner';
import { t } from '../lib/i18n';
import type { Language, Role, Sop, SopVersion } from '../lib/types';

const Markdown = dynamic(
  () => import('@uiw/react-md-editor').then((m) => ({ default: m.default.Markdown })),
  { ssr: false },
);

const fmt = (iso: string | null, language: Language | null) =>
  iso ? new Date(iso).toLocaleDateString(language === 'RU' ? 'ru-RU' : 'en-US', { month: 'short', day: 'numeric', year: 'numeric' }) : '';

// Shared read + acknowledge list for managers/providers (owners view it read-only). The acknowledge
// button arms only after the SOP's content has been opened this session — a light UX gate.
export default function SopList({
  role,
  language,
  initialSops,
}: {
  role: Role;
  language: Language | null;
  initialSops: Sop[];
}) {
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
    return <p className="mt-6 rounded-lg px-4 py-3 text-sm text-zinc-400 ring-1 ring-zinc-200">{t(language, 'sopNone')}</p>;
  }

  return (
    <ul className="mt-6 space-y-2">
      {sops.map((s, i) => (
        <li key={s.id} className="rounded-lg p-3 ring-1 ring-zinc-200">
          <div className="flex items-start justify-between gap-3">
            <button className="flex items-start gap-2 text-left" onClick={() => toggle(s)}>
              <span
                className={`mt-0.5 inline-flex h-5 w-5 shrink-0 items-center justify-center rounded-full text-[11px] font-semibold tabular-nums ${
                  s.acknowledged ? 'bg-green-100 text-green-700' : 'bg-zinc-800 text-white'
                }`}
                aria-hidden
              >
                {i + 1}
              </span>
              <span>
                <span className="text-sm font-medium text-zinc-800">{s.title}</span>
                <span className="ml-2 text-xs text-zinc-400">
                  {s.category} · v{s.currentVersion?.versionNumber ?? '—'}
                </span>
              </span>
            </button>
            <div className="shrink-0">
              {s.acknowledged ? (
                <span className="rounded bg-green-50 px-2 py-1 text-xs text-green-700">
                  ✅ {t(language, 'sopAcknowledged')} {fmt(s.acknowledgedAt, language)}
                </span>
              ) : canAck ? (
                <button
                  onClick={() => acknowledge(s)}
                  disabled={busy === s.id || !viewed.has(s.id)}
                  title={viewed.has(s.id) ? '' : t(language, 'sopOpenFirst')}
                  className="inline-flex items-center gap-1.5 rounded-lg bg-zinc-900 px-3 py-1.5 text-xs font-medium text-white disabled:opacity-40"
                >
                  {busy === s.id ? <Spinner className="h-3.5 w-3.5 text-white" /> : null}
                  {t(language, 'sopAckButton')}
                </button>
              ) : null}
            </div>
          </div>
          {openId === s.id ? (
            <div className="mt-3 border-t border-zinc-100 pt-3">
              <ReaderBody version={s.currentVersion} defaultLang={language ?? 'EN'} />
            </div>
          ) : null}
        </li>
      ))}
    </ul>
  );
}

// SOP content with a per-SOP EN/RU toggle. Defaults to the reader's preferred language, falling back
// to English when there's no Russian; the toggle only appears when a translation exists.
function ReaderBody({ version, defaultLang }: { version: SopVersion | null; defaultLang: Language }) {
  const hasRu = !!(version?.bodyRu && version.bodyRu.trim());
  const [lang, setLang] = useState<Language>(defaultLang === 'RU' && hasRu ? 'RU' : 'EN');
  const content = lang === 'RU' && hasRu ? version!.bodyRu! : version?.body;
  const pill = (l: Language) =>
    `text-xs ${lang === l ? 'font-semibold text-zinc-700' : 'text-zinc-400 hover:text-zinc-600'}`;

  return (
    <div data-color-mode="light">
      {hasRu ? (
        <div className="mb-2 flex items-center gap-1">
          <button type="button" onClick={() => setLang('EN')} className={pill('EN')}>EN</button>
          <span className="text-zinc-300">/</span>
          <button type="button" onClick={() => setLang('RU')} className={pill('RU')}>RU</button>
        </div>
      ) : null}
      <div className="text-sm">
        <Markdown source={content || '_(no content)_'} />
      </div>
    </div>
  );
}
