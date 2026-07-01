'use client';

import { usePathname, useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import { api } from '../lib/api';
import { t } from '../lib/i18n';
import SopCard from './SopCard';
import type { Language, Sop } from '../lib/types';

// Nav-time safety net for SOP acknowledgment. The primary gate is server-rendered in the root layout
// (OnboardingGate) so a blocked manager/provider never sees the app. This client component additionally
// catches SOPs published *mid-session*: the layout doesn't re-run on client navigation, so on each
// navigation it re-checks and blocks on any newly unaccepted SOP. Owners author SOPs and are never gated.
export default function SopAckGate() {
  const [pending, setPending] = useState<Sop[] | null>(null);
  const [lang, setLang] = useState<Language>('EN');
  const [gated, setGated] = useState(true); // whether this role is subject to the gate
  const [busy, setBusy] = useState(false);
  const pathname = usePathname();
  const router = useRouter();

  // Re-read role + pending SOPs on mount and each navigation. setState only happens inside the async
  // callbacks, not synchronously.
  useEffect(() => {
    let cancelled = false;
    api.getMe()
      .then(async (me) => {
        if (cancelled) return;
        if (me.role !== 'MANAGER' && me.role !== 'PROVIDER') { setGated(false); return; }
        setLang(me.preferredLanguage ?? 'EN');
        const sops = await api.listSops(); // API returns only active, published, audience-matched SOPs
        if (cancelled) return;
        setPending(sops.filter((s) => !s.acknowledged && s.currentVersion));
      })
      .catch(() => { if (!cancelled) setGated(false); }); // not signed in / error → never trap the user
    return () => { cancelled = true; };
  }, [pathname]);

  if (!gated || pending === null || pending.length === 0) return null;

  const sop = pending[0];

  async function confirm() {
    setBusy(true);
    try {
      await api.acknowledgeSop(sop.id);
      setPending((xs) => (xs ? xs.slice(1) : xs));
      router.refresh(); // refresh server components that show acknowledgment state
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <div className="fixed inset-0 z-50 flex items-stretch justify-center bg-black/50 sm:items-center sm:p-4">
        <div className="flex h-full w-full flex-col overflow-hidden bg-[var(--paper)] shadow-2xl sm:h-auto sm:max-h-[90vh] sm:max-w-2xl sm:rounded-2xl">
          <SopCard
            key={sop.id}
            sop={sop}
            lang={lang}
            index={pending.length}
            busy={busy}
            onConfirm={confirm}
          />
        </div>
      </div>
      {/* Always available — a user can sign out without first accepting the pending SOPs. */}
      <a
        href="/api/logout"
        className="fixed right-4 top-4 z-[70] rounded-lg bg-black/40 px-3 py-1.5 text-xs font-medium text-white backdrop-blur transition hover:bg-black/60"
      >
        {t(lang, 'logout')}
      </a>
    </>
  );
}
