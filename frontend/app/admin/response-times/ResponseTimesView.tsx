'use client';

import { useEffect, useState } from 'react';
import type { Language, ResponseLeaderboard } from '../../lib/types';
import { t, tf } from '../../lib/i18n';
import { formatWait, rankManagers, speedTone } from '../../lib/responseTime';

type Period = 'today' | '7d' | '30d';
const PERIODS: { id: Period; key: 'rtToday' | 'rt7d' | 'rt30d' }[] = [
  { id: 'today', key: 'rtToday' },
  { id: '7d', key: 'rt7d' },
  { id: '30d', key: 'rt30d' },
];
const TONE: Record<string, string> = { good: 'text-emerald-700', ok: 'text-amber-700', slow: 'text-red-700', none: 'text-zinc-500' };

export default function ResponseTimesView({ language }: { language: Language | null }) {
  const [period, setPeriod] = useState<Period>('7d');
  const [data, setData] = useState<ResponseLeaderboard | null>(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    let alive = true;
    fetch(`/api/metrics/response?period=${period}`, { cache: 'no-store' })
      .then((res) => (res.ok ? (res.json() as Promise<ResponseLeaderboard>) : Promise.reject(new Error(String(res.status)))))
      .then((body) => { if (alive) { setData(body); setError(false); } })
      .catch(() => { if (alive) setError(true); });
    return () => { alive = false; };
  }, [period]);

  const rows = data ? rankManagers(data.managers) : [];
  const inferred = data ? data.managers.reduce((n, m) => n + m.inferredReplies, 0) : 0;
  const pct = (n: number | null) => (n == null ? '-' : `${n}%`);

  return (
    <div>
      <div role="tablist" className="mb-4 inline-flex rounded-lg border border-zinc-200 bg-white p-0.5">
        {PERIODS.map((p) => (
          <button
            key={p.id}
            role="tab"
            aria-selected={period === p.id}
            onClick={() => setPeriod(p.id)}
            className={`min-h-9 rounded-md px-3 text-sm ${period === p.id ? 'bg-zinc-900 text-white' : 'text-zinc-600'}`}
          >
            {t(language, p.key)}
          </button>
        ))}
      </div>

      {error ? <p className="text-sm text-red-700">{t(language, 'rtLoadError')}</p> : null}
      {!data && !error ? <p className="text-sm text-zinc-500">...</p> : null}

      {data ? (
        <>
          {data.unansweredNow > 0 ? (
            <p className="mb-3 rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-900">
              {tf(language, 'rtUnansweredNow', { n: data.unansweredNow })}
              {data.oldestUnansweredSeconds != null ? ` (${tf(language, 'shiftUnansweredOldest', { wait: formatWait(data.oldestUnansweredSeconds, language) })})` : ''}
            </p>
          ) : null}

          {rows.length === 0 ? (
            <p className="text-sm text-zinc-500">{t(language, 'rtEmpty')}</p>
          ) : (
            <div className="overflow-x-auto rounded-xl border border-zinc-200 bg-white">
              <table className="w-full min-w-[34rem] text-left text-sm">
                <thead className="text-xs text-zinc-500">
                  <tr>
                    <th className="px-3 py-2 font-medium">{t(language, 'rtManager')}</th>
                    <th className="px-3 py-2 font-medium">{t(language, 'rtMedian')}</th>
                    <th className="px-3 py-2 font-medium">{t(language, 'rtAverage')}</th>
                    <th className="px-3 py-2 font-medium">{t(language, 'rtWithin5')}</th>
                    <th className="px-3 py-2 font-medium">{t(language, 'rtWithin15')}</th>
                    <th className="px-3 py-2 font-medium">{t(language, 'rtReplies')}</th>
                    <th className="px-3 py-2 font-medium">{t(language, 'rtLeads')}</th>
                    <th className="px-3 py-2 font-medium">{t(language, 'rtHours')}</th>
                  </tr>
                </thead>
                <tbody className="tabular-nums">
                  {rows.map((m, i) => (
                    <tr key={m.userId} className="border-t border-zinc-100">
                      <td className="px-3 py-2 font-medium">{m.replies > 0 ? `${i + 1}. ` : ''}{m.username}</td>
                      <td className={`px-3 py-2 font-semibold ${TONE[speedTone(m.medianSeconds)]}`}>{formatWait(m.medianSeconds, language)}</td>
                      <td className="px-3 py-2">{formatWait(m.averageSeconds, language)}</td>
                      <td className="px-3 py-2">{pct(m.within5Percent)}</td>
                      <td className="px-3 py-2">{pct(m.within15Percent)}</td>
                      <td className="px-3 py-2">{m.replies}</td>
                      <td className="px-3 py-2">{m.leadsProcessed}</td>
                      <td className="px-3 py-2">{m.shiftHours}</td>
                    </tr>
                  ))}
                  <tr className="border-t border-zinc-200 bg-zinc-50 font-medium">
                    <td className="px-3 py-2">{t(language, 'rtTeam')}</td>
                    <td className="px-3 py-2">{formatWait(data.team.medianSeconds, language)}</td>
                    <td className="px-3 py-2">{formatWait(data.team.averageSeconds, language)}</td>
                    <td className="px-3 py-2">{pct(data.team.within5Percent)}</td>
                    <td className="px-3 py-2">{pct(data.team.within15Percent)}</td>
                    <td className="px-3 py-2">{data.team.replies}</td>
                    <td className="px-3 py-2" />
                    <td className="px-3 py-2" />
                  </tr>
                </tbody>
              </table>
            </div>
          )}

          <ul className="mt-3 space-y-1 text-xs text-zinc-500">
            {inferred > 0 ? <li>{tf(language, 'rtInferred', { n: inferred })}</li> : null}
            {data.unknownReplies > 0 ? <li>{tf(language, 'rtUnknown', { n: data.unknownReplies })}</li> : null}
            {data.offShiftReplies > 0 ? <li>{tf(language, 'rtOffShift', { n: data.offShiftReplies })}</li> : null}
          </ul>
        </>
      ) : null}
    </div>
  );
}
