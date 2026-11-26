'use client';

import { useRef, useState } from 'react';
import Link from 'next/link';
import { api } from '../../../lib/api';
import type { AdLeadCampaignMoney, AdLeadMoney, AdLeadRow, GooglePpcData, Margins } from '../../../lib/types';
import { romiPercent } from './romi';
import { CONVERSION_EVENTS } from './conversionEvents';
import { consultationLabel, statusLabel, statusTone } from '../adLeads';
import PeriodFilter from '../PeriodFilter';
import { periodToBounds, periodToCookie, PPC_PERIOD_COOKIE } from '../period';
import type { PeriodSelection } from '../period';

const usd0 = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });
const usd2 = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: 2, maximumFractionDigits: 2 });
const num = (n: number) => n.toLocaleString('en-US');
const dash = '-';

const perUnit = (spend: number, n: number) => (n > 0 ? usd2.format(spend / n) : dash);

export default function GooglePpcView({ initialSelection, initialData, initialLeads, initialMoney, initialMargins, timeZone }: { initialSelection: PeriodSelection; initialData: GooglePpcData | null; initialLeads: AdLeadRow[]; initialMoney: AdLeadMoney | null; initialMargins: Margins | null; timeZone?: string }) {
  const [data, setData] = useState(initialData);
  const [leads, setLeads] = useState(initialLeads);
  const [money, setMoney] = useState(initialMoney);
  const [margins, setMargins] = useState(initialMargins);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(initialData === null);
  const requestId = useRef(0);
  const [selection, setSelection] = useState<PeriodSelection>(initialSelection);

  async function changePeriod(next: PeriodSelection) {
    setSelection(next);
    try {
      document.cookie = `${PPC_PERIOD_COOKIE}=${periodToCookie(next)}; path=/; max-age=31536000; samesite=lax`;
    } catch {
      // cookies blocked: the tab simply opens on its default period next time
    }
    const bounds = periodToBounds(next, timeZone);
    const id = ++requestId.current;
    setLoading(true);
    try {
      const [d, l, m] = await Promise.all([
        api.getGooglePpc(bounds.from, bounds.to),
        api.getGooglePpcLeads(bounds.from, bounds.to).catch(() => [] as AdLeadRow[]),
        api.getGooglePpcMoney(bounds.from, bounds.to).catch(() => null),
      ]);
      if (id !== requestId.current) return;
      setData(d);
      setLeads(l);
      setMoney(m);
      setFailed(false);
    } catch {
      if (id === requestId.current) setFailed(true);
    } finally {
      if (id === requestId.current) setLoading(false);
    }
  }

  return (
    <div className="mt-4 space-y-6">
      <PeriodFilter value={selection} onChange={changePeriod} timeZone={timeZone} disabled={loading} />
      {money && <MoneyCard money={money} loading={loading} />}
      {failed || !data ? (
        <p className="rounded-lg border border-dashed border-zinc-300 p-8 text-center text-sm text-zinc-500">
          Google Ads data could not be loaded. Try again in a moment.
        </p>
      ) : !data.hasSpend ? (
        <p className="rounded-lg border border-dashed border-zinc-300 p-8 text-center text-sm text-zinc-500">
          No Google Ads spend was synced for this period.
        </p>
      ) : (
        <div className={`space-y-6 transition-opacity ${loading ? 'opacity-50' : ''}`} aria-busy={loading}>
          <Kpis data={data} margins={margins} onMargins={setMargins} money={money} leads={leads} />
          <Campaigns data={data} money={money} />
          <Pages data={data} money={money} />
          <Leads leads={leads} timeZone={timeZone} />
        </div>
      )}
    </div>
  );
}

function MoneyCard({ money, loading }: { money: AdLeadMoney; loading: boolean }) {
  const older = money.leftToCollect - money.periodLeft;
  const olderLeads = money.leftLeads - money.periodLeftLeads;
  return (
    <section aria-label="Money from Google Ads leads" className={`rounded-xl bg-white p-4 ring-1 ring-zinc-200 transition-opacity ${loading ? 'opacity-50' : ''}`}>
      <div className="grid grid-cols-1 gap-5 sm:grid-cols-2">
        <div className="min-w-0">
          <div className="text-xs font-medium text-zinc-500">Collected</div>
          <div className="mt-1 text-3xl font-semibold tabular-nums text-zinc-900">{usd0.format(money.collected)}</div>
          <div className="mt-1 text-xs text-zinc-500">Paid so far by the Google Ads leads of this period, deposits included.</div>
        </div>
        <div className="min-w-0 sm:border-l sm:border-zinc-100 sm:pl-4">
          <div className="text-xs font-medium text-zinc-500">Left to collect</div>
          <div className={`mt-1 text-3xl font-semibold tabular-nums ${money.periodLeft > 0 ? 'text-amber-700' : 'text-zinc-900'}`}>{usd0.format(money.periodLeft)}</div>
          <div className="mt-1 text-xs text-zinc-500">
            {money.periodLeftLeads > 0
              ? `Still to pay on procedures these leads already booked (${num(money.periodLeftLeads)} ${money.periodLeftLeads === 1 ? 'lead' : 'leads'}). The procedures cost ${usd0.format(money.periodEstimate)} and ${usd0.format(money.periodPrepaid)} is already paid.`
              : 'These leads have no booked procedure with a balance to pay.'}
          </div>
        </div>
      </div>
      {(older > 0 || money.partial) && (
        <p className="mt-4 border-t border-zinc-100 pt-3 text-xs text-zinc-500">
          {older > 0 && `Not included: ${usd0.format(older)} left to collect on ${num(olderLeads)} Google Ads ${olderLeads === 1 ? 'lead' : 'leads'} from earlier periods. `}
          {money.partial && 'Some booked services have no price yet, so Left to collect may be higher.'}
        </p>
      )}
    </section>
  );
}

