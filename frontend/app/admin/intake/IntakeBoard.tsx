'use client';

import { useCallback, useEffect, useState } from 'react';
import type { Language } from '../../lib/types';
import { intakeWhen, it, type IntakePreviewRow, type IntakeSummary } from '../../lib/intake';
import IntakeCard from './IntakeCard';

type Filter = 'all' | 'unsigned' | 'flagged';

const VERDICTS: Record<string, string> = {
  FIRST_VISIT: 'First visit',
  HAS_FORM: 'Already has a form',
  NO_PHONE: 'No phone on file',
  NO_CUSTOMER: 'No customer profile',
};

export default function IntakeBoard({ language }: { language: Language | null }) {
  const [forms, setForms] = useState<IntakeSummary[] | null>(null);
  const [preview, setPreview] = useState<IntakePreviewRow[] | null>(null);
  const [filter, setFilter] = useState<Filter>('all');
  const [failed, setFailed] = useState(false);

  const [tick, setTick] = useState(0);
  const load = useCallback(() => setTick((n) => n + 1), []);

  useEffect(() => {
    let alive = true;
    fetch('/api/intake-forms/upcoming?days=14', { cache: 'no-store' })
      .then(async (r) => {
        if (!r.ok) throw new Error(String(r.status));
        return r.json();
      })
      .then((rows: IntakeSummary[]) => { if (alive) { setForms(rows); setFailed(false); } })
      .catch(() => { if (alive) { setFailed(true); setForms([]); } });
    return () => { alive = false; };
  }, [tick]);

  useEffect(() => {
    let alive = true;
    fetch('/api/intake-forms/preview?hours=48', { cache: 'no-store' })
      .then((r) => (r.ok ? r.json() : null))
      .then((rows) => { if (alive) setPreview(rows); })
      .catch(() => {});
    return () => { alive = false; };
  }, []);

  const shown = (forms ?? []).filter((f) =>
    filter === 'all' ? true : filter === 'unsigned' ? f.status !== 'SIGNED' : f.status === 'SIGNED' && f.needsAttention);
  const chip = (k: Filter, label: string) => (
    <button key={k} type="button" onClick={() => setFilter(k)} aria-pressed={filter === k}
      className={`min-h-9 rounded-full px-3 text-sm ring-1 ${filter === k ? 'bg-zinc-900 text-white ring-zinc-900' : 'text-zinc-700 ring-zinc-300 hover:bg-zinc-50'}`}>
      {label}
    </button>
  );

  return (
    <div className="space-y-8">
      <section>
        <h2 className="mb-3 text-base font-semibold">{it(language, 'upcomingTitle')}</h2>
        <div className="mb-3 flex flex-wrap gap-2">
          {chip('all', it(language, 'all'))}
          {chip('unsigned', it(language, 'unsigned'))}
          {chip('flagged', it(language, 'flagged'))}
        </div>
        {forms === null ? <p className="text-sm text-zinc-500">{it(language, 'loading')}</p>
          : failed ? <p className="text-sm text-red-700">{it(language, 'failed')}</p>
          : shown.length === 0 ? <p className="text-sm text-zinc-500">{it(language, 'none')}</p>
          : (
            <div className="grid gap-3 md:grid-cols-2 2xl:grid-cols-3">
              {shown.map((f) => (
                <div key={f.id} className="min-w-0">
                  <p className="mb-1 text-sm font-medium">{f.firstName ?? 'Client'} <span className="font-normal text-zinc-500">•••• {f.phoneLast4}</span></p>
                  <IntakeCard form={f} language={language} canManage onChanged={load} />
                </div>
              ))}
            </div>
          )}
      </section>

      {preview && (
        <section data-testid="intake-preview">
          <h2 className="text-base font-semibold">{it(language, 'previewTitle')}</h2>
          <p className="mb-3 mt-1 text-xs text-zinc-500">{it(language, 'previewHint')}</p>
          {preview.length === 0 ? <p className="text-sm text-zinc-500">{it(language, 'none')}</p> : (
            <div className="overflow-x-auto rounded-xl border border-zinc-200">
              <table className="w-full min-w-[22rem] text-left text-sm">
                <thead className="bg-zinc-50 text-xs text-zinc-500">
                  <tr><th className="px-3 py-2">{it(language, 'visit')}</th><th className="px-3 py-2">Client</th><th className="px-3 py-2">Detected as</th></tr>
                </thead>
                <tbody>
                  {preview.map((r, i) => (
                    <tr key={i} className="border-t border-zinc-100">
                      <td className="px-3 py-2">{intakeWhen(r.appointmentStartAt, language)}</td>
                      <td className="px-3 py-2">{r.firstInitial}. •••• {r.phoneLast4}</td>
                      <td className="px-3 py-2">{VERDICTS[r.verdict] ?? r.verdict}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}
    </div>
  );
}
