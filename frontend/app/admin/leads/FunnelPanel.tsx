'use client';

import { useCallback, useEffect, useState } from 'react';
import { t } from '../../lib/i18n';
import type { FunnelState, Language, LeadFunnel } from '../../lib/types';

export const FUNNEL_TONE: Record<FunnelState, string> = {
  ACTIVE: 'bg-green-100 text-green-800',
  PAUSED: 'bg-amber-100 text-amber-800',
  STOPPED: 'bg-red-100 text-red-800',
  FINISHED: 'bg-zinc-100 text-zinc-600',
  NOT_APPLIED: 'bg-blue-50 text-blue-800',
};
const STEP_DOT: Record<string, string> = {
  DONE: 'bg-green-500 border-green-500',
  SKIPPED: 'bg-zinc-300 border-zinc-300',
  FAILED: 'bg-red-500 border-red-500',
  PENDING: 'bg-white border-zinc-400',
};
const quick = 'rounded px-3 py-1.5 text-sm ring-1 ring-zinc-300 hover:bg-zinc-50 disabled:opacity-40';

function when(iso: string, lang: Language | null): string {
  return new Date(iso).toLocaleString(lang === 'RU' ? 'ru-RU' : 'en-US', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
}

export default function FunnelPanel({ leadId, language, onChanged }: { leadId: number; language: Language | null; onChanged: () => void }) {
  const [funnels, setFunnels] = useState<LeadFunnel[] | null>(null);
  const [confirm, setConfirm] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    const res = await fetch(`/api/leads/${leadId}/funnels`, { cache: 'no-store' });
    if (res.ok) setFunnels(await res.json());
  }, [leadId]);

  useEffect(() => { void load(); }, [load]);

  async function run(key: string, action: 'apply' | 'stop') {
    setBusy(true);
    setError('');
    const res = await fetch(`/api/leads/${leadId}/funnels/${key}/${action}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
    });
    setBusy(false);
    if (!res.ok) { setError(t(language, 'leadErr')); return; }
    setConfirm(null);
    await load();
    onChanged();
  }

  if (!funnels || funnels.length === 0) return null;

  return (
    <section className="flex flex-col gap-3 rounded-lg p-4 ring-1 ring-zinc-200">
      <h3 className="text-xs font-semibold uppercase tracking-wide text-zinc-500">{t(language, 'funnelTitle')}</h3>
      {funnels.map((f) => {
        const startKey = `start:${f.key}`;
        const stopKey = `stop:${f.key}`;
        const nameKey = `funnelName_${f.key}` as 'funnelName_academy';
        return (
          <div key={f.key} className="flex flex-col gap-2 rounded-md bg-zinc-50/70 p-3 ring-1 ring-zinc-200">
            <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
              <span className="text-sm font-medium">{t(language, nameKey)}</span>
              <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${FUNNEL_TONE[f.state]}`}>
                {t(language, `funnelState_${f.state}` as 'funnelState_ACTIVE')}
              </span>
              {f.startedAt && <span className="text-xs text-zinc-500">{t(language, 'funnelStarted')} {when(f.startedAt, language)}</span>}
            </div>
            {f.reason && (
              <p className="text-xs text-zinc-600">{t(language, `funnelReason_${f.reason}` as 'funnelReason_STAFF')}</p>
            )}
            {f.steps.length > 0 && (
              <ol className="flex flex-col gap-1.5">
                {f.steps.map((s) => (
                  <li key={s.key} className="flex items-center gap-2 text-xs">
                    <span aria-hidden className={`h-2.5 w-2.5 shrink-0 rounded-full border ${STEP_DOT[s.state]}`} />
                    <span className={s.state === 'PENDING' ? 'text-zinc-500' : 'text-zinc-800'}>
                      {t(language, `funnelStep_${s.key}` as 'funnelStep_sms')}
                    </span>
                    <span className="text-zinc-400">
                      {t(language, `funnelStep${s.state}` as 'funnelStepDONE')}{s.at ? `, ${when(s.at, language)}` : ''}
                    </span>
                  </li>
                ))}
              </ol>
            )}
            {f.state === 'NOT_APPLIED' && !f.canApply && (
              <p className="text-xs text-zinc-500">{t(language, `funnelHint_${f.key}` as 'funnelHint_site')}</p>
            )}
            {(f.canApply || f.canStop) && confirm !== startKey && confirm !== stopKey && (
              <div className="flex flex-wrap gap-2">
                {f.canApply && <button className={`${quick} bg-zinc-900 text-white ring-zinc-900 hover:bg-zinc-800`} onClick={() => setConfirm(startKey)}>{t(language, 'funnelStart')}</button>}
                {f.canStop && <button className={quick} onClick={() => setConfirm(stopKey)}>{t(language, 'funnelStop')}</button>}
              </div>
            )}
            {confirm === startKey && (
              <div className="flex flex-col gap-2 rounded-md bg-white p-3 ring-1 ring-zinc-300">
                <p className="text-sm font-medium">{t(language, 'funnelStartTitle')}</p>
                <p className="text-xs text-zinc-600">{t(language, 'funnelStartBody')}</p>
                <div className="flex gap-2">
                  <button className={`${quick} bg-zinc-900 text-white ring-zinc-900`} disabled={busy} onClick={() => run(f.key, 'apply')}>{t(language, 'funnelStartYes')}</button>
                  <button className={quick} onClick={() => setConfirm(null)}>{t(language, 'funnelCancel')}</button>
                </div>
              </div>
            )}
            {confirm === stopKey && (
              <div className="flex flex-col gap-2 rounded-md bg-white p-3 ring-1 ring-zinc-300">
                <p className="text-sm font-medium">{t(language, 'funnelStopTitle')}</p>
                <div className="flex gap-2">
                  <button className={`${quick} bg-red-600 text-white ring-red-600`} disabled={busy} onClick={() => run(f.key, 'stop')}>{t(language, 'funnelStopYes')}</button>
                  <button className={quick} onClick={() => setConfirm(null)}>{t(language, 'funnelCancel')}</button>
                </div>
              </div>
            )}
          </div>
        );
      })}
      {error && <p className="text-xs text-red-700">{error}</p>}
    </section>
  );
}
