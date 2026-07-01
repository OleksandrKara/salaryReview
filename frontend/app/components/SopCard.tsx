'use client';

import dynamic from 'next/dynamic';
import { useEffect, useRef, useState } from 'react';
import { t } from '../lib/i18n';
import { Spinner } from './Spinner';
import type { Language, Sop } from '../lib/types';

const Markdown = dynamic(
  () => import('@uiw/react-md-editor').then((m) => ({ default: m.default.Markdown })),
  { ssr: false },
);

// One SOP presented for reading + confirmation. The confirm button enables only after the reader
// scrolls to the end (or immediately if the content already fits). Remount via `key={sop.id}` resets
// the read state per SOP. Shared by the blocking onboarding gate and the nav-time acknowledgment gate.
export default function SopCard({
  sop,
  lang,
  index,
  busy,
  onConfirm,
}: {
  sop: Sop;
  lang: Language;
  index: number; // how many remain (this one + the rest)
  busy: boolean;
  onConfirm: () => void;
}) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const contentRef = useRef<HTMLDivElement>(null);
  const [readToEnd, setReadToEnd] = useState(false);
  const hasRu = !!(sop.currentVersion?.bodyRu && sop.currentVersion.bodyRu.trim());
  const [viewLang, setViewLang] = useState<Language>(lang === 'RU' && hasRu ? 'RU' : 'EN');
  const body = viewLang === 'RU' && hasRu ? sop.currentVersion!.bodyRu! : sop.currentVersion?.body || '';

  // Enable confirm once the reader reaches the end (the card remounts per SOP via key, so this resets).
  function checkScrolled() {
    const el = scrollRef.current;
    if (el && el.scrollHeight - el.scrollTop - el.clientHeight <= 24) setReadToEnd(true);
  }

  // Auto-enable only when the content genuinely fits without scrolling. Crucially this must be measured
  // AFTER the Markdown renders — it's dynamically imported (ssr:false), so an earlier check would see an
  // empty container (just padding), wrongly conclude it "fits", and enable the button by default. A
  // ResizeObserver on the content fires when the Markdown finally lays out (and on language toggle), and
  // we ignore the empty state. setState runs in the observer callback, never synchronously in the effect.
  useEffect(() => {
    const el = scrollRef.current;
    const content = contentRef.current;
    if (!el || !content) return;
    const ro = new ResizeObserver(() => {
      if (content.scrollHeight < 8) return; // Markdown not rendered yet — don't decide
      if (el.scrollHeight - el.clientHeight <= 24) setReadToEnd(true);
    });
    ro.observe(content);
    return () => ro.disconnect();
  }, [viewLang]);

  return (
    <>
      {/* Header */}
      <div className="shrink-0 border-b border-[var(--line)] bg-[var(--ink)] px-5 py-3 text-[var(--paper)]">
        <div className="flex items-center justify-between gap-3">
          <span className="text-[10px] font-medium uppercase tracking-wide text-[var(--accent)]">
            {t(lang, 'sopAckTitle')}
            {index > 1 ? <span className="ml-2 text-[var(--paper)]/60">· {index} {t(lang, 'sopAckRemaining')}</span> : null}
          </span>
          {hasRu ? (
            <span className="flex items-center gap-1 text-[10px]">
              {(['EN', 'RU'] as Language[]).map((l) => (
                <button
                  key={l}
                  onClick={() => { setViewLang(l); setReadToEnd(false); }}
                  className={viewLang === l ? 'font-semibold text-[var(--accent)]' : 'text-[var(--paper)]/60'}
                >
                  {l}
                </button>
              ))}
            </span>
          ) : null}
        </div>
        <h2 className="mt-1 text-lg font-semibold leading-tight" style={{ fontFamily: 'var(--serif)' }}>
          {sop.title}
        </h2>
        <p className="text-xs text-[var(--paper)]/60">
          {sop.category} · v{sop.currentVersion?.versionNumber} · {t(lang, 'sopAckIntro')}
        </p>
      </div>

      {/* Scrollable content */}
      <div
        ref={scrollRef}
        onScroll={checkScrolled}
        data-color-mode="light"
        className="sop-md min-h-0 flex-1 overflow-y-auto px-5 py-4 text-sm"
      >
        <div ref={contentRef}>
          <Markdown source={body || '_(no content)_'} style={{ background: 'transparent', fontSize: '0.9rem' }} />
        </div>
      </div>

      {/* Sticky footer with the confirm button */}
      <div className="shrink-0 border-t border-[var(--line)] bg-[var(--paper)] px-5 py-3">
        {!readToEnd ? (
          <p className="mb-2 flex items-center justify-center gap-1 text-center text-[11px] text-[var(--muted)]">
            <span className="animate-bounce">↓</span> {t(lang, 'sopAckScroll')}
          </p>
        ) : null}
        <button
          onClick={onConfirm}
          disabled={!readToEnd || busy}
          className="flex w-full items-center justify-center gap-2 rounded-xl bg-gradient-to-br from-[var(--accent)] to-[var(--accent-ink)] px-4 py-3 text-sm font-semibold text-[var(--paper)] shadow-sm transition disabled:opacity-40"
        >
          {busy ? <Spinner className="h-4 w-4 text-[var(--paper)]" /> : null}
          {t(lang, 'sopAckButton')}
        </button>
      </div>
    </>
  );
}
