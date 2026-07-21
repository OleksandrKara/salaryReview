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

// Standalone shareable-link view of one SOP — same read + acknowledge behavior as its row in
// SopList, just without the rest of the list around it. The acknowledge button arms immediately
// here (unlike the list's "open it first" gate) since landing on this page already means reading
// the content is the whole point of the visit.
export default function SopDetailView({
  initialSop,
  role,
  language,
}: {
  initialSop: Sop;
  role: Role;
  language: Language | null;
}) {
  const [sop, setSop] = useState(initialSop);
  const [busy, setBusy] = useState(false);
  const canAck = role === 'MANAGER' || role === 'PROVIDER';

  async function acknowledge() {
    setBusy(true);
    try {
      setSop(await api.acknowledgeSop(sop.id));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mt-4 rounded-lg p-4 ring-1 ring-zinc-200">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <span className="flex flex-wrap items-center gap-1.5">
            {!sop.acknowledged && hasUnreadChange(sop) ? (
              <span className="inline-flex items-center gap-1 rounded-full bg-amber-100 px-2 py-0.5 text-[10px] font-bold uppercase tracking-wide text-amber-800">
                <NewBadgeIcon className="h-2.5 w-2.5" /> {t(language, 'sopNewVersionBadge')}
              </span>
            ) : null}
          </span>
          <span className="text-xs text-zinc-400">
            {sop.category} · v{sop.currentVersion?.versionNumber ?? '—'}
          </span>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <ShareLinkButton path={`/sops/${sop.id}`} title={localized(language, sop.title, sop.titleRu)} />
          {sop.acknowledged ? (
            <span className="rounded bg-green-50 px-2 py-1 text-xs text-green-700">
              ✅ {t(language, 'sopAcknowledged')} {fmt(sop.acknowledgedAt, language)}
            </span>
          ) : canAck ? (
            <button
              onClick={acknowledge}
              disabled={busy}
              className="inline-flex items-center gap-1.5 rounded-lg bg-zinc-900 px-3 py-1.5 text-xs font-medium text-white disabled:opacity-40"
            >
              {busy ? <Spinner className="h-3.5 w-3.5 text-white" /> : null}
              {t(language, 'sopAckButton')}
            </button>
          ) : null}
        </div>
      </div>
      <div className="mt-4 border-t border-zinc-100 pt-4">
        <SopArticleBody version={sop.currentVersion} defaultLang={language ?? 'EN'} />
      </div>
    </div>
  );
}
