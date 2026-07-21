'use client';

import { useState } from 'react';
import { api } from '../lib/api';
import { Spinner } from '../components/Spinner';
import ShareLinkButton from '../components/ShareLinkButton';
import { hasUnreadChange, NewBadgeIcon, SopArticleBody } from './SopArticleBody';
import { localized, t } from '../lib/i18n';
import type { Language, Role, Sop } from '../lib/types';

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
                <span className="flex flex-wrap items-center gap-1.5">
                  <span className="text-sm font-medium text-zinc-800">{localized(language, s.title, s.titleRu)}</span>
                  {!s.acknowledged && hasUnreadChange(s) ? (
                    <span className="inline-flex items-center gap-1 rounded-full bg-amber-100 px-2 py-0.5 text-[10px] font-bold uppercase tracking-wide text-amber-800">
                      <NewBadgeIcon className="h-2.5 w-2.5" /> {t(language, 'sopNewVersionBadge')}
                    </span>
                  ) : null}
                </span>
                <span className="text-xs text-zinc-400">
                  {s.category} · v{s.currentVersion?.versionNumber ?? '—'}
                </span>
              </span>
            </button>
            <div className="flex shrink-0 items-center gap-2">
              <ShareLinkButton path={`/sops/${s.id}`} title={localized(language, s.title, s.titleRu)} />
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
              <SopArticleBody version={s.currentVersion} defaultLang={language ?? 'EN'} />
            </div>
          ) : null}
        </li>
      ))}
    </ul>
  );
}