function Kpi({ label, value, sub, className = '' }: { label: string; value: string; sub?: string; className?: string }) {
  return (
    <div className={`min-w-0 rounded-xl bg-white p-3 ring-1 ring-zinc-200 sm:p-4 ${className}`}>
      <div className="text-xs font-medium text-zinc-500">{label}</div>
      <div className="mt-1 text-xl font-semibold tabular-nums text-zinc-900 sm:text-2xl">{value}</div>
      {sub && <div className="mt-0.5 text-xs text-zinc-500">{sub}</div>}
    </div>
  );
}

function Kpis({ data, margins, onMargins, money, leads }: { data: GooglePpcData; margins: Margins | null; onMargins: (m: Margins | null) => void; money: AdLeadMoney | null; leads: AdLeadRow[] }) {
  const r = data.results;
  // One source of truth for revenue: what this period's Google Ads leads have paid since they came in, the same
  // number as Collected above. Falls back to the contact-capture figure only when the money block failed to load.
  const revenue = money ? money.collected : r.revenue;
  const payers = money ? leads.filter((l) => (l.collected ?? 0) > 0).length : r.payingCustomers;
  const leadsCount = (money?.campaigns ?? []).reduce((t, m) => t + m.leads, 0);
  const roi = data.spend > 0 ? ((revenue - data.spend) / data.spend) * 100 : null;
  const roas = data.spend > 0 ? revenue / data.spend : null;
  // Collected plus the balances still to pay on procedures these leads already booked (one more procedure of materials each).
  const left = money ? money.periodLeft : 0;
  const romiAhead = margins
    ? romiPercent(revenue + left, data.spend, r.deposits + (money ? money.periodLeftLeads : 0), margins)
    : data.spend > 0 ? ((revenue + left - data.spend) / data.spend) * 100 : null;
  return (
    <section aria-label="Google Ads results">
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-3">
        <Kpi label="Spend" value={usd0.format(data.spend)} sub={`${num(data.clicks)} clicks, ${num(data.impressions)} impressions`} />
        <Kpi label="CAC" value={money ? perUnit(data.spend, money.payers) : dash} sub={money ? (money.payers > 0 ? `Spend per new paying customer, ${num(money.payers)} ${money.payers === 1 ? 'customer' : 'customers'}` : 'No paying customers among this period\'s leads yet') : 'Not loaded'} />
        <Kpi label="Cost per lead" value={money ? perUnit(data.spend, leadsCount) : dash} sub={money ? `${num(leadsCount)} ${leadsCount === 1 ? 'lead' : 'leads'} created in the period` : 'Not loaded'} />
        <Kpi label="Paying customers" value={money ? num(money.payers) : dash} sub={money && leadsCount > 0 ? `${((money.payers / leadsCount) * 100).toFixed(0)}% of the leads paid` : 'Leads that paid since they came in'} />
        <Kpi label="Cost per click" value={perUnit(data.spend, data.clicks)} sub={`${num(r.visitors)} landing page visitors`} />
        <Kpi label="Revenue" value={usd0.format(revenue)} sub={`${num(payers)} paying ${payers === 1 ? 'lead' : 'leads'}, same as Collected`} />
        <Kpi label="ROAS" value={roas === null ? dash : `${roas.toFixed(2)}x`} sub="Revenue divided by spend" />
        <Kpi label="ROI" value={roi === null ? dash : `${roi.toFixed(0)}%`} sub="(Revenue - spend) / spend" />
        <Kpi
          className="col-span-2 lg:col-span-1"
          label="ROMI with left to collect"
          value={romiAhead === null ? dash : `${romiAhead.toFixed(0)}%`}
          sub={`${usd0.format(revenue)} collected + ${usd0.format(left)} left to collect, minus spend${margins ? ' and costs (materials, master, Square fees; approx.)' : '. Set cost assumptions below to include costs'}`}
        />
      </div>
      <div className="mt-3 rounded-xl bg-white p-3 ring-1 ring-zinc-200 sm:p-4">
        <h2 className="text-sm font-semibold text-zinc-900">Conversions</h2>
        <p className="mt-0.5 text-xs text-zinc-500">Each conversion with the event that fires it. Google Ads shows its own count of the same event, to check against.</p>
        <dl className="mt-3 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4 2xl:grid-cols-7">
          {CONVERSION_EVENTS.map((e) => {
            const ours = e.key === 'sms' ? r.smsReceived : e.key === 'smsTaps' ? r.smsTaps : e.key === 'calls' ? r.callTaps : e.key === 'ringostat' ? r.ringostatCalls
              : e.key === 'consultations' ? r.consultations : e.key === 'deposits' ? (money ? money.siteDeposits : null) : r.forms;
            const google = data.googleByEvent?.[e.event];
            const same = google !== undefined && ours !== null && Math.round(google) === ours;
            return (
              <div key={e.key} className="flex min-w-0 flex-col rounded-lg bg-zinc-50 p-3 ring-1 ring-zinc-200" title={e.what}>
                <dt className="flex items-baseline justify-between gap-2 text-xs font-semibold text-zinc-800">{e.label}{e.secondary && <span className="text-[10px] font-normal uppercase tracking-wide text-zinc-400">secondary</span>}</dt>
                <dd className="mt-1 text-2xl font-semibold tabular-nums text-zinc-900">{ours === null ? dash : num(ours)}</dd>
                <dd className="text-xs text-zinc-500">{ours === null ? 'Not loaded' : e.secondary ? 'not in the total' : `${perUnit(data.spend, ours)} each`}</dd>
                <dd className="mt-2"><code className="break-all rounded bg-white px-1.5 py-0.5 text-[11px] text-zinc-700 ring-1 ring-zinc-200">{e.event}</code></dd>
                <dd className={`mt-1 text-[11px] leading-snug ${google === undefined ? 'text-zinc-400' : same ? 'text-emerald-700' : 'text-amber-700'}`}>
                  Google Ads: {google === undefined ? 'no data' : num(Math.round(google))}{same ? ' (matches)' : ''}
                </dd>
              </div>
            );
          })}
        </dl>
        <details className="mt-3 text-xs text-zinc-500">
          <summary className="cursor-pointer font-medium text-zinc-700">What fires each event</summary>
          <ul className="mt-2 space-y-1">
            {CONVERSION_EVENTS.map((e) => <li key={e.key}><strong className="font-medium text-zinc-800">{e.label}</strong> <code className="text-[11px]">{e.event}</code>: {e.what}.</li>)}
          </ul>
          <p className="mt-2">Google also counts other actions (page visits, local actions) that are not in this list, so its total can be higher.</p>
        </details>
      </div>
      <MarginsEditor margins={margins} onChange={onMargins} />
      <p className="mt-2 text-xs text-zinc-500">
        Revenue is everything the Google Ads leads of this period have paid in Square since they came in.
      </p>
    </section>
  );
}

