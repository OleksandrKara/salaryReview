'use client';

import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { api } from '../lib/api';
import type { Language } from '../lib/types';

// "EN / RU" toggle used in the owner/manager admin menu and on the provider /me header. The active
// language is emphasized; clicking the other persists it and refreshes server components.
export default function LanguageSwitch({ language }: { language: Language | null }) {
  const [lang, setLang] = useState<Language>(language ?? 'EN');
  const router = useRouter();

  async function switchTo(next: Language) {
    if (next === lang) return;
    setLang(next);
    try {
      await api.setLanguage(next);
      router.refresh();
    } catch {
      /* leave the optimistic state; a reload reconciles */
    }
  }

  const pill = (l: Language) =>
    `text-xs ${lang === l ? 'font-semibold text-zinc-700' : 'text-zinc-400 hover:text-zinc-600'}`;

  return (
    <span className="flex items-center gap-1" title="Language">
      <button type="button" onClick={() => switchTo('EN')} className={pill('EN')}>EN</button>
      <span className="text-zinc-300">/</span>
      <button type="button" onClick={() => switchTo('RU')} className={pill('RU')}>RU</button>
    </span>
  );
}
