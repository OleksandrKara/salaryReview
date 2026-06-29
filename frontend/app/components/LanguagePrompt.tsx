'use client';

import { usePathname, useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import { api } from '../lib/api';
import type { Language, Me } from '../lib/types';

// One-time language setup for owners/managers. Shown when they haven't chosen yet (preferredLanguage
// null). Lives in the root layout, which doesn't remount on client navigation — so it re-checks
// /api/me on each navigation, which is what makes it appear right after sign-in.
export default function LanguagePrompt() {
  const [me, setMe] = useState<Me | null | undefined>(undefined);
  const [busy, setBusy] = useState(false);
  const pathname = usePathname();
  const router = useRouter();

  useEffect(() => {
    let cancelled = false;
    api.getMe().then((m) => { if (!cancelled) setMe(m); }).catch(() => { if (!cancelled) setMe(null); });
    return () => { cancelled = true; };
  }, [pathname]);

  const show = !!me && (me.role === 'OWNER' || me.role === 'MANAGER') && me.preferredLanguage === null;
  if (!show) return null;

  async function choose(language: Language) {
    setBusy(true);
    try {
      await api.setLanguage(language);
      setMe((m) => (m ? { ...m, preferredLanguage: language } : m));
      router.refresh();
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/40 p-4">
      <div className="w-full max-w-sm rounded-2xl bg-[var(--paper)] p-6 text-center shadow-2xl ring-1 ring-[var(--line)]">
        <h2 className="text-lg" style={{ fontFamily: 'var(--serif)' }}>Choose your language</h2>
        <p className="mt-1 text-sm text-[var(--muted)]">Выберите язык</p>
        <div className="mt-5 flex gap-3">
          <button
            onClick={() => choose('EN')}
            disabled={busy}
            className="flex-1 rounded-lg bg-[var(--ink)] px-4 py-3 text-sm font-medium text-[var(--paper)] disabled:opacity-50"
          >
            English
          </button>
          <button
            onClick={() => choose('RU')}
            disabled={busy}
            className="flex-1 rounded-lg bg-[var(--ink)] px-4 py-3 text-sm font-medium text-[var(--paper)] disabled:opacity-50"
          >
            Русский
          </button>
        </div>
        <p className="mt-4 text-xs text-[var(--muted)]">You can change this anytime from the menu.</p>
      </div>
    </div>
  );
}