/** Light, open editor for the rough costs behind ROMI: anyone can change or clear them at any time. */
function MarginsEditor({ margins, onChange }: { margins: Margins | null; onChange: (m: Margins | null) => void }) {
  const [open, setOpen] = useState(false);
  const [material, setMaterial] = useState(String(margins?.materialPerProcedure ?? 50));
  const [master, setMaster] = useState(String(margins?.masterPct ?? 45));
  const [square, setSquare] = useState(String(margins?.squarePct ?? 3));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const m = Number(material), a = Number(master), q = Number(square);
  const valid = [m, a, q].every((n) => Number.isFinite(n) && n >= 0) && a + q < 100 && m <= 10000 && material !== '' && master !== '' && square !== '';

  async function save() {
    setBusy(true);
    setError(null);
    try {
      onChange(await api.saveMargins({ materialPerProcedure: m, masterPct: a, squarePct: q }));
      setOpen(false);
    } catch {
      setError('Could not save. Check the numbers and try again.');
    } finally {
      setBusy(false);
    }
  }

  async function clear() {
    setBusy(true);
    setError(null);
    try {
      await api.clearMargins();
      onChange(null);
      setOpen(false);
    } catch {
      setError('Could not clear. Try again in a moment.');
    } finally {
      setBusy(false);
    }
  }

  const summary = margins
    ? `Materials ${usd0.format(margins.materialPerProcedure)} per procedure, master ${margins.masterPct}%, Square ${margins.squarePct}%`
    : 'No cost assumptions set';
  const field = 'mt-1 w-full rounded-lg border border-zinc-300 px-3 py-2 text-base text-zinc-900 sm:text-sm';

  return (
    <div className="mt-3 rounded-xl bg-zinc-50 px-3 py-2 text-xs text-zinc-600">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="min-w-0 break-words">ROMI assumptions: {summary}</span>
        <button type="button" onClick={() => setOpen((v) => !v)} aria-expanded={open} className="min-h-[36px] rounded-lg bg-white px-3 py-1.5 text-xs font-medium text-zinc-800 ring-1 ring-zinc-200">
          {open ? 'Close' : 'Edit'}
        </button>
      </div>
      {open && (
        <div className="mt-3 space-y-3">
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
            <label className="block text-xs text-zinc-600" htmlFor="margin-material">Materials per procedure ($)
              <input id="margin-material" inputMode="decimal" value={material} onChange={(e) => setMaterial(e.target.value)} className={field} />
            </label>
            <label className="block text-xs text-zinc-600" htmlFor="margin-master">Master share (% of revenue)
              <input id="margin-master" inputMode="decimal" value={master} onChange={(e) => setMaster(e.target.value)} className={field} />
            </label>
            <label className="block text-xs text-zinc-600" htmlFor="margin-square">Square fee (% of revenue)
              <input id="margin-square" inputMode="decimal" value={square} onChange={(e) => setSquare(e.target.value)} className={field} />
            </label>
          </div>
          <p className="text-zinc-500">
            Rough numbers. Materials are counted once per customer who paid a deposit. Rent, insurance and other overhead are not included.
            {margins?.updatedBy ? ` Last changed by ${margins.updatedBy}.` : ''}
          </p>
          {error && <p role="alert" className="text-red-700">{error}</p>}
          <div className="flex flex-wrap gap-2">
            <button type="button" onClick={save} disabled={!valid || busy} className="min-h-[40px] rounded-lg bg-zinc-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-50">Save</button>
            {margins && <button type="button" onClick={clear} disabled={busy} className="min-h-[40px] rounded-lg bg-white px-4 py-2 text-sm font-medium text-zinc-700 ring-1 ring-zinc-300 disabled:opacity-50">Clear</button>}
          </div>
        </div>
      )}
    </div>
  );
}

