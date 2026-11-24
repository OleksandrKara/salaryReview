'use client';

import { useRef, useState } from 'react';
import Link from 'next/link';
import { api } from '../../../lib/api';
import type { AdLeadCampaignMoney, AdLeadMoney, AdLeadRow, GooglePpcData, Margins } from '../../../lib/types';
import { romiPercent } from './romi';
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
          <Pages data={data} />
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
        <Kpi label="Cost per click" value={perUnit(data.spend, data.clicks)} sub={`${num(r.visitors)} landing page visitors`} />
        <Kpi label="Revenue" value={usd0.format(revenue)} sub={`${num(payers)} paying ${payers === 1 ? 'lead' : 'leads'}, same as Collected`} />
        <Kpi label="ROAS" value={roas === null ? dash : `${roas.toFixed(2)}x`} sub="Revenue divided by spend" />
        <Kpi label="ROI" value={roi === null ? dash : `${roi.toFixed(0)}%`} sub="(Revenue - spend) / spend" />
        <Kpi
          className="col-span-2"
          label="ROMI with left to collect"
          value={romiAhead === null ? dash : `${romiAhead.toFixed(0)}%`}
          sub={`${usd0.format(revenue)} collected + ${usd0.format(left)} left to collect, minus spend${margins ? ' and costs (materials, master, Square fees; approx.)' : '. Set cost assumptions below to include costs'}`}
        />
      </div>
      <div className="mt-3 rounded-xl bg-white p-3 ring-1 ring-zinc-200 sm:p-4">
        <h2 className="text-sm font-semibold text-zinc-900">Conversions</h2>
        <dl className="mt-2 grid grid-cols-2 gap-3 lg:grid-cols-4">
          {[
            { label: 'Consultations booked', n: r.consultations, hint: 'online or in studio, from the site' },
            { label: 'Text taps', n: r.smsTaps, hint: 'taps on the text button' },
            { label: 'Call taps', n: r.callTaps, hint: 'taps on the call button' },
            { label: 'Deposits from the site', n: money ? money.siteDeposits : null, hint: 'Book now, Book a procedure (invoices do not count)' },
          ].map((c) => (
            <div key={c.label} className="min-w-0">
              <dt className="text-xs font-medium text-zinc-500">{c.label}</dt>
              <dd className="mt-0.5 text-xl font-semibold tabular-nums text-zinc-900">{c.n === null ? dash : num(c.n)}</dd>
              <dd className="text-xs text-zinc-500">{c.n === null ? 'Not loaded' : `${perUnit(data.spend, c.n)} each`}</dd>
              <dd className="text-[11px] leading-snug text-zinc-400">{c.hint}</dd>
            </div>
          ))}
        </dl>
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

