'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import type { Language } from '../lib/types';
import { t, tf } from '../lib/i18n';
import VoidPanel from '../timecards/VoidPanel';
import BreakQuestions, { complete, initStates, toPayload } from '../timecards/BreakQuestions';
import { type DayView, type ProviderSummary, type Waiver, hhmm, hrs, laToIso, tcFetch, usd } from '../lib/timecards';

type Earnings = { from: string; to: string; timeBased: ProviderSummary; commissionComparisonCents: number | null };
type Tone = 'approved' | 'waiting' | 'confirm' | 'review' | 'today' | 'off' | 'voided';

const TONE: Record<Tone, { chip: string; dot: string }> = {
  approved: { chip: 'bg-emerald-50 text-emerald-800 ring-emerald-200', dot: 'bg-emerald-500' },
  waiting: { chip: 'bg-sky-50 text-sky-800 ring-sky-200', dot: 'bg-sky-500' },
  confirm: { chip: 'bg-amber-50 text-amber-800 ring-amber-300', dot: 'bg-amber-400' },
  review: { chip: 'bg-red-50 text-red-800 ring-red-200', dot: 'bg-red-500' },
  today: { chip: 'bg-zinc-100 text-zinc-700 ring-zinc-200', dot: 'bg-zinc-400' },
  off: { chip: 'bg-zinc-50 text-zinc-400 ring-zinc-100', dot: 'bg-zinc-200' },
  voided: { chip: 'bg-zinc-100 text-zinc-500 ring-zinc-200', dot: 'bg-zinc-300' },
};

function pad(n: number) { return String(n).padStart(2, '0'); }
function ymd(y: number, m: number, d: number) { return `${y}-${pad(m)}-${pad(d)}`; }
function todayLa(): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Los_Angeles' }).format(new Date());
}

function toneOf(d: DayView | undefined, date: string, today: string): Tone {
  if (date > today) return 'off';
  if (!d) return 'off';
  if (d.source === 'VOIDED') return 'voided';
  if (date === today) return d.status === 'APPROVED' ? 'approved' : 'today';
  if (d.status === 'APPROVED' || d.status === 'CONFIRMED_PENDING_WRITE') return 'approved';
  if (d.status === 'WORKER_CONFIRMED') return 'waiting';
  if (d.status === 'NEEDS_REVIEW') return 'review';
  return 'confirm';
}

function label(tone: Tone, lang: Language): string {
  const k = { approved: 'payApproved', waiting: 'payWaiting', confirm: 'payConfirm', review: 'payReview', today: 'payToday', off: 'payOff', voided: 'payVoided' } as const;
  return t(lang, k[tone]);
}

function weekday(date: string, lang: Language) {
  const d = new Date(`${date}T12:00:00Z`);
  const loc = lang === 'RU' ? 'ru-RU' : 'en-US';
  return {
    wd: new Intl.DateTimeFormat(loc, { weekday: 'short', timeZone: 'UTC' }).format(d),
    md: new Intl.DateTimeFormat(loc, { month: 'short', day: 'numeric', timeZone: 'UTC' }).format(d),
  };
}