type ConvParts = { sms: number; calls: number; ringostat: number; consultations: number; deposits: number; forms: number };
// Call taps are secondary (Google Ads does not count them either): the real call is the Ringostat one.
const convTotal = (c: ConvParts) => c.sms + c.ringostat + c.consultations + c.deposits + c.forms;
/** "3 text taps, 2 call taps, 1 consultation": only the kinds that happened. */
function convBreakdown(c: ConvParts): string {
  return [
    c.sms > 0 ? `${c.sms} ${c.sms === 1 ? 'text' : 'texts'}` : null,
    c.ringostat > 0 ? `${c.ringostat} ${c.ringostat === 1 ? 'phone call' : 'phone calls'}` : null,
    c.consultations > 0 ? `${c.consultations} consult.` : null,
    c.deposits > 0 ? `${c.deposits} ${c.deposits === 1 ? 'deposit' : 'deposits'}` : null,
    c.forms > 0 ? `${c.forms} ${c.forms === 1 ? 'form' : 'forms'}` : null,
  ].filter(Boolean).join(' · ');
}

function Conv({ parts }: { parts: ConvParts }) {
  return (
    <div className="text-right">
      <div className="font-semibold tabular-nums text-zinc-900">{num(convTotal(parts))}</div>
      {convTotal(parts) > 0 && <div className="text-[11px] leading-tight text-zinc-500">{convBreakdown(parts)}</div>}
    </div>
  );
}

/** Every campaign with Google Ads spend: clicks and cost, our conversions (text taps + call taps + consultations booked +
 * deposits from the site + Academy forms), and the money of its leads. Conversions come from the server already matched to the
 * campaign id; what could not be tied to a campaign of this period is listed on its own row so the totals still add up. */
