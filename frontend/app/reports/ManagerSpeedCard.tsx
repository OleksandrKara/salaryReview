'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';
import type { Language, ResponseLeaderboard } from '../lib/types';
import { t, tf } from '../lib/i18n';
import { formatWait, rankManagers, speedTone } from '../lib/responseTime';

const TONE: Record<string, string> = { good: 'text-emerald-700', ok: 'text-amber-700', slow: 'text-red-700', none: 'text-zinc-500' };

export default function ManagerSpeedCard({ language }: { language: Language | null }) {
  const [data, setData] = useState<ResponseLeaderboard | null>(null);

  useEffect(() => {
    let alive = true;
    fetch('/api/metrics/response?period=7d', { cache: 'no-store' })
      .then((res) => (res.ok ? (res.json() as Promise<ResponseLeaderboard>) : null))
      .then((body) => { if (alive && body) setData(body); })
      .catch(() => {});
    return () => { alive = false; };
  }, []);

  if (!data) return null;
  const best = rankManagers(data.managers).find((m) => m.replies > 0);
  return (
    <section data-testid="manager-speed-card" className="mb-4 rounded-xl border border-zinc-200 bg-white p-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="text-sm font-semibold">{t(language, 'rtCardTitle')}</h2>
        <Link href="/admin/response-times" className="text-xs underline">{t(language, 'rtCardOpen')}</Link>
      </div>
      <p className="mt-2 flex flex-wrap items-baseline gap-x-4 gap-y-1 text-sm">
        <span className="text-xs text-zinc-500">{t(language, 'rtCardTeam')}</span>
        <span className={`text-xl font-semibold tabular-nums ${TONE[speedTone(data.team.medianSeconds)]}`}>{formatWait(data.team.medianSeconds, language)}</span>
        {best ? <span className="text-xs text-zinc-600">{tf(language, 'rtCardBest', { name: best.username, wait: formatWait(best.medianSeconds, language) })}</span> : null}
      </p>
      {data.unansweredNow > 0 ? (
        <p className="mt-1 text-xs text-amber-800">{tf(language, 'rtUnansweredNow', { n: data.unansweredNow })}</p>
      ) : null}
    </section>
  );
}
