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

  // "What changed" — only from v2 on (a v1 has nothing to compare against), and only when the author
  // actually wrote one; blank shows nothing. Falls back to English like the body does. Gated behind
  // its own full-screen step (not just a banner above the body) so it can't be skimmed past — a plain
  // colored banner blended in with the rest of the page and was easy to miss.
  const hasChangeNote = !!(sop.currentVersion?.changeNote?.trim() || sop.currentVersion?.changeNoteRu?.trim());
  const showChangeNote = (sop.currentVersion?.versionNumber ?? 0) >= 2 && hasChangeNote;
  const changeNote = viewLang === 'RU' && sop.currentVersion?.changeNoteRu?.trim()
    ? sop.currentVersion.changeNoteRu
    : sop.currentVersion?.changeNote;
  // Resets per SOP because the parent remounts this component via key={sop.id}.
  const [introSeen, setIntroSeen] = useState(false);

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

  // Step 1 of 2 when there's a change note: a full-screen, impossible-to-miss "this is a new
  // version" interstitial — no body text, no other content competing for attention. Only after
  // explicitly continuing does the reader reach the normal card (header + body + confirm).
  if (showChangeNote && !introSeen) {
    return (
      <div className="flex h-full flex-col">
        <div className="flex flex-1 flex-col items-center justify-center gap-4 overflow-y-auto bg-gradient-to-b from-[var(--accent-soft)] to-[var(--paper)] px-6 py-10 text-center">
          <span className="inline-flex items-center gap-1.5 rounded-full bg-gradient-to-br from-[var(--accent)] to-[var(--accent-ink)] px-3.5 py-1.5 text-xs font-bold uppercase tracking-wide text-[var(--paper)] shadow-sm">
            <NewBadgeIcon className="h-3.5 w-3.5" />
            {t(lang, 'sopNewVersionBadge')} · v{sop.currentVersion?.versionNumber}
          </span>
          <h2 className="text-2xl font-bold leading-tight" style={{ fontFamily: 'var(--serif)' }}>
            {sop.title}
          </h2>
          {hasRu ? (
            <span className="-mt-2 flex items-center gap-1 text-xs">
              {(['EN', 'RU'] as Language[]).map((l) => (
                <button
                  key={l}
                  onClick={() => setViewLang(l)}
                  className={viewLang === l ? 'font-semibold text-[var(--accent-ink)]' : 'text-[var(--muted)]'}
                >
                  {l}
                </button>
              ))}
            </span>
          ) : null}
          <div className="w-full max-w-md rounded-xl bg-[var(--white)] p-4 text-left shadow-md ring-2 ring-[var(--accent)]">
            <p className="mb-1.5 flex items-center gap-1 text-xs font-bold uppercase tracking-wide text-[var(--accent-ink)]">
              {t(lang, 'sopWhatChanged')}
            </p>
            <div className="text-sm text-[var(--ink)]" style={{ whiteSpace: 'pre-wrap' }}>{changeNote}</div>
          </div>
        </div>
        <div className="shrink-0 border-t border-[var(--line)] bg-[var(--paper)] px-5 py-3">
          <button
            onClick={() => setIntroSeen(true)}
            className="flex w-full items-center justify-center gap-2 rounded-xl bg-gradient-to-br from-[var(--accent)] to-[var(--accent-ink)] px-4 py-3 text-sm font-semibold text-[var(--paper)] shadow-sm transition"
          >
            {t(lang, 'sopContinueToRead')}
          </button>
        </div>
      </div>
    );
  }

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

      {/* What changed — repeated here (not just on the intro step) so scrolling back up still shows
          it, in case the reader wants to re-check it against the full body. */}
      {showChangeNote ? (
        <div className="shrink-0 border-b-2 border-[var(--accent)] bg-[var(--accent-soft)] px-5 py-3">
          <p className="mb-1 flex items-center gap-1 text-[10px] font-bold uppercase tracking-wide text-[var(--accent-ink)]">
            <NewBadgeIcon className="h-3 w-3" /> {t(lang, 'sopWhatChanged')}
          </p>
          <div className="text-sm text-[var(--ink)]" style={{ whiteSpace: 'pre-wrap' }}>{changeNote}</div>
        </div>
      ) : null}

      {/* Scrollable content */}
      <div
        ref={scrollRef}
        onScroll={checkScrolled}
        data-color-mode="light"
        className="min-h-0 flex-1 overflow-y-auto px-5 py-4 text-sm"
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

function NewBadgeIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <path d="M12 2l2.4 6.6L21 11l-6.6 2.4L12 20l-2.4-6.6L3 11l6.6-2.4z" />
    </svg>
  );
}