function Campaigns({ data, money }: { data: GooglePpcData; money: AdLeadMoney | null }) {
  const byId = new Map<number, AdLeadCampaignMoney>((money?.campaigns ?? []).filter((m) => m.campaignId !== null).map((m) => [m.campaignId as number, m]));
  const known = new Set(data.campaigns.map((c) => Number(c.campaignId)));
  const extra = (money?.campaigns ?? []).filter((m) => m.campaignId === null || !known.has(m.campaignId));
  const un = data.unattributed;
  const unParts: ConvParts = { sms: un.smsReceived, calls: un.callTaps, ringostat: un.ringostatCalls, consultations: un.consultations, deposits: 0, forms: un.forms };
  const partsOf = (c: (typeof data.campaigns)[number]): ConvParts => ({
    sms: c.smsReceived, calls: c.callTaps, ringostat: c.ringostatCalls, consultations: c.consultations, deposits: byId.get(Number(c.campaignId))?.siteDeposits ?? 0, forms: c.forms,
  });
  const totalParts = data.campaigns.reduce((t, c) => {
    const x = partsOf(c);
    return { sms: t.sms + x.sms, calls: t.calls + x.calls, ringostat: t.ringostat + x.ringostat, consultations: t.consultations + x.consultations, deposits: t.deposits + x.deposits, forms: t.forms + x.forms };
  }, { ...unParts, deposits: extra.reduce((t, m) => t + m.siteDeposits, 0) });
  const hasUn = convTotal(unParts) > 0;
  const leadsTotal = (money?.campaigns ?? []).reduce((t, m) => t + m.leads, 0);
  const th = 'px-3 py-2 text-right font-medium';
  return (
    <section aria-label="Campaigns">
      <h2 className="text-sm font-semibold text-zinc-900">Campaigns</h2>
      <p className="mb-2 mt-0.5 text-xs text-zinc-500">
        Conversions = text taps + call taps + consultations booked + deposits from the site (+ Academy forms). Leads are people created in the period.
      </p>
      <ul className="space-y-2 sm:hidden">
        {data.campaigns.map((c) => {
          const m = byId.get(Number(c.campaignId));
          const parts = partsOf(c);
          const n = convTotal(parts);
          return (
            <li key={c.campaignId} className="rounded-xl bg-white p-3 ring-1 ring-zinc-200">
              <div className="flex items-start justify-between gap-2">
                <span className="min-w-0 break-words text-sm font-medium text-zinc-900">{c.name}</span>
                <span className="shrink-0 text-sm font-semibold tabular-nums">{usd0.format(c.spend)}</span>
              </div>
              <div className="mt-2 flex items-baseline justify-between gap-3 rounded-lg bg-zinc-50 px-3 py-2">
                <div>
                  <div className="text-xs text-zinc-500">Conversions</div>
                  <div className="text-lg font-semibold tabular-nums text-zinc-900">{num(n)}</div>
                </div>
                <div className="text-right">
                  <div className="text-xs text-zinc-500">Cost per conversion</div>
                  <div className="text-lg font-semibold tabular-nums text-zinc-900">{perUnit(c.spend, n)}</div>
                </div>
              </div>
              {n > 0 && <p className="mt-1.5 text-xs text-zinc-500">{convBreakdown(parts)}</p>}
              <dl className="mt-2 grid grid-cols-3 gap-2 text-xs">
                <Cell label="Clicks" value={num(c.clicks)} />
                <Cell label="CPC" value={perUnit(c.spend, c.clicks)} />
                <Cell label="Leads" value={m ? num(m.leads) : dash} />
                <Cell label="CAC" value={m ? perUnit(c.spend, m.payers) : dash} />
                <Cell label="Collected" value={m ? usd0.format(m.collected) : dash} />
                <Cell label="Left to collect" value={m ? usd0.format(m.remaining) : dash} />
              </dl>
            </li>
          );
        })}
        {extra.map((m) => (
          <li key={m.campaign ?? 'none'} className="rounded-xl bg-white p-3 ring-1 ring-zinc-200">
            <div className="min-w-0 break-words text-sm font-medium text-zinc-900">
              {m.campaign ?? 'No campaign recorded'}{m.campaign && <span className="ml-1 text-xs text-zinc-400">(no spend this period)</span>}
            </div>
            <dl className="mt-2 grid grid-cols-3 gap-2 text-xs">
              <Cell label="Site deposits" value={num(m.siteDeposits)} />
              <Cell label="Leads" value={num(m.leads)} />
              <Cell label="Collected" value={usd0.format(m.collected)} />
              <Cell label="Left to collect" value={usd0.format(m.remaining)} />
            </dl>
          </li>
        ))}
        {hasUn && (
          <li className="rounded-xl bg-white p-3 ring-1 ring-zinc-200">
            <div className="text-sm font-medium text-zinc-900">Not tied to a campaign of this period</div>
            <p className="mt-1 text-xs text-zinc-500">{num(convTotal(unParts))} conversions: {convBreakdown(unParts)}</p>
          </li>
        )}
        <li className="rounded-xl bg-zinc-50 p-3 ring-1 ring-zinc-300">
          <div className="flex items-baseline justify-between gap-3">
            <span className="text-sm font-semibold text-zinc-900">Total</span>
            <span className="text-sm font-semibold tabular-nums">{usd0.format(data.spend)}</span>
          </div>
          <dl className="mt-2 grid grid-cols-3 gap-2 text-xs">
            <Cell label="Conversions" value={num(convTotal(totalParts))} />
            <Cell label="Cost per conversion" value={perUnit(data.spend, convTotal(totalParts))} />
            <Cell label="Leads" value={money ? num(leadsTotal) : dash} />
            <Cell label="CAC" value={money ? perUnit(data.spend, money.payers) : dash} />
            <Cell label="Clicks" value={num(data.clicks)} />
            <Cell label="Collected" value={money ? usd0.format(money.collected) : dash} />
            <Cell label="Left to collect" value={money ? usd0.format(money.periodLeft) : dash} />
          </dl>
        </li>
      </ul>
      <div className="hidden overflow-x-auto rounded-xl bg-white ring-1 ring-zinc-200 sm:block">
        <table className="w-full text-left text-sm">
          <thead className="bg-zinc-50 text-xs uppercase tracking-wide text-zinc-500">
            <tr>
              <th className="px-3 py-2 font-medium">Campaign</th>
              <th className={th}>Spend</th>
              <th className={th}>Clicks</th>
              <th className={th}>CPC</th>
              <th className={th} title="Texts received + phone calls + consultations booked + deposits from the site (+ Academy forms). Taps on the text and call buttons are not counted">Conversions</th>
              <th className={th}>Cost per conversion</th>
              <th className={th} title="People created as leads in the period">Leads</th>
              <th className={th} title="Spend divided by the leads of this campaign that paid since they came in">CAC</th>
              <th className={th} title="All payments of this campaign's leads created in the period">Collected</th>
              <th className={th} title="Balance still to pay on procedures booked ahead">Left to collect</th>
            </tr>
          </thead>
          <tbody>
            {data.campaigns.map((c) => {
              const m = byId.get(Number(c.campaignId));
              const parts = partsOf(c);
              return (
                <tr key={c.campaignId} className="border-t border-zinc-100 align-top">
                  <td className="px-3 py-2 font-medium text-zinc-900">{c.name}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{usd0.format(c.spend)}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{num(c.clicks)}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{perUnit(c.spend, c.clicks)}</td>
                  <td className="px-3 py-2"><Conv parts={parts} /></td>
                  <td className="px-3 py-2 text-right tabular-nums">{perUnit(c.spend, convTotal(parts))}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{m ? num(m.leads) : dash}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{m ? perUnit(c.spend, m.payers) : dash}</td>
                  <td className="px-3 py-2 text-right font-medium tabular-nums">{m ? usd0.format(m.collected) : dash}</td>
                  <td className={`px-3 py-2 text-right font-medium tabular-nums ${m && m.remaining > 0 ? 'text-amber-700' : ''}`}>{m ? usd0.format(m.remaining) : dash}</td>
                </tr>
              );
            })}
            {extra.map((m) => (
              <tr key={m.campaign ?? 'none'} className="border-t border-zinc-100 align-top text-zinc-600">
                <td className="px-3 py-2">{m.campaign ?? 'No campaign recorded'}{m.campaign && <span className="ml-1 text-xs text-zinc-400">(no spend this period)</span>}</td>
                <td className="px-3 py-2 text-right tabular-nums">{dash}</td>
                <td className="px-3 py-2 text-right tabular-nums">{dash}</td>
                <td className="px-3 py-2 text-right tabular-nums">{dash}</td>
                <td className="px-3 py-2"><Conv parts={{ sms: 0, calls: 0, ringostat: 0, consultations: 0, deposits: m.siteDeposits, forms: 0 }} /></td>
                <td className="px-3 py-2 text-right tabular-nums">{dash}</td>
                <td className="px-3 py-2 text-right tabular-nums">{num(m.leads)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{dash}</td>
                <td className="px-3 py-2 text-right font-medium tabular-nums">{usd0.format(m.collected)}</td>
                <td className={`px-3 py-2 text-right font-medium tabular-nums ${m.remaining > 0 ? 'text-amber-700' : ''}`}>{usd0.format(m.remaining)}</td>
              </tr>
            ))}
            {hasUn && (
              <tr className="border-t border-zinc-100 align-top text-zinc-600">
                <td className="px-3 py-2">Not tied to a campaign of this period</td>
                <td className="px-3 py-2 text-right tabular-nums">{dash}</td>
                <td className="px-3 py-2 text-right tabular-nums">{dash}</td>
                <td className="px-3 py-2 text-right tabular-nums">{dash}</td>
                <td className="px-3 py-2"><Conv parts={unParts} /></td>
                {[0, 1, 2, 3, 4].map((i) => <td key={i} className="px-3 py-2 text-right tabular-nums">{dash}</td>)}
              </tr>
            )}
          </tbody>
          <tfoot className="border-t border-zinc-200 bg-zinc-50 text-zinc-900">
            <tr className="align-top font-semibold">
              <td className="px-3 py-2">Total</td>
              <td className="px-3 py-2 text-right tabular-nums">{usd0.format(data.spend)}</td>
              <td className="px-3 py-2 text-right tabular-nums">{num(data.clicks)}</td>
              <td className="px-3 py-2 text-right tabular-nums">{perUnit(data.spend, data.clicks)}</td>
              <td className="px-3 py-2"><Conv parts={totalParts} /></td>
              <td className="px-3 py-2 text-right tabular-nums">{perUnit(data.spend, convTotal(totalParts))}</td>
              <td className="px-3 py-2 text-right tabular-nums">{money ? num(leadsTotal) : dash}</td>
              <td className="px-3 py-2 text-right tabular-nums">{money ? perUnit(data.spend, money.payers) : dash}</td>
              <td className="px-3 py-2 text-right tabular-nums">{money ? usd0.format(money.collected) : dash}</td>
              <td className="px-3 py-2 text-right tabular-nums text-amber-700">{money ? usd0.format(money.periodLeft) : dash}</td>
            </tr>
          </tfoot>
        </table>
      </div>
    </section>
  );
}

type PageRow = { key: string; name: string; visitors: number | null; sms: number; calls: number; consultations: number; deposits: number; forms: number };

function RateBar({ value, max }: { value: number; max: number }) {
  return (
    <span className="block h-1.5 w-full overflow-hidden rounded-full bg-zinc-100" aria-hidden>
      <span className="block h-full rounded-full bg-emerald-600" style={{ width: `${Math.round((value / max) * 100)}%` }} />
    </span>
  );
}

/** Every conversion type by landing page. Conversion rate = text taps + call taps + consultations booked + deposits
 * from the site, divided by the visits of the page. Deposits are counted for leads created in the period and
 * assigned to the page the lead first landed on. */
function Pages({ data, money }: { data: GooglePpcData; money: AdLeadMoney | null }) {
  const byPage = money?.siteDepositsByPage ?? {};
  const known = new Set(data.pages.map((p) => p.slug));
  const rows: PageRow[] = data.pages.map((p) => ({ key: p.slug, name: p.name, visitors: p.visitors, sms: p.smsReceived, calls: p.callTaps, consultations: p.consultations, deposits: byPage[p.slug] ?? 0, forms: p.forms }));
  const unplaced = Object.entries(byPage).filter(([slug]) => !known.has(slug)).reduce((t, [, n]) => t + n, 0);
  if (unplaced > 0) rows.push({ key: 'unplaced', name: 'Page not recorded', visitors: null, sms: 0, calls: 0, consultations: 0, deposits: unplaced, forms: 0 });
  if (rows.length === 0) return null;
  const conv = (r: PageRow) => r.sms + r.consultations + r.deposits + r.forms;
  const hasForms = rows.some((r) => r.forms > 0);
  const share = (r: PageRow) => (r.visitors && r.visitors > 0 ? conv(r) / r.visitors : 0);
  const rate = (r: PageRow) => (r.visitors && r.visitors > 0 ? `${(share(r) * 100).toFixed(1)}%` : dash);
  rows.sort((x, y) => conv(y) - conv(x) || (y.visitors ?? 0) - (x.visitors ?? 0));
  const maxShare = Math.max(0.0001, ...rows.map(share));
  const total: PageRow = rows.reduce((t, r) => ({ ...t, visitors: (t.visitors ?? 0) + (r.visitors ?? 0), sms: t.sms + r.sms, calls: t.calls + r.calls, consultations: t.consultations + r.consultations, deposits: t.deposits + r.deposits, forms: t.forms + r.forms }),
    { key: 'all', name: 'All pages', visitors: 0, sms: 0, calls: 0, consultations: 0, deposits: 0, forms: 0 });
  return (
    <section aria-label="Results by landing page">
      <h2 className="text-sm font-semibold text-zinc-900">Results by landing page</h2>
      <p className="mb-2 mt-0.5 text-xs text-zinc-500">
        Conversion rate = text taps + call taps + consultations booked + deposits from the site (+ Academy forms on the Academy page), divided by visits of the page.
      </p>
      <ul className="space-y-2 sm:hidden">
        {[...rows, total].map((r) => (
          <li key={r.key} className={`rounded-xl p-3 ring-1 ${r.key === 'all' ? 'bg-zinc-50 ring-zinc-300' : 'bg-white ring-zinc-200'}`}>
            <div className="flex items-baseline justify-between gap-3">
              <span className="min-w-0 break-words text-sm font-medium text-zinc-900">{r.name}</span>
              <span className="shrink-0 text-lg font-semibold tabular-nums text-zinc-900">{rate(r)}</span>
            </div>
            {r.key !== 'all' && <div className="mt-1.5"><RateBar value={share(r)} max={maxShare} /></div>}
            <dl className="mt-2 grid grid-cols-3 gap-x-2 gap-y-2 text-xs">
              <Cell label="Visits" value={r.visitors === null ? dash : num(r.visitors)} />
              <Cell label="Texts received" value={num(r.sms)} />
              <Cell label="Call taps (not counted)" value={num(r.calls)} />
              <Cell label="Consultations" value={num(r.consultations)} />
              <Cell label="Site deposits" value={num(r.deposits)} />
              {hasForms && <Cell label="Forms" value={r.key === 'academy' || r.forms > 0 || r.key === 'all' ? num(r.forms) : dash} />}
              <Cell label="Conversions" value={num(conv(r))} />
            </dl>
          </li>
        ))}
      </ul>
      <div className="hidden overflow-x-auto rounded-xl bg-white ring-1 ring-zinc-200 sm:block">
        <table className="w-full text-left text-sm">
          <thead className="bg-zinc-50 text-xs uppercase tracking-wide text-zinc-500">
            <tr>
              <th className="px-3 py-2 font-medium">Page</th>
              <th className="px-3 py-2 text-right font-medium">Visits</th>
              <th className="px-3 py-2 text-right font-medium">Texts received</th>
              <th className="px-3 py-2 text-right font-medium">Call taps (not counted)</th>
              <th className="px-3 py-2 text-right font-medium">Consultations</th>
              <th className="px-3 py-2 text-right font-medium" title="Deposits paid through Book now, Book a procedure; invoices do not count">Site deposits</th>
              {hasForms && <th className="px-3 py-2 text-right font-medium" title="Academy course request form (Academy page only)">Forms</th>}
              <th className="px-3 py-2 text-right font-medium">Conversions</th>
              <th className="px-3 py-2 font-medium lg:w-48">Conversion rate</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.key} className="border-t border-zinc-100">
                <td className="px-3 py-2 font-medium text-zinc-900">{r.name}</td>
                <td className="px-3 py-2 text-right tabular-nums">{r.visitors === null ? dash : num(r.visitors)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{num(r.sms)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{num(r.calls)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{num(r.consultations)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{num(r.deposits)}</td>
                {hasForms && <td className="px-3 py-2 text-right tabular-nums">{r.key === 'academy' || r.forms > 0 ? num(r.forms) : dash}</td>}
                <td className="px-3 py-2 text-right font-medium tabular-nums">{num(conv(r))}</td>
                <td className="px-3 py-2">
                  <div className="flex items-center gap-2">
                    <span className="hidden min-w-0 flex-1 lg:block"><RateBar value={share(r)} max={maxShare} /></span>
                    <span className="w-12 shrink-0 text-right font-medium tabular-nums text-zinc-900">{rate(r)}</span>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
          <tfoot className="border-t border-zinc-200 bg-zinc-50 font-semibold text-zinc-900">
            <tr>
              <td className="px-3 py-2">{total.name}</td>
              <td className="px-3 py-2 text-right tabular-nums">{num(total.visitors ?? 0)}</td>
              <td className="px-3 py-2 text-right tabular-nums">{num(total.sms)}</td>
              <td className="px-3 py-2 text-right tabular-nums">{num(total.calls)}</td>
              <td className="px-3 py-2 text-right tabular-nums">{num(total.consultations)}</td>
              <td className="px-3 py-2 text-right tabular-nums">{num(total.deposits)}</td>
              {hasForms && <td className="px-3 py-2 text-right tabular-nums">{num(total.forms)}</td>}
              <td className="px-3 py-2 text-right tabular-nums">{num(conv(total))}</td>
              <td className="px-3 py-2 text-right tabular-nums">{rate(total)}</td>
            </tr>
          </tfoot>
        </table>
      </div>
    </section>
  );
}

function fmtDate(iso: string | null, timeZone?: string): string {
  if (!iso) return dash;
  return new Date(iso).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric', timeZone });
}

const STATUS_FILTERS = ['ALL', 'ACTIVE', 'BOOKED', 'LOST'] as const;
type StatusFilter = (typeof STATUS_FILTERS)[number];
const FILTER_LABEL: Record<StatusFilter, string> = { ALL: 'All', ACTIVE: 'In progress', BOOKED: 'Booked', LOST: 'Lost' };
const inFilter = (l: AdLeadRow, f: StatusFilter) =>
  f === 'ALL' ? true : f === 'ACTIVE' ? l.status === 'NEW' || l.status === 'CONTACTED' : l.status === f;

function Leads({ leads, timeZone }: { leads: AdLeadRow[]; timeZone?: string }) {
  const [filter, setFilter] = useState<StatusFilter>('ALL');
  if (leads.length === 0) return null;
  const shown = leads.filter((l) => inFilter(l, filter));
  return (
    <section aria-label="Google Ads leads">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-sm font-semibold text-zinc-900">Leads ({leads.length})</h2>
        <div className="flex flex-wrap gap-1.5" role="group" aria-label="Filter leads by status">
          {STATUS_FILTERS.map((f) => {
            const n = leads.filter((l) => inFilter(l, f)).length;
            return (
              <button
                key={f}
                type="button"
                aria-pressed={filter === f}
                onClick={() => setFilter(f)}
                className={`min-h-[32px] rounded-full px-3 py-1 text-xs font-medium ${filter === f ? 'bg-zinc-900 text-white' : 'bg-zinc-100 text-zinc-700'}`}
              >
                {FILTER_LABEL[f]} <span className="tabular-nums opacity-70">{n}</span>
              </button>
            );
          })}
        </div>
      </div>
      <ul className="divide-y divide-zinc-100 overflow-hidden rounded-xl bg-white ring-1 ring-zinc-200">
        {shown.map((l) => {
          const stage = l.procedure ? `Procedure: ${l.procedure}` : consultationLabel(l.consultation);
          return (
            <li key={l.id}>
              <Link href={`/owner/marketing/leads/${l.id}`} className="block px-3 py-3 hover:bg-zinc-50">
                <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1">
                  <span className="min-w-0 break-words text-sm font-medium text-zinc-900">{l.name || 'No name'}</span>
                  <span className="flex shrink-0 items-center gap-2 text-xs text-zinc-500">
                    <span className={`rounded-full px-2 py-0.5 font-medium ${statusTone(l.status)}`}>{statusLabel(l.status)}</span>
                    <span className="tabular-nums">{fmtDate(l.createdAt, timeZone)}</span>
                  </span>
                </div>
                <div className="mt-1 break-words text-xs text-zinc-600">
                  <span className="text-zinc-500">Search: </span>
                  {l.searchQuery ? <span className="font-medium text-zinc-800">{l.searchQuery}</span> : <span className="text-zinc-400">not provided by Google</span>}
                  {l.campaign && <span className="text-zinc-500"> | {l.campaign}</span>}
                </div>
                {stage && <div className="mt-0.5 break-words text-xs text-zinc-600">{stage}</div>}
                {(l.collected ?? 0) > 0 || l.remaining != null ? (
                  <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs tabular-nums">
                    <span className="text-zinc-500">Collected <span className="font-semibold text-zinc-900">{usd0.format(l.collected ?? 0)}</span></span>
                    {l.remaining != null && (
                      <span className={l.remaining > 0 ? 'text-amber-700' : 'text-emerald-700'}>
                        {l.remaining > 0 ? <>Left to collect <span className="font-semibold">{usd0.format(l.remaining)}</span></> : <span className="font-semibold">Paid in full</span>}
                        {l.nextProcedureAt && <span className="text-zinc-500"> | {fmtDate(l.nextProcedureAt, timeZone)}</span>}
                      </span>
                    )}
                  </div>
                ) : null}
              </Link>
            </li>
          );
        })}
      </ul>
      {shown.length === 0 && <p className="mt-2 text-xs text-zinc-500">No leads with this status in the period.</p>}
    </section>
  );
}

function Cell({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-zinc-500">{label}</dt>
      <dd className="font-medium tabular-nums text-zinc-900">{value}</dd>
    </div>
  );
}