function DayRow({ d, tone, waiverSigned, lang, open, onToggle, onDone }: {
  d: DayView; tone: Tone; waiverSigned: boolean; lang: Language; open: boolean; onToggle: () => void; onDone: () => void;
}) {
  const card = d.timecards[0];
  const [start, setStart] = useState(card ? hhmm(card.start) : '');
  const [end, setEnd] = useState(card ? hhmm(card.end) : '');
  const [qs, setQs] = useState(() => initStates(d.breakQuestions ?? [], d.workerAnswers ?? []));
  const [waiver, setWaiver] = useState(d.mealWaiver);
  const [note, setNote] = useState(d.note ?? '');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  const [voiding, setVoiding] = useState(false);
  const locked = d.status === 'APPROVED' || tone === 'today';
  const editable = d.source === 'DRAFT' && !locked;
  const voided = d.source === 'VOIDED';
  const manual = d.source === 'DRAFT' && d.appointments.length === 0 && d.status !== 'APPROVED' && tone !== 'today';
  const { wd, md } = weekday(d.date, lang);
  const field = 'min-h-[44px] w-full rounded-md border border-zinc-300 bg-white px-3 py-2 text-base focus:outline-none focus-visible:ring-2 focus-visible:ring-zinc-900 sm:text-sm';
  const waived = waiver && d.paidMinutes <= 360;
  const shownIdx = (d.breakQuestions ?? []).map((q, i) => i).filter((i) => !(waived && d.breakQuestions[i].kind === 'MEAL'));
  const shownQs = shownIdx.map((i) => d.breakQuestions[i]);
  const shownStates = shownIdx.map((i) => qs[i]);
  const ready = complete(shownStates);

  const withdraw = async (reason: string) => {
    setBusy(true);
    setErr('');
    try {
      await tcFetch<DayView>('me/void', 'POST', { date: d.date, reason });
      onDone();
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const submit = async () => {
    setBusy(true);
    setErr('');
    try {
      await tcFetch<DayView>('me/submit', 'POST', {
        date: d.date,
        ...(editable && start && end ? { start: laToIso(d.date, start), end: laToIso(d.date, end) } : {}),
        mealWaiver: waiver,
        restsTaken: shownQs.every((q) => q.kind !== 'REST') || shownStates.filter((x) => x.kind === 'REST').every((x) => x.answer !== 'NO'),
        answers: toPayload(d.date, shownStates),
        note: note || null,
      });
      onDone();
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <li className={`rounded-lg border ${tone === 'confirm' || tone === 'review' ? 'border-amber-300 bg-amber-50/40' : 'border-zinc-200 bg-white'}`}>
      <button type="button" onClick={onToggle} aria-expanded={open} className="flex w-full items-center gap-3 px-3 py-2.5 text-left">
        <div className="w-14 shrink-0">
          <div className="text-xs uppercase text-zinc-400">{wd}</div>
          <div className="text-sm font-medium">{md}</div>
        </div>
        <div className="min-w-0 flex-1 text-sm">
          {card && <div className="tabular-nums">{hhmm(card.start)} – {hhmm(card.end)}</div>}
          <div className="text-xs text-zinc-500 tabular-nums">
            {hrs(d.paidMinutes)} {t(lang, 'tcHours')}
            {d.overtimeMinutes > 0 && ` · OT ${hrs(d.overtimeMinutes + d.doubleTimeMinutes)}`}
            {d.payCents != null && ` · ${usd(d.payCents + (d.premiumCents ?? 0))}`}
          </div>
        </div>
        <span className={`shrink-0 rounded-full px-2 py-0.5 text-xs ring-1 ${TONE[tone].chip}`}>{label(tone, lang)}</span>
      </button>
      {open && (
        <div className="flex flex-col gap-3 border-t border-zinc-100 px-3 py-3 text-sm">
          {d.timecards.flatMap((c) => c.breaks).length > 0 && (
            <div className="text-xs text-zinc-600">
              {t(lang, 'payBreaks')}:{' '}
              {d.timecards.flatMap((c) => c.breaks).map((b) => `${b.kind === 'UNPAID' ? t(lang, 'payMeal') : t(lang, 'payRest')} ${hhmm(b.start)}–${hhmm(b.end)}`).join(', ')}
            </div>
          )}
          {d.findings.filter((f) => f.severity !== 'INFO').map((f, i) => (
            <div key={i} className="rounded-md bg-amber-50 px-2 py-1 text-xs text-amber-800">{f.message}</div>
          ))}
          {d.premiumCents ? <div className="text-xs text-zinc-600">{t(lang, 'payPremium')}: {usd(d.premiumCents)}</div> : null}
          {voided ? (
            <div className="text-xs text-zinc-500">
              {t(lang, 'payVoidedNote')}
              {d.voidReason ? ` ${t(lang, 'tcVoidedReason')}: ${d.voidReason}` : ''}
            </div>
          ) : locked ? (
            tone === 'approved' && <div className="text-xs text-zinc-500">{t(lang, 'payLocked')}</div>
          ) : (
            <>
              {editable && (
                <div className="grid grid-cols-2 gap-3">
                  <label className="flex min-w-0 flex-col gap-1 text-xs">{t(lang, 'tcStart')}<input className={field} type="time" value={start} onChange={(e) => setStart(e.target.value)} /></label>
                  <label className="flex min-w-0 flex-col gap-1 text-xs">{t(lang, 'tcEnd')}<input className={field} type="time" value={end} onChange={(e) => setEnd(e.target.value)} /></label>
                </div>
              )}
              {shownQs.length > 0 ? (
                <BreakQuestions lang={lang} questions={shownQs} states={shownStates} onChange={(ns) => setQs(qs.map((q, i) => { const k = shownIdx.indexOf(i); return k >= 0 ? ns[k] : q; }))} />
              ) : (
                <p className="text-xs text-zinc-600">{t(lang, 'tcBqNoQuestions')}</p>
              )}
              {waiverSigned && d.paidMinutes <= 360 && (
                <label className="flex min-h-[44px] items-center gap-3"><input className="h-5 w-5 shrink-0" type="checkbox" checked={waiver} onChange={(e) => setWaiver(e.target.checked)} />{t(lang, 'tcUseWaiver')}</label>
              )}
              <label className="flex flex-col gap-1 text-xs">{t(lang, 'tcNote')}<input className={field} value={note} onChange={(e) => setNote(e.target.value)} /></label>
              <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:gap-3">
                <button disabled={busy || !ready} onClick={submit} className="min-h-[44px] w-full rounded-md bg-zinc-900 px-4 py-2.5 text-sm text-white focus:outline-none focus-visible:ring-2 focus-visible:ring-zinc-900 focus-visible:ring-offset-2 disabled:opacity-50 sm:w-auto">{t(lang, 'tcConfirmDay')}</button>
                {manual && !voiding && (
                  <button type="button" disabled={busy} onClick={() => setVoiding(true)} className="min-h-[44px] w-full rounded-md border border-red-300 px-4 py-2.5 text-sm text-red-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-red-600 disabled:opacity-50 sm:w-auto">{t(lang, 'payWithdraw')}</button>
                )}
                {!voiding && err && <span className="text-sm text-red-700">{err}</span>}
              </div>
              {voiding && <VoidPanel lang={lang} hint={t(lang, 'payWithdrawHint')} busy={busy} err={err} onClose={() => setVoiding(false)} onConfirm={withdraw} />}
            </>
          )}
        </div>
      )}
    </li>
  );
}

function AddShift({ lang, from, to, onDone }: { lang: Language; from: string; to: string; onDone: () => void }) {
  const [open, setOpen] = useState(false);
  const [date, setDate] = useState('');
  const [start, setStart] = useState('');
  const [end, setEnd] = useState('');
  const [note, setNote] = useState('');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  const field = 'min-h-[44px] w-full rounded-md border border-zinc-300 bg-white px-3 py-2 text-base focus:outline-none focus-visible:ring-2 focus-visible:ring-zinc-900 sm:text-sm';
  const send = async () => {
    setBusy(true);
    setErr('');
    try {
      await tcFetch<DayView>('me/submit', 'POST', { date, start: laToIso(date, start), end: laToIso(date, end), mealWaiver: false, restsTaken: false, note });
      setOpen(false);
      setDate(''); setStart(''); setEnd(''); setNote('');
      onDone();
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  if (!open) {
    return <button type="button" onClick={() => setOpen(true)} className="mt-3 inline-flex min-h-[44px] items-center text-sm text-zinc-700 underline">{t(lang, 'payAddShift')}</button>;
  }
  return (
    <div className="mt-3 flex flex-col gap-2 rounded-md border border-zinc-200 p-3">
      <p className="text-xs text-zinc-500">{t(lang, 'payAddShiftHint')}</p>
      <div className="flex flex-wrap gap-3">
        <label className="flex flex-col gap-1 text-xs">{t(lang, 'payAddShiftDate')}<input className={field} type="date" min={from} max={to} value={date} onChange={(e) => setDate(e.target.value)} /></label>
        <label className="flex flex-col gap-1 text-xs">{t(lang, 'tcStart')}<input className={field} type="time" value={start} onChange={(e) => setStart(e.target.value)} /></label>
        <label className="flex flex-col gap-1 text-xs">{t(lang, 'tcEnd')}<input className={field} type="time" value={end} onChange={(e) => setEnd(e.target.value)} /></label>
      </div>
      <label className="flex flex-col gap-1 text-xs">{t(lang, 'tcNote')}<input className={field} value={note} onChange={(e) => setNote(e.target.value)} /></label>
      <div className="flex items-center gap-3">
        <button disabled={busy || !date || !start || !end || !note.trim()} onClick={send} className="rounded-md bg-zinc-900 px-4 py-2 text-sm text-white disabled:opacity-50">{t(lang, 'payAddShiftSend')}</button>
        <button type="button" onClick={() => setOpen(false)} className="text-sm text-zinc-500">×</button>
        {err && <span className="text-sm text-red-700">{err}</span>}
      </div>
    </div>
  );
}

function PeriodCard({ title, range, from, to, earn, byDate, today, waiverSigned, lang, openDate, setOpenDate, reload }: {
  title: string; range: string; from: string; to: string; earn: Earnings | undefined; byDate: Map<string, DayView>; today: string;
  waiverSigned: boolean; lang: Language; openDate: string | null; setOpenDate: (d: string | null) => void; reload: () => void;
}) {
  const dates: string[] = [];
  for (let d = new Date(`${from}T12:00:00Z`); d.toISOString().slice(0, 10) <= to; d.setUTCDate(d.getUTCDate() + 1)) dates.push(d.toISOString().slice(0, 10));
  const rows = dates.filter((d) => byDate.has(d) && d <= today).reverse();
  const counts = { approved: 0, waiting: 0, confirm: 0, review: 0 } as Record<string, number>;
  for (const d of rows) {
    const tn = toneOf(byDate.get(d), d, today);
    if (tn in counts) counts[tn]++;
  }
  const s = earn?.timeBased;
  return (
    <section className="rounded-lg border border-zinc-200 bg-white p-4">
      <div className="flex items-baseline justify-between">
        <h2 className="text-base font-semibold">{title}</h2>
        <span className="text-xs text-zinc-500">{range}</span>
      </div>
      {s && (
        <>
          <div className="mt-3 text-2xl font-semibold tabular-nums">{usd(s.totalToPayCents)}</div>
          <div className="text-xs text-zinc-500">
            {s.unapprovedDays > 0 ? tf(lang, 'payEstimate', { n: s.unapprovedDays }) : t(lang, 'payFinal')}
          </div>
          <dl className="mt-3 grid grid-cols-2 gap-3 text-sm sm:grid-cols-4">
            <Stat label={t(lang, 'payHours')} value={hrs(s.totalMinutes)} />
            <Stat label={t(lang, 'payOvertime')} value={hrs(s.overtimeMinutes + s.doubleTimeMinutes)} />
            <Stat label={t(lang, 'payWages')} value={usd(s.grossWagesCents)} />
            <Stat label={t(lang, 'payTips')} value={usd(s.tipsCents)} />
          </dl>
          {earn?.commissionComparisonCents != null && (
            <div className="mt-2 text-xs text-zinc-500">Commission scheme: {usd(earn.commissionComparisonCents)}</div>
          )}
        </>
      )}
      <div className="mt-4" aria-label={t(lang, 'payLegend')}>
        <div className="flex flex-wrap gap-1">
          {dates.map((d) => {
            const tn = toneOf(byDate.get(d), d, today);
            return (
              <button
                key={d}
                type="button"
                disabled={!byDate.has(d) || d > today}
                onClick={() => setOpenDate(openDate === d ? null : d)}
                title={`${d}: ${label(tn, lang)}`}
                className={`flex h-11 w-11 items-center justify-center rounded text-xs focus:outline-none focus-visible:ring-2 focus-visible:ring-zinc-900 tabular-nums ${tn === 'off' ? 'bg-zinc-50 text-zinc-300' : 'text-white'} ${tn === 'off' ? '' : TONE[tn].dot} ${openDate === d ? 'ring-2 ring-zinc-900' : ''}`}
              >
                {Number(d.slice(8))}
              </button>
            );
          })}
        </div>
        <div className="mt-2 flex flex-wrap gap-x-3 gap-y-1 text-xs text-zinc-600">
          {(['approved', 'waiting', 'confirm', 'review'] as const).map((k) => counts[k] > 0 && (
            <span key={k} className="inline-flex items-center gap-1"><i className={`inline-block h-2 w-2 rounded-full ${TONE[k].dot}`} />{label(k, lang)}: {counts[k]}</span>
          ))}
        </div>
      </div>
      <AddShift lang={lang} from={from} to={to < today ? to : today} onDone={reload} />
      {rows.length === 0 ? (
        <p className="mt-4 text-sm text-zinc-400">{t(lang, 'payNoDays')}</p>
      ) : (
        <ul className="mt-4 flex flex-col gap-2">
          {rows.map((d) => (
            <DayRow key={d + byDate.get(d)!.status} d={byDate.get(d)!} tone={toneOf(byDate.get(d), d, today)} waiverSigned={waiverSigned}
              lang={lang} open={openDate === d} onToggle={() => setOpenDate(openDate === d ? null : d)} onDone={reload} />
          ))}
        </ul>
      )}
    </section>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-xs text-zinc-500">{label}</dt>
      <dd className="font-medium tabular-nums">{value}</dd>
    </div>
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
    <details className="mt-4 rounded-lg border border-zinc-200 bg-white p-4">
      <summary className="flex min-h-[44px] cursor-pointer items-center text-sm font-medium">
        {t(lang, 'tcWaiverTitle')}: {w.signed ? `${t(lang, 'tcWaiverSigned')} (${w.signedName})` : 'not signed'}
      </summary>
      <pre className="mt-3 whitespace-pre-wrap text-xs text-zinc-700">{w.text}</pre>
      {w.signed ? (
        <button onClick={() => act('me/waiver/revoke', {})} className="mt-3 min-h-[44px] rounded border border-zinc-400 px-3 py-1.5 text-sm">{t(lang, 'tcWaiverRevoke')}</button>
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

export default function MyPay({ language, year, month }: { language: Language; year: number; month: number }) {
  const [days, setDays] = useState<DayView[] | null>(null);
  const [waiver, setWaiver] = useState<Waiver | null>(null);
  const [earn, setEarn] = useState<[Earnings, Earnings] | null>(null);
  const [err, setErr] = useState('');
  const [openDate, setOpenDate] = useState<string | null>(null);
  const today = todayLa();
  const last = new Date(Date.UTC(year, month, 0)).getUTCDate();

  const load = useCallback(() => {
    Promise.all([
      tcFetch<DayView[]>(`me/range?from=${ymd(year, month, 1)}&to=${ymd(year, month, last)}`),
      tcFetch<Waiver>('me/waiver'),
      tcFetch<Earnings>(`me/earnings?year=${year}&month=${month}&half=1`),
      tcFetch<Earnings>(`me/earnings?year=${year}&month=${month}&half=2`),
    ])
      .then(([d, w, e1, e2]) => { setDays(d); setWaiver(w); setEarn([e1, e2]); })
      .catch((e) => setErr(e.message));
  }, [year, month, last]);
  useEffect(() => { setDays(null); setEarn(null); load(); }, [load]);

  const byDate = useMemo(() => new Map((days ?? []).map((d) => [d.date, d])), [days]);
  if (err) return <p className="text-sm text-red-700">{err}</p>;
  if (!days || !earn || !waiver) return <p className="text-sm text-zinc-400">…</p>;

  const needYou = days.filter((d) => { const tn = toneOf(d, d.date, today); return tn === 'confirm' || tn === 'review'; }).length;
  const total = earn[0].timeBased.totalToPayCents + earn[1].timeBased.totalToPayCents;
  const minutes = earn[0].timeBased.totalMinutes + earn[1].timeBased.totalMinutes;
  const ot = earn[0].timeBased.overtimeMinutes + earn[0].timeBased.doubleTimeMinutes + earn[1].timeBased.overtimeMinutes + earn[1].timeBased.doubleTimeMinutes;
  const tips = earn[0].timeBased.tipsCents + earn[1].timeBased.tipsCents;
  const mm = pad(month);

  return (
    <div>
      <div className="grid grid-cols-2 gap-4 rounded-lg bg-zinc-900 p-5 text-white sm:grid-cols-4">
        <div>
          <div className="text-xs text-zinc-300">{t(language, 'payMonthTotal')}</div>
          <div className="mt-1 text-2xl font-semibold tabular-nums">{usd(total)}</div>
        </div>
        <div><div className="text-xs text-zinc-300">{t(language, 'payHours')}</div><div className="mt-1 text-xl font-semibold tabular-nums">{hrs(minutes)}</div></div>
        <div><div className="text-xs text-zinc-300">{t(language, 'payOvertime')}</div><div className="mt-1 text-xl font-semibold tabular-nums">{hrs(ot)}</div></div>
        <div><div className="text-xs text-zinc-300">{t(language, 'payTips')}</div><div className="mt-1 text-xl font-semibold tabular-nums">{usd(tips)}</div></div>
      </div>

      <p className={`mt-4 rounded-md px-3 py-2 text-sm ring-1 ${needYou > 0 ? 'bg-amber-50 text-amber-800 ring-amber-300' : 'bg-emerald-50 text-emerald-800 ring-emerald-200'}`}>
        {needYou > 0 ? tf(language, 'payNeedYou', { n: needYou }) : t(language, 'payAllDone')}
      </p>

      <div className="mt-4 grid grid-cols-1 gap-4 lg:grid-cols-2">
        <PeriodCard title="1–15" range={`${mm}/01 – ${mm}/15`} from={ymd(year, month, 1)} to={ymd(year, month, 15)} earn={earn[0]} byDate={byDate}
          today={today} waiverSigned={waiver.signed} lang={language} openDate={openDate} setOpenDate={setOpenDate} reload={load} />
        <PeriodCard title={t(language, 'payPeriodEnd')} range={`${mm}/16 – ${mm}/${last}`} from={ymd(year, month, 16)} to={ymd(year, month, last)} earn={earn[1]} byDate={byDate}
          today={today} waiverSigned={waiver.signed} lang={language} openDate={openDate} setOpenDate={setOpenDate} reload={load} />
      </div>
      <WaiverBlock lang={language} w={waiver} reload={load} />
    </div>
  );
}
