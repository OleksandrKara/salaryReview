'use client';

import { useRef, useState } from 'react';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { api } from '../../../lib/api';
import type { AdLeadCampaignMoney, AdLeadMoney, AdLeadRow, GooglePpcCustomer, GooglePpcData, Margins } from '../../../lib/types';
import { romiPercent } from './romi';
import { consultationLabel, statusLabel, statusTone } from '../adLeads';
import PeriodFilter from '../PeriodFilter';
import { parsePeriodParams, periodToBounds } from '../period';
import type { PeriodSelection } from '../period';

const usd0 = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });
const usd2 = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: 2, maximumFractionDigits: 2 });
const num = (n: number) => n.toLocaleString('en-US');
const dash = '-';

const perUnit = (spend: number, n: number) => (n > 0 ? usd2.format(spend / n) : dash);

export default function GooglePpcView({ initialData, initialLeads, initialMoney, initialMargins, timeZone }: { initialData: GooglePpcData | null; initialLeads: AdLeadRow[]; initialMoney: AdLeadMoney | null; initialMargins: Margins | null; timeZone?: string }) {
  const searchParams = useSearchParams();
  const [data, setData] = useState(initialData);
  const [leads, setLeads] = useState(initialLeads);
  const [money, setMoney] = useState(initialMoney);
  const [margins, setMargins] = useState(initialMargins);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(initialData === null);
  const requestId = useRef(0);
  const [selection, setSelection] = useState<PeriodSelection>(() => parsePeriodParams(searchParams));

  async function changePeriod(next: PeriodSelection) {
    setSelection(next);
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
          <Kpis data={data} margins={margins} onMargins={setMargins} money={money} />
          <Campaigns data={data} money={money} />
          <Pages data={data} />
          <Leads leads={leads} timeZone={timeZone} />
          <Customers customers={data.customers} leads={leads} timeZone={timeZone} />
        </div>
      )}
    </div>
  );
}

