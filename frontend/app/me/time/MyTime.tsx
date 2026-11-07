'use client';

import { useCallback, useEffect, useState } from 'react';
import type { Language } from '../../lib/types';
import { t } from '../../lib/i18n';
import { type DayView, type Waiver, hhmm, hrs, laToIso, tcFetch, usd } from '../../lib/timecards';

type EarningsDto = { from: string; to: string; timeBased: { totalMinutes: number; overtimeMinutes: number; doubleTimeMinutes: number; grossWagesCents: number; tipsCents: number; totalToPayCents: number; unapprovedDays: number }; commissionComparisonCents: number | null };

function Day({ d, waiverSigned, lang, onDone }: { d: DayView; waiverSigned: boolean; lang: Language; onDone: () => void }) {
  const card = d.timecards[0];
  const meal = card?.breaks.find((b) => b.kind === 'UNPAID');
  const [start, setStart] = useState(card ? hhmm(card.start) : '');
  const [end, setEnd] = useState(card ? hhmm(card.end) : '');
  const [mealStart, setMealStart] = useState(meal ? hhmm(meal.start) : '');
  const [mealEnd, setMealEnd] = useState(meal ? hhmm(meal.end) : '');
  const [rests, setRests] = useState(d.restConfirmed);
  const [waiver, setWaiver] = useState(d.mealWaiver);
  const [note, setNote] = useState(d.note ?? '');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  const locked = d.status === 'APPROVED';
  const editable = d.source === 'DRAFT' && !locked;

  const submit = async () => {
    setBusy(true);
    setErr('');
    try {
      await tcFetch<DayView>('me/submit', 'POST', {
        date: d.date,
        ...(editable && start && end ? { start: laToIso(d.date, start), end: laToIso(d.date, end) } : {}),
        ...(editable && mealStart && mealEnd ? { mealStart: laToIso(d.date, mealStart), mealEnd: laToIso(d.date, mealEnd) } : {}),
        mealWaiver: waiver,
        restsTaken: rests,
        note: note || null,
      });
      onDone();
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  const field = 'rounded border border-zinc-300 px-2 py-1 text-sm';
  return (
    <li className="rounded-lg border border-zinc-200 bg-white p-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <div className="font-medium">{d.date}</div>
        <span className="rounded bg-zinc-100 px-2 py-0.5 text-xs">{t(lang, `tcStatus${d.status}` as 'tcStatusAPPROVED')}</span>
      </div>
      <div className="mt-1 text-sm">
        {t(lang, 'tcPaid')}: {hrs(d.paidMinutes)} {t(lang, 'tcHours')}
        {d.payCents != null && <span className="text-zinc-600"> · {usd(d.payCents)}{d.premiumCents ? ` + ${usd(d.premiumCents)}` : ''}</span>}
      </div>
      {!locked && (
        <div className="mt-3 flex flex-col gap-3">
          {editable && (
            <div className="flex flex-wrap gap-3">
              <label className="flex flex-col gap-1 text-xs">{t(lang, 'tcStart')}<input className={field} type="time" value={start} onChange={(e) => setStart(e.target.value)} /></label>
              <label className="flex flex-col gap-1 text-xs">{t(lang, 'tcEnd')}<input className={field} type="time" value={end} onChange={(e) => setEnd(e.target.value)} /></label>
              <label className="flex flex-col gap-1 text-xs">{t(lang, 'tcMealStart')}<input className={field} type="time" value={mealStart} onChange={(e) => setMealStart(e.target.value)} /></label>
              <label className="flex flex-col gap-1 text-xs">{t(lang, 'tcMealEnd')}<input className={field} type="time" value={mealEnd} onChange={(e) => setMealEnd(e.target.value)} /></label>
            </div>
          )}
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={rests} onChange={(e) => setRests(e.target.checked)} />{t(lang, 'tcRestsTaken')}</label>
          {waiverSigned && d.paidMinutes <= 360 && (
            <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={waiver} onChange={(e) => setWaiver(e.target.checked)} />{t(lang, 'tcUseWaiver')}</label>
          )}
          <label className="flex flex-col gap-1 text-xs">{t(lang, 'tcNote')}<input className={field} value={note} onChange={(e) => setNote(e.target.value)} /></label>
          <div className="flex items-center gap-3">
            <button disabled={busy} onClick={submit} className="rounded bg-zinc-900 px-3 py-1.5 text-sm text-white disabled:opacity-50">{t(lang, 'tcConfirmDay')}</button>
            {err && <span className="text-sm text-red-700">{err}</span>}
          </div>
        </div>
      )}
    </li>
  );
}

function WaiverBlock({ lang, w, reload }: { lang: Language; w: Waiver; reload: () => void }) {
  const [name, setName] = useState('');
  const [err, setErr] = useState('');
  const act = async (path: string, body: object) => {
    setErr('');
    try {
      await tcFetch<Waiver>(path, 'POST', body);
      reload();
    } catch (e) {
      setErr((e as Error).message);
    }
  };
  return (
    <details className="mb-5 rounded-lg border border-zinc-200 bg-white p-4">
      <summary className="cursor-pointer text-sm font-medium">
        {t(lang, 'tcWaiverTitle')}: {w.signed ? `${t(lang, 'tcWaiverSigned')} (${w.signedName})` : 'not signed'}
      </summary>
      <pre className="mt-3 whitespace-pre-wrap text-xs text-zinc-700">{w.text}</pre>
      {w.signed ? (
        <button onClick={() => act('me/waiver/revoke', {})} className="mt-3 rounded border border-zinc-400 px-3 py-1.5 text-sm">{t(lang, 'tcWaiverRevoke')}</button>
      ) : (
        <div className="mt-3 flex flex-wrap items-end gap-2">
          <label className="flex flex-col gap-1 text-xs">{t(lang, 'tcWaiverSign')}
            <input className="rounded border border-zinc-300 px-2 py-1 text-sm" value={name} onChange={(e) => setName(e.target.value)} />
          </label>
          <button disabled={name.trim().length < 3} onClick={() => act('me/waiver/sign', { signedName: name })} className="rounded bg-zinc-900 px-3 py-1.5 text-sm text-white disabled:opacity-50">{t(lang, 'tcWaiverSignBtn')}</button>
        </div>
      )}
      {err && <div className="mt-2 text-sm text-red-700">{err}</div>}
    </details>
  );
}

export default function MyTime({ language }: { language: Language }) {
  const [days, setDays] = useState<DayView[] | null>(null);
  const [waiver, setWaiver] = useState<Waiver | null>(null);
  const [earn, setEarn] = useState<EarningsDto | null>(null);
  const [err, setErr] = useState('');
  const load = useCallback(() => {
    const n = new Date();
    const half = n.getDate() <= 15 ? 1 : 2;
    Promise.all([
      tcFetch<DayView[]>('me/days?days=21'),
      tcFetch<Waiver>('me/waiver'),
      tcFetch<EarningsDto>(`me/earnings?year=${n.getFullYear()}&month=${n.getMonth() + 1}&half=${half}`),
    ])
      .then(([d, w, e]) => { setDays(d); setWaiver(w); setEarn(e); })
      .catch((e) => setErr(e.message));
  }, []);
  useEffect(load, [load]);
  if (err) return <p className="text-sm text-red-700">{err}</p>;
  return (
    <div>
      {earn && (
        <div className="mb-5 rounded-lg border border-zinc-200 bg-white p-4 text-sm">
          <div className="font-medium">{t(language, 'tcEarnings')} ({earn.from} to {earn.to})</div>
          <div className="mt-1">
            {hrs(earn.timeBased.totalMinutes)} {t(language, 'tcHours')} · {usd(earn.timeBased.grossWagesCents)} + tips {usd(earn.timeBased.tipsCents)} = <b>{usd(earn.timeBased.totalToPayCents)}</b>
          </div>
          {earn.commissionComparisonCents != null && <div className="mt-1 text-zinc-600">Commission scheme: {usd(earn.commissionComparisonCents)}</div>}
        </div>
      )}
      {waiver && <WaiverBlock lang={language} w={waiver} reload={load} />}
      <ul className="flex flex-col gap-3">
        {days?.map((d) => <Day key={d.date + d.status} d={d} waiverSigned={waiver?.signed ?? false} lang={language} onDone={load} />)}
      </ul>
    </div>
  );
}
