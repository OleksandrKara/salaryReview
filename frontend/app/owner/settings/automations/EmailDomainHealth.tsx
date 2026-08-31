'use client';

import { useEffect, useState } from 'react';
import { api } from '../../../lib/api';
import type { EmailDomainHealthCheckDto, EmailDomainHealthDto } from '../../../lib/types';

const RATING_STYLES: Record<string, { badge: string; ring: string }> = {
  Excellent: { badge: 'bg-emerald-50 text-emerald-700', ring: 'ring-emerald-200' },
  Good: { badge: 'bg-sky-50 text-sky-700', ring: 'ring-sky-200' },
  'Needs work': { badge: 'bg-amber-50 text-amber-700', ring: 'ring-amber-200' },
  Poor: { badge: 'bg-red-50 text-red-700', ring: 'ring-red-200' },
};

function CheckIcon({ pass }: { pass: boolean }) {
  return pass ? (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" aria-hidden className="shrink-0 text-emerald-600">
      <path d="M20 6 9 17l-5-5" />
    </svg>
  ) : (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" aria-hidden className="shrink-0 text-red-500">
      <path d="M18 6 6 18M6 6l12 12" />
    </svg>
  );
}

function CheckRow({ label, check }: { label: string; check: EmailDomainHealthCheckDto }) {
  return (
    <div className="flex items-start gap-2.5 py-2">
      <div className="mt-0.5"><CheckIcon pass={check.pass} /></div>
      <div className="min-w-0">
        <p className="text-sm font-medium text-zinc-900">{label}</p>
        <p className="mt-0.5 text-xs break-words text-zinc-500">{check.detail}</p>
      </div>
    </div>
  );
}

function formatWhen(iso: string): string {
  return new Date(iso).toLocaleString('en-US', {
    month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit',
  });
}

/**
 * SPF/DKIM/DMARC/MX health for this business's own sending domain — grew directly out of the
 * pmu-annakara.com toll-free-verification investigation (2026-08-31), where a dangling MX record
 * and missing SPF/DKIM were only found by manually running `dig` back and forth across a long
 * back-and-forth. Same idea as mail-tester.com's score, but DNS-only (no mail is actually sent, so
 * no blacklist/content/reputation check) — the part an owner can actually act on from their DNS
 * panel, checked instantly on load instead of a manual send-and-check round trip.
 *
 * Auto-fetches on mount (unlike the Activity log sections below, which stay collapsed until
 * opened) — this is a small, fast DNS check, not a 100-row log, so there's no first-paint cost
 * worth gating behind a click.
 */
export default function EmailDomainHealth() {
  const [data, setData] = useState<EmailDomainHealthDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  async function load() {
    setLoading(true);
    setError(null);
    try {
      setData(await api.getEmailDomainHealth());
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to check domain health');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  if (loading && !data) {
    return (
      <div className="mt-4 rounded-lg p-4 text-sm text-zinc-400 ring-1 ring-zinc-200">
        Checking domain…
      </div>
    );
  }

  if (error) {
    return (
      <div className="mt-4 rounded-lg p-4 text-sm text-red-600 ring-1 ring-red-200">
        {error}{' '}
        <button type="button" onClick={() => void load()} className="font-medium underline">Retry</button>
      </div>
    );
  }

  if (!data || !data.configured || !data.spf || !data.dkim || !data.dmarc || !data.mx) {
    return (
      <div className="mt-4 rounded-lg p-4 text-sm text-zinc-500 ring-1 ring-dashed ring-zinc-300">
        Set a &quot;From&quot; email above (Save first) to check your sending domain&apos;s SPF/DKIM/DMARC health.
      </div>
    );
  }

  const style = RATING_STYLES[data.rating ?? ''] ?? RATING_STYLES.Poor;

  return (
    <div className={`mt-4 rounded-lg p-4 ring-1 ${style.ring}`}>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <p className="text-sm font-medium text-zinc-900">{data.domain}</p>
          {data.checkedAt && (
            <p className="mt-0.5 text-xs text-zinc-400">Checked {formatWhen(data.checkedAt)}</p>
          )}
        </div>
        <div className="flex items-center gap-2">
          <span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold ${style.badge}`}>
            {data.score}/100 — {data.rating}
          </span>
          <button
            type="button"
            onClick={() => void load()}
            disabled={loading}
            className="rounded border border-zinc-300 px-2.5 py-1 text-xs font-medium text-zinc-600 hover:bg-zinc-50 disabled:opacity-50"
          >
            {loading ? 'Checking…' : 'Recheck'}
          </button>
        </div>
      </div>

      <div className="mt-2 divide-y divide-zinc-100">
        <CheckRow label="SPF" check={data.spf} />
        <CheckRow label="DKIM" check={data.dkim} />
        <CheckRow label="DMARC" check={data.dmarc} />
        <CheckRow label="MX" check={data.mx} />
      </div>

      <p className="mt-3 text-xs text-zinc-400">
        DNS-only check (no email is actually sent) — for a full deliverability/inbox-placement
        test, use{' '}
        <a href="https://www.mail-tester.com" target="_blank" rel="noreferrer" className="underline">
          mail-tester.com
        </a>
        .
      </p>
    </div>
  );
}