function MoneyCard({ money, loading }: { money: AdLeadMoney; loading: boolean }) {
  const pct = money.leftEstimate > 0 ? Math.min(100, Math.round((money.leftPrepaid / money.leftEstimate) * 100)) : 0;
  return (
    <section aria-label="Money from Google Ads leads" className={`rounded-xl bg-white p-4 ring-1 ring-zinc-200 transition-opacity ${loading ? 'opacity-50' : ''}`}>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <div className="min-w-0">
          <div className="text-xs font-medium text-zinc-500">Left to collect</div>
          <div className={`mt-1 text-3xl font-semibold tabular-nums ${money.leftToCollect > 0 ? 'text-amber-700' : 'text-zinc-900'}`}>{usd0.format(money.leftToCollect)}</div>
          <div className="mt-1 text-xs text-zinc-500">
            {money.leftLeads > 0
              ? `${num(money.leftLeads)} ${money.leftLeads === 1 ? 'lead has' : 'leads have'} a procedure booked ahead and a balance to pay`
              : 'No Google Ads lead has a balance to pay on a booked procedure'}
          </div>
          {money.leftEstimate > 0 && (
            <div className="mt-3">
              <div className="h-2 overflow-hidden rounded-full bg-zinc-100" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct} aria-label="Prepaid share of booked procedures">
                <div className="h-full rounded-full bg-emerald-600" style={{ width: `${pct}%` }} />
              </div>
              <p className="mt-1 text-xs tabular-nums text-zinc-500">Prepaid {usd0.format(money.leftPrepaid)} of {usd0.format(money.leftEstimate)} booked</p>
            </div>
          )}
          {money.partial && <p className="mt-2 text-xs text-zinc-500">Some booked services have no price yet, so this may be higher.</p>}
        </div>
        <div className="min-w-0 sm:border-l sm:border-zinc-100 sm:pl-4">
          <div className="text-xs font-medium text-zinc-500">Collected from leads in this period</div>
          <div className="mt-1 text-3xl font-semibold tabular-nums text-zinc-900">{usd0.format(money.collected)}</div>
          <div className="mt-1 text-xs text-zinc-500">All payments of Google Ads leads created in the selected period. Left to collect covers every Google Ads lead with a booking ahead, whenever it came in.</div>
        </div>
      </div>
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

function Kpis({ data, margins, onMargins, money }: { data: GooglePpcData; margins: Margins | null; onMargins: (m: Margins | null) => void; money: AdLeadMoney | null }) {
  const r = data.results;
  const roi = data.spend > 0 ? ((r.revenue - data.spend) / data.spend) * 100 : null;
  const roas = data.spend > 0 ? r.revenue / data.spend : null;
  const romi = margins ? romiPercent(r.revenue, data.spend, r.deposits, margins) : roi;
  // Same math with the balances still to pay on procedures already booked by this period's leads (one more procedure of materials each).
  const romiAhead = money && money.periodLeft > 0
    ? (margins ? romiPercent(r.revenue + money.periodLeft, data.spend, r.deposits + money.periodLeftLeads, margins)
      : data.spend > 0 ? ((r.revenue + money.periodLeft - data.spend) / data.spend) * 100 : null)
    : null;
  return (
    <section aria-label="Google Ads results">
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-3">
        <Kpi label="Spend" value={usd0.format(data.spend)} sub={`${num(data.clicks)} clicks, ${num(data.impressions)} impressions`} />
        <Kpi label="Cost per click" value={perUnit(data.spend, data.clicks)} sub={`${num(r.visitors)} landing page visitors`} />
        <Kpi label="Cost per consultation" value={perUnit(data.spend, r.consultations)} sub={`${num(r.consultations)} consultations`} />
        <Kpi label="Cost per deposit" value={perUnit(data.spend, r.deposits)} sub={`${num(r.deposits)} deposits, ${num(r.fullPayments)} full payments`} />
        <Kpi label="Revenue" value={usd0.format(r.revenue)} sub={`${num(r.payingCustomers)} paying customers`} />
        <Kpi label="ROAS" value={roas === null ? dash : `${roas.toFixed(2)}x`} sub="Revenue divided by spend" />
        <Kpi label="ROI" value={roi === null ? dash : `${roi.toFixed(0)}%`} sub="(Revenue - spend) / spend" />
        {/* Without cost assumptions ROMI is just ROI, so the card appears only once they are set. */}
        {margins && <Kpi label="ROMI" value={romi === null ? dash : `${romi.toFixed(0)}%`} sub="After materials, master and Square fees (approx.)" />}
        <Kpi
          className={margins ? 'col-span-2 lg:col-span-1' : 'lg:col-span-2'}
          label="ROMI with left to collect"
          value={romiAhead === null ? dash : `${romiAhead.toFixed(0)}%`}
          sub={money && money.periodLeft > 0 ? `If the ${usd0.format(money.periodLeft)} still to collect from this period's leads is paid` : 'Nothing left to collect from this period\'s leads'}
        />
      </div>
      <MarginsEditor margins={margins} onChange={onMargins} />
      <p className="mt-2 text-xs text-zinc-500">
        Revenue is Square payments from customers whose first visit came from Google Ads, counted after their first contact.
        Bookings are matched via contact-capture data and may slightly undercount.
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
  const byName = new Map<string, AdLeadCampaignMoney>((money?.campaigns ?? []).filter((m) => m.campaign).map((m) => [m.campaign as string, m]));
  const known = new Set(data.campaigns.map((c) => c.name));
  // Leads whose campaign has no spend row this period (or no campaign at all) still count toward the totals.
  const extra = (money?.campaigns ?? []).filter((m) => !m.campaign || !known.has(m.campaign));
  const rows = data.campaigns;
  return (
    <section aria-label="Campaigns">
      <h2 className="mb-2 text-sm font-semibold text-zinc-900">Campaigns</h2>
      <ul className="space-y-2 sm:hidden">
        {rows.map((c) => {
          const m = byName.get(c.name);
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
              <th className="px-3 py-2 text-right font-medium" title="All payments of this campaign's leads created in the period">Collected</th>
              <th className="px-3 py-2 text-right font-medium" title="Balance still to pay on procedures booked ahead">Left to collect</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((c) => {
              const m = byName.get(c.name);
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
                  <td className="px-3 py-2 text-right font-medium tabular-nums">{m ? usd0.format(m.collected) : dash}</td>
                  <td className={`px-3 py-2 text-right font-medium tabular-nums ${m && m.remaining > 0 ? 'text-amber-700' : ''}`}>{m ? usd0.format(m.remaining) : dash}</td>
                </tr>
              );
            })}
            {extra.map((m) => (
              <tr key={m.campaign ?? 'none'} className="border-t border-zinc-100 text-zinc-600">
                <td className="px-3 py-2">{m.campaign ?? 'No campaign recorded'}</td>
                {[0, 1, 2, 3, 4, 5].map((i) => <td key={i} className="px-3 py-2 text-right tabular-nums">{dash}</td>)}
                <td className="px-3 py-2 text-right tabular-nums">{num(m.leads)}</td>
                <td className="px-3 py-2 text-right font-medium tabular-nums">{usd0.format(m.collected)}</td>
                <td className={`px-3 py-2 text-right font-medium tabular-nums ${m.remaining > 0 ? 'text-amber-700' : ''}`}>{usd0.format(m.remaining)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function Pages({ data }: { data: GooglePpcData }) {
  if (data.pages.length === 0) return null;
  return (
    <section aria-label="Results by landing page">
      <h2 className="mb-2 text-sm font-semibold text-zinc-900">Results by landing page</h2>
      <div className="overflow-x-auto rounded-xl bg-white ring-1 ring-zinc-200">
        <table className="w-full min-w-[34rem] text-left text-sm">
          <thead className="bg-zinc-50 text-xs uppercase tracking-wide text-zinc-500">
            <tr>
              <th className="px-3 py-2 font-medium">Page</th>
              <th className="px-3 py-2 text-right font-medium">Visitors</th>
              <th className="px-3 py-2 text-right font-medium">Taps</th>
              <th className="px-3 py-2 text-right font-medium">Consultations</th>
              <th className="px-3 py-2 text-right font-medium">Deposits</th>
              <th className="px-3 py-2 text-right font-medium">Full payments</th>
              <th className="px-3 py-2 text-right font-medium">Revenue</th>
            </tr>
          </thead>
          <tbody>
            {data.pages.map((p) => (
              <tr key={p.slug} className="border-t border-zinc-100">
                <td className="px-3 py-2 font-medium text-zinc-900">{p.name}</td>
                <td className="px-3 py-2 text-right tabular-nums">{num(p.visitors)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{num(p.smsTaps + p.callTaps)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{num(p.consultations)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{num(p.deposits)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{num(p.fullPayments)}</td>
                <td className="px-3 py-2 text-right font-medium tabular-nums">{usd0.format(p.revenue)}</td>
              </tr>
            ))}
          </tbody>
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

function Customers({ customers, leads, timeZone }: { customers: GooglePpcCustomer[]; leads: AdLeadRow[]; timeZone?: string }) {
  const [open, setOpen] = useState<Set<string>>(new Set());
  if (customers.length === 0) return null;
  const toggle = (id: string) =>
    setOpen((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  const allOpen = open.size === customers.length;
  const leadByCustomer = new Map(leads.filter((l) => l.customerId).map((l) => [l.customerId as string, l.id]));
  return (
    <section aria-label="Paying customers">
      <div className="mb-2 flex items-center justify-between gap-2">
        <h2 className="text-sm font-semibold text-zinc-900">Paying customers ({customers.length})</h2>
        <button
          type="button"
          onClick={() => setOpen(allOpen ? new Set() : new Set(customers.map((c) => c.customerId)))}
          className="rounded-lg bg-zinc-100 px-3 py-1.5 text-xs font-medium text-zinc-700"
        >
          {allOpen ? 'Collapse all' : 'Expand all'}
        </button>
      </div>
      <ul className="divide-y divide-zinc-100 overflow-hidden rounded-xl bg-white ring-1 ring-zinc-200">
        {customers.map((c) => {
          const isOpen = open.has(c.customerId);
          const stage = c.full ? 'Full payment' : c.deposit ? 'Deposit' : 'Paid';
          return (
            <li key={c.customerId}>
              <button
                type="button"
                onClick={() => toggle(c.customerId)}
                aria-expanded={isOpen}
                className="flex min-h-[44px] w-full items-center justify-between gap-3 px-3 py-2 text-left"
              >
                <span className="min-w-0">
                  <span className="block truncate text-sm font-medium text-zinc-900">{c.name || c.phone || 'Unknown'}</span>
                  <span className="block truncate text-xs text-zinc-500">{c.service ?? 'No booking matched'} | {stage}</span>
                </span>
                <span className="flex shrink-0 items-center gap-2">
                  <span className="text-sm font-semibold tabular-nums text-zinc-900">{usd0.format(c.total)}</span>
                  <span aria-hidden="true" className="text-zinc-400">{isOpen ? '\u25B2' : '\u25BC'}</span>
                </span>
              </button>
              {isOpen && (
                <div className="space-y-3 bg-zinc-50 px-3 py-3 text-xs">
                  <dl className="grid grid-cols-2 gap-x-3 gap-y-2 sm:grid-cols-3">
                    <Cell label="Phone" value={c.phone ?? dash} />
                    <Cell label="Email" value={c.email ?? dash} />
                    <Cell label="First contact" value={fmtDate(c.firstContactAt, timeZone)} />
                    <Cell label="Landing page" value={c.pageSlug ?? dash} />
                    <Cell label="Campaign (utm)" value={c.campaign ?? dash} />
                    <Cell label="Service" value={c.service ?? dash} />
                    <Cell label="Booking status" value={c.bookingStatus ?? dash} />
                    <Cell label="Appointment" value={fmtDate(c.bookingStartAt, timeZone)} />
                    <Cell label="Technician" value={c.artist ?? dash} />
                  </dl>
                  {leadByCustomer.has(c.customerId) && (
                    <Link href={`/owner/marketing/leads/${leadByCustomer.get(c.customerId)}`} className="inline-block text-sm font-medium text-zinc-900 underline">
                      Open lead
                    </Link>
                  )}
                  <div>
                    <div className="mb-1 text-zinc-500">Payments</div>
                    <ul className="space-y-1">
                      {c.payments.map((p, i) => (
                        <li key={i} className="flex justify-between gap-2 tabular-nums">
                          <span>{fmtDate(p.at, timeZone)}</span>
                          <span className="font-medium text-zinc-900">{usd2.format(p.amount)}</span>
                        </li>
                      ))}
                    </ul>
                  </div>
                </div>
              )}
            </li>
          );
        })}
      </ul>
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
