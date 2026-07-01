'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { api } from '../lib/api';
import { t } from '../lib/i18n';
import SopCard from './SopCard';
import { Spinner } from './Spinner';
import type { Language, Me, Sop } from '../lib/types';

// The mandatory onboarding gate, seeded with server-fetched state so it fills the screen in the very
// first paint — a blocked manager/provider never sees the app behind it (the root layout renders this
// instead of the page). Two steps, in order:
//   1. Choose a language, if not chosen yet.
//   2. Read + confirm every unaccepted SOP, one at a time.
// When both are satisfied it refreshes so the layout re-runs and reveals the app.
export default function OnboardingGate({ me, pending }: { me: Me; pending: Sop[] }) {
  const router = useRouter();
  const [lang, setLang] = useState<Language | null>(me.preferredLanguage);
  const [queue, setQueue] = useState<Sop[]>(pending);
  const [busy, setBusy] = useState(false);

  async function chooseLanguage(l: Language) {
    setBusy(true);
    try {
      await api.setLanguage(l);
      setLang(l);
      if (queue.length === 0) router.refresh(); // language was the only thing missing
    } finally {
      setBusy(false);
    }
  }

  async function confirmSop(id: number) {
    setBusy(true);
    try {
      await api.acknowledgeSop(id);
      const rest = queue.slice(1);
      setQueue(rest);
      if (rest.length === 0) router.refresh(); // last SOP done → reveal the app
    } finally {
      setBusy(false);
    }
  }

  let content: React.ReactNode;
  if (lang === null) {
    // Step 1: language.
    content = (
      <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
        <div className="w-full max-w-sm rounded-2xl bg-[var(--paper)] p-6 text-center shadow-2xl ring-1 ring-[var(--line)]">
          <h2 className="text-lg" style={{ fontFamily: 'var(--serif)' }}>Choose your language</h2>
          <p className="mt-1 text-sm text-[var(--muted)]">Выберите язык</p>
          <div className="mt-5 flex gap-3">
            {(['EN', 'RU'] as Language[]).map((l) => (
              <button
                key={l}
                onClick={() => chooseLanguage(l)}
                disabled={busy}
                className="flex-1 rounded-lg bg-[var(--ink)] px-4 py-3 text-sm font-medium text-[var(--paper)] disabled:opacity-50"
              >
                {l === 'EN' ? 'English' : 'Русский'}
              </button>
            ))}
          </div>
          <p className="mt-4 text-xs text-[var(--muted)]">You can change this anytime from the menu.</p>
        </div>
      </div>
    );
  } else if (queue.length > 0) {
    // Step 2: SOPs.
    const sop = queue[0];
    content = (
      <div className="fixed inset-0 z-50 flex items-stretch justify-center bg-black/50 sm:items-center sm:p-4">
        <div className="flex h-full w-full flex-col overflow-hidden bg-[var(--paper)] shadow-2xl sm:h-auto sm:max-h-[90vh] sm:max-w-2xl sm:rounded-2xl">
          <SopCard
            key={sop.id}
            sop={sop}
            lang={lang}
            index={queue.length}
            busy={busy}
            onConfirm={() => confirmSop(sop.id)}
          />
        </div>
      </div>
    );
  } else {
    // Both satisfied — waiting for the refresh to swap in the app.
    content = (
      <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
        <Spinner className="h-6 w-6 text-[var(--paper)]" />
        <span className="sr-only">{t(lang, 'sopAckButton')}</span>
      </div>
    );
  }

  return (
    <>
      {content}
      {/* Always available — a user can sign out without first choosing a language or accepting SOPs. */}
      <a
        href="/api/logout"
        className="fixed right-4 top-4 z-[70] rounded-lg bg-black/40 px-3 py-1.5 text-xs font-medium text-white backdrop-blur transition hover:bg-black/60"
      >
        {t(lang ?? 'EN', 'logout')}
      </a>
    </>
  );
}