function Campaigns({ data, money }: { data: GooglePpcData; money: AdLeadMoney | null }) {
  // The money rows are already matched to Google Ads campaigns by id on the server (a lead's campaign text is written
  // differently from the campaign name). Leads whose campaign has no spend row this period, or no campaign at all,
  // stay in their own rows so the totals still add up.
  const byId = new Map<number, AdLeadCampaignMoney>((money?.campaigns ?? []).filter((m) => m.campaignId !== null).map((m) => [m.campaignId as number, m]));
  const known = new Set(data.campaigns.map((c) => Number(c.campaignId)));
  const extra = (money?.campaigns ?? []).filter((m) => m.campaignId === null || !known.has(m.campaignId));
  const rows = data.campaigns;
  return (
    <section aria-label="Campaigns">
      <h2 className="mb-2 text-sm font-semibold text-zinc-900">Campaigns</h2>
      <ul className="space-y-2 sm:hidden">
        {rows.map((c) => {
          const m = byId.get(Number(c.campaignId));
          return (
            <li key={c.campaignId} className="rounded-xl bg-white p-3 ring-1 ring-zinc-200">
              <div className="flex items-start justify-between gap-2">
                <span className="min-w-0 break-words text-sm font-medium text-zinc-900">{c.name}</span>
                <span className="text-sm font-semibold tabular-nums">{usd0.format(c.spend)}</span>
              </div>
              <dl className="mt-2 grid grid-cols-3 gap-2 text-xs">
                <Cell label="Clicks" value={num(c.clicks)} />
                <Cell label="CPC" value={perUnit(c.spend, c.clicks)} />
                <Cell label="Conversions" value={num(Math.round(c.platformConversions))} />
                <Cell label="Collected" value={m ? usd0.format(m.collected) : dash} />
                <Cell label="Left to collect" value={m ? usd0.format(m.remaining) : dash} />
                <Cell label="Leads" value={m ? num(m.leads) : dash} />
              </dl>
            </li>
          );
        })}
        {extra.map((m) => (
          <li key={m.campaign ?? 'none'} className="rounded-xl bg-white p-3 ring-1 ring-zinc-200">
            <div className="min-w-0 break-words text-sm font-medium text-zinc-900">{m.campaign ?? 'No campaign recorded'}</div>
            <dl className="mt-2 grid grid-cols-3 gap-2 text-xs">
              <Cell label="Collected" value={usd0.format(m.collected)} />
              <Cell label="Left to collect" value={usd0.format(m.remaining)} />
              <Cell label="Leads" value={num(m.leads)} />
            </dl>
          </li>
        ))}
      </ul>
      <div className="hidden overflow-x-auto rounded-xl bg-white ring-1 ring-zinc-200 sm:block">
        <table className="w-full text-left text-sm">
          <thead className="bg-zinc-50 text-xs uppercase tracking-wide text-zinc-500">
            <tr>
              <th className="px-3 py-2 font-medium">Campaign</th>
              <th className="px-3 py-2 text-right font-medium">Spend</th>
              <th className="px-3 py-2 text-right font-medium">Impressions</th>
              <th className="px-3 py-2 text-right font-medium">Clicks</th>
              <th className="px-3 py-2 text-right font-medium">CPC</th>
              <th className="px-3 py-2 text-right font-medium" title="Conversions reported by Google Ads itself">Google conversions</th>
              <th className="px-3 py-2 text-right font-medium">Cost per conversion</th>
              <th className="px-3 py-2 text-right font-medium">Leads</th>
              <th className="px-3 py-2 text-right font-medium" title="Leads of this campaign created in the period who paid a deposit through the site">Site deposits</th>
              <th className="px-3 py-2 text-right font-medium" title="All payments of this campaign's leads created in the period">Collected</th>
              <th className="px-3 py-2 text-right font-medium" title="Balance still to pay on procedures booked ahead">Left to collect</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((c) => {
              const m = byId.get(Number(c.campaignId));
              return (
                <tr key={c.campaignId} className="border-t border-zinc-100">
                  <td className="px-3 py-2 font-medium text-zinc-900">{c.name}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{usd0.format(c.spend)}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{num(c.impressions)}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{num(c.clicks)}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{perUnit(c.spend, c.clicks)}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{num(Math.round(c.platformConversions))}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{perUnit(c.spend, c.platformConversions)}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{m ? num(m.leads) : dash}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{m ? num(m.siteDeposits) : dash}</td>
                  <td className="px-3 py-2 text-right font-medium tabular-nums">{m ? usd0.format(m.collected) : dash}</td>
                  <td className={`px-3 py-2 text-right font-medium tabular-nums ${m && m.remaining > 0 ? 'text-amber-700' : ''}`}>{m ? usd0.format(m.remaining) : dash}</td>
                </tr>
              );
            })}
            {extra.map((m) => (
              <tr key={m.campaign ?? 'none'} className="border-t border-zinc-100 text-zinc-600">
                <td className="px-3 py-2">{m.campaign ?? 'No campaign recorded'}{m.campaign && <span className="ml-1 text-xs text-zinc-400">(no spend this period)</span>}</td>
                {[0, 1, 2, 3, 4, 5].map((i) => <td key={i} className="px-3 py-2 text-right tabular-nums">{dash}</td>)}
                <td className="px-3 py-2 text-right tabular-nums">{num(m.leads)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{num(m.siteDeposits)}</td>
                <td className="px-3 py-2 text-right font-medium tabular-nums">{usd0.format(m.collected)}</td>
                <td className={`px-3 py-2 text-right font-medium tabular-nums ${m.remaining > 0 ? 'text-amber-700' : ''}`}>{usd0.format(m.remaining)}</td>
              </tr>
            ))}
          </tbody>
          {money && (
            <tfoot className="border-t border-zinc-200 bg-zinc-50 text-zinc-900">
              <tr className="font-semibold">
                <td className="px-3 py-2">Total</td>
                <td className="px-3 py-2 text-right tabular-nums">{usd0.format(data.spend)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{num(data.impressions)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{num(data.clicks)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{perUnit(data.spend, data.clicks)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{num(Math.round(rows.reduce((t, c) => t + c.platformConversions, 0)))}</td>
                <td className="px-3 py-2 text-right tabular-nums">{perUnit(data.spend, rows.reduce((t, c) => t + c.platformConversions, 0))}</td>
                <td className="px-3 py-2 text-right tabular-nums">{num(money.campaigns.reduce((t, c) => t + c.leads, 0))}</td>
                <td className="px-3 py-2 text-right tabular-nums">{num(money.siteDeposits)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{usd0.format(money.collected)}</td>
                <td className="px-3 py-2 text-right tabular-nums text-amber-700">{usd0.format(money.periodLeft)}</td>
              </tr>
            </tfoot>
          )}
        </table>
      </div>
    </section>
  );
}

/** Conversion rate by landing page: text taps + call taps + consultations booked, divided by the visits of that page. */
function Pages({ data }: { data: GooglePpcData }) {
  if (data.pages.length === 0) return null;
  const conv = (p: GooglePpcData['pages'][number]) => p.smsTaps + p.callTaps + p.consultations;
  const rate = (c: number, v: number) => (v > 0 ? `${((c / v) * 100).toFixed(1)}%` : dash);
  const rows = [...data.pages].sort((x, y) => conv(y) - conv(x) || y.visitors - x.visitors);
  const maxRate = Math.max(0.0001, ...rows.map((p) => (p.visitors > 0 ? conv(p) / p.visitors : 0)));
  const tot = rows.reduce((t, p) => ({ v: t.v + p.visitors, s: t.s + p.smsTaps, c: t.c + p.callTaps, k: t.k + p.consultations }), { v: 0, s: 0, c: 0, k: 0 });
  return (
    <section aria-label="Conversions by landing page">
      <h2 className="text-sm font-semibold text-zinc-900">Conversions by landing page</h2>
      <p className="mb-2 mt-0.5 text-xs text-zinc-500">Conversion rate = text taps + call taps + consultations booked, divided by visits of the page.</p>
      <div className="overflow-x-auto rounded-xl bg-white ring-1 ring-zinc-200">
        <table className="w-full min-w-[40rem] text-left text-sm">
          <thead className="bg-zinc-50 text-xs uppercase tracking-wide text-zinc-500">
            <tr>
              <th className="px-3 py-2 font-medium">Page</th>
              <th className="px-3 py-2 text-right font-medium">Visits</th>
              <th className="px-3 py-2 text-right font-medium">Text taps</th>
              <th className="px-3 py-2 text-right font-medium">Call taps</th>
              <th className="px-3 py-2 text-right font-medium">Consultations</th>
              <th className="px-3 py-2 text-right font-medium">Conversions</th>
              <th className="px-3 py-2 text-right font-medium">Conversion rate</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((p) => {
              const c = conv(p);
              const share = p.visitors > 0 ? c / p.visitors : 0;
              return (
                <tr key={p.slug} className="border-t border-zinc-100">
                  <td className="px-3 py-2 font-medium text-zinc-900">{p.name}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{num(p.visitors)}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{num(p.smsTaps)}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{num(p.callTaps)}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{num(p.consultations)}</td>
                  <td className="px-3 py-2 text-right font-medium tabular-nums">{num(c)}</td>
                  <td className="px-3 py-2 text-right">
                    <div className="flex items-center justify-end gap-2">
                      <span className="hidden h-1.5 w-16 overflow-hidden rounded-full bg-zinc-100 sm:block" aria-hidden>
                        <span className="block h-full rounded-full bg-emerald-600" style={{ width: `${Math.round((share / maxRate) * 100)}%` }} />
                      </span>
                      <span className="w-12 font-medium tabular-nums text-zinc-900">{rate(c, p.visitors)}</span>
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
          <tfoot className="border-t border-zinc-200 bg-zinc-50 font-semibold text-zinc-900">
            <tr>
              <td className="px-3 py-2">All pages</td>
              <td className="px-3 py-2 text-right tabular-nums">{num(tot.v)}</td>
              <td className="px-3 py-2 text-right tabular-nums">{num(tot.s)}</td>
              <td className="px-3 py-2 text-right tabular-nums">{num(tot.c)}</td>
              <td className="px-3 py-2 text-right tabular-nums">{num(tot.k)}</td>
              <td className="px-3 py-2 text-right tabular-nums">{num(tot.s + tot.c + tot.k)}</td>
              <td className="px-3 py-2 text-right tabular-nums">{rate(tot.s + tot.c + tot.k, tot.v)}</td>
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
