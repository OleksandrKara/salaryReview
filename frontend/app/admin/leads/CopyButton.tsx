'use client';

import { useState } from 'react';
import { t } from '../../lib/i18n';
import type { Language } from '../../lib/types';

export default function CopyButton({ value, language }: { value: string; language: Language | null }) {
  const [done, setDone] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
    } catch {
      const el = document.createElement('textarea');
      el.value = value;
      document.body.appendChild(el);
      el.select();
      try { document.execCommand('copy'); } catch { /* nothing else to try */ }
      el.remove();
    }
    setDone(true);
    setTimeout(() => setDone(false), 1500);
  }

  const label = t(language, done ? 'copyCopied' : 'copyCopy');
  return (
    <button
      type="button" onClick={copy} aria-label={`${label}: ${value}`} title={label}
      className={`inline-flex h-7 w-7 shrink-0 items-center justify-center rounded text-zinc-500 hover:bg-zinc-100 hover:text-zinc-800 ${done ? 'text-green-700' : ''}`}
    >
      {done ? (
        <svg viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden><path d="M5 12.5l4.5 4.5L19 7.5" /></svg>
      ) : (
        <svg viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden><rect x="9" y="9" width="11" height="11" rx="2" /><path d="M5 15V6a2 2 0 0 1 2-2h9" /></svg>
      )}
    </button>
  );
}
