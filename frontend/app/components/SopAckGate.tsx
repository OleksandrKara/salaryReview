'use client';

import dynamic from 'next/dynamic';
import { usePathname, useRouter } from 'next/navigation';
import { useEffect, useRef, useState } from 'react';
import { api } from '../lib/api';
import { t } from '../lib/i18n';
import { Spinner } from './Spinner';
import type { Language, Sop } from '../lib/types';

const Markdown = dynamic(
  () => import('@uiw/react-md-editor').then((m) => ({ default: m.default.Markdown })),
  { ssr: false },
);

// Mandatory SOP acknowledgment. Managers and providers must read and confirm every active SOP they
// haven't accepted yet before using the app. Shown as a blocking overlay (full-screen on mobile, a
// centered card on web): one SOP at a time, the confirm button enables only after the reader has
// scrolled to the end of the content. Owners author SOPs and are never gated. No backend change —
// /api/sops already returns the content + the caller's acknowledgment state.
export default function SopAckGate() {
  const [pending, setPending] = useState<Sop[] | null>(null);
  const [lang, setLang] = useState<Language>('EN');
  const [gated, setGated] = useState(true); // whether this role is subject to the gate
  const [busy, setBusy] = useState(false);
  const pathname = usePathname();
  const router = useRouter();

  // Re-read role + pending SOPs on mount and each navigation (the gate lives in the root layout, which
  // doesn't remount on client nav). setState only happens inside the async callbacks, not synchronously.
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
  );
}

function SopCard({
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
  const [readToEnd, setReadToEnd] = useState(false);
  const hasRu = !!(sop.currentVersion?.bodyRu && sop.currentVersion.bodyRu.trim());
  const [viewLang, setViewLang] = useState<Language>(lang === 'RU' && hasRu ? 'RU' : 'EN');
  const body = viewLang === 'RU' && hasRu ? sop.currentVersion!.bodyRu! : sop.currentVersion?.body || '';

  // Enable confirm once the reader reaches the end (the card remounts per SOP via key, so this resets).
  function checkScrolled() {
    const el = scrollRef.current;
    if (el && el.scrollHeight - el.scrollTop - el.clientHeight <= 24) setReadToEnd(true);
  }

  // If the content fits without scrolling, there's nothing to scroll → allow confirm. Re-checked when
  // the language toggle changes the body. setState runs inside the rAF callback, not synchronously.
  useEffect(() => {
    const id = requestAnimationFrame(() => {
      const el = scrollRef.current;
      if (el && el.scrollHeight <= el.clientHeight + 24) setReadToEnd(true);
    });
    return () => cancelAnimationFrame(id);
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
        className="min-h-0 flex-1 overflow-y-auto px-5 py-4 text-sm"
      >
        <Markdown source={body || '_(no content)_'} style={{ background: 'transparent', fontSize: '0.9rem' }} />
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
