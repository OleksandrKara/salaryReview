import Link from 'next/link';
import { redirect } from 'next/navigation';
import { serverApi } from '../../lib/serverApi';
import MonthNav from '../../components/MonthNav';
import type { RetentionTrendPoint } from '../../lib/types';

const MONTHS = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
const pct = (n: number | null) => (n == null ? '—' : `${Math.round(n * 100)}%`);

// Owner-only provider retention analytics. Reads the visit ledger (no Square call). Server component;
// month navigation re-renders via the URL. The proxy also gates /owner/* to owners.
export default async function RetentionPage({
  searchParams,
}: {
  searchParams: Promise<{ year?: string; month?: string }>;
}) {
  const me = await serverApi.getMe();
  if (me.role !== 'OWNER') redirect(me.role === 'MANAGER' ? '/manager' : '/me');

  const sp = await searchParams;
  const now = new Date();
  const year = Number(sp.year) || now.getUTCFullYear();
  const month = Number(sp.month) || now.getUTCMonth() + 1;

  let report;
  try {
    report = await serverApi.getRetention(year, month);
  } catch {
    report = { year, month, retentionWindowDays: 60, providers: [] };
  }

  const prev = month === 1 ? { year: year - 1, month: 12 } : { year, month: month - 1 };
  const next = month === 12 ? { year: year + 1, month: 1 } : { year, month: month + 1 };

  return (
    <main className="mx-auto max-w-5xl px-4 py-8">
      <div className="mb-1 flex flex-wrap items-baseline justify-between gap-3">
        <div className="flex items-baseline gap-3">
          <h1 className="text-2xl font-semibold">Provider retention</h1>
          <Link href="/reports" className="text-xs text-zinc-400 hover:text-zinc-600">← Reports</Link>
        </div>
        <MonthNav base="/owner/retention" year={year} month={month} prev={prev} next={next} />
      </div>
      <p className="mb-5 text-xs text-zinc-500">
        Who keeps clients, who grows their book, and where the salon&apos;s new-client pipeline is leaking.
        Retention = of a provider&apos;s new clients this month, how many returned within{' '}
        {report.retentionWindowDays} days — <span className="font-medium">to that provider</span> /{' '}
        <span className="font-medium">to the salon</span>. Recent months show{' '}
        <span className="italic">too soon</span> until the window elapses.
      </p>

      {report.providers.length === 0 ? (
        <p className="rounded-lg px-4 py-6 text-sm text-zinc-400 ring-1 ring-zinc-200">
          No visits recorded for {MONTHS[month - 1]} {year} yet. (The ledger backfills on deploy and refreshes daily.)
        </p>
      ) : (
        <>
          {/* Mobile: cards */}
          <ul className="space-y-3 sm:hidden">
            {report.providers.map((p) => (
              <li key={p.providerRef} className="rounded-xl p-4 ring-1 ring-zinc-200">
                <div className="flex items-center justify-between">
                  <span className="font-semibold text-zinc-800">{p.providerName}</span>
                  {p.leakRisk ? <RiskBadge /> : null}
                </div>
                <div className="mt-2 grid grid-cols-2 gap-x-4 gap-y-1 text-xs text-zinc-600">
                  <Stat label="Clients seen" value={`${p.clientsSeen}`} />
                  <Stat label="New / returning" value={`${p.newToProvider} / ${p.returningToProvider}`} />
                  <Stat label="Fresh (new to salon)" value={`${p.newToSalonViaP}`} />
                  <Stat label="Same-day rebook" value={pct(p.sameDayRebookRate)} />
                  <Stat label="Returned to provider" value={p.cohortMatured ? pct(p.providerRetention) : 'too soon'} />
                  <Stat label="Returned to salon" value={p.cohortMatured ? pct(p.salonRetention) : 'too soon'} />
                </div>
                <div className="mt-2"><Sparkline trend={p.trend} /></div>
              </li>
            ))}
          </ul>

          {/* Desktop: table */}
          <div className="hidden overflow-x-auto rounded-lg ring-1 ring-zinc-200 sm:block">
            <table className="w-full text-sm">
              <thead className="bg-zinc-50 text-left text-[11px] uppercase tracking-wide text-zinc-500">
                <tr>
                  <th className="px-3 py-2">Provider</th>
                  <th className="px-3 py-2">Clients</th>
                  <th className="px-3 py-2" title="New to this provider / returning to them">New / Ret.</th>
                  <th className="px-3 py-2" title="New salon clients this provider acquired">Fresh</th>
                  <th className="px-3 py-2">Rebook</th>
                  <th className="px-3 py-2" title="Of new clients, % who returned (to provider / to salon)">Retention</th>
                  <th className="px-3 py-2">Trend</th>
                  <th className="px-3 py-2"></th>
                </tr>
              </thead>
              <tbody>
                {report.providers.map((p) => (
                  <tr key={p.providerRef} className="border-t border-zinc-100">
                    <td className="px-3 py-2 font-medium text-zinc-800">{p.providerName}</td>
                    <td className="px-3 py-2 tabular-nums">{p.clientsSeen}</td>
                    <td className="px-3 py-2 tabular-nums">
                      <span className="text-green-700">{p.newToProvider}</span> / {p.returningToProvider}
                    </td>
                    <td className="px-3 py-2 tabular-nums">{p.newToSalonViaP}</td>
                    <td className="px-3 py-2 tabular-nums">{pct(p.sameDayRebookRate)}</td>
                    <td className="px-3 py-2 tabular-nums">
                      {p.cohortMatured ? (
                        <span>
                          <span className="font-medium text-zinc-800">{pct(p.providerRetention)}</span>
                          <span className="text-zinc-400"> / {pct(p.salonRetention)}</span>
                        </span>
                      ) : (
                        <span className="text-xs italic text-zinc-400">too soon</span>
                      )}
                    </td>
                    <td className="px-3 py-2"><Sparkline trend={p.trend} /></td>
                    <td className="px-3 py-2">{p.leakRisk ? <RiskBadge /> : null}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <p className="mt-3 text-[11px] text-zinc-400">
            <span className="font-medium">Fresh</span> = brand-new salon clients this provider acquired this
            month. <span className="font-medium text-amber-700">⚠ At risk</span> = many fresh clients but a low
            return rate — the salon&apos;s new-client pipeline leaking through this provider. Retention shows{' '}
            <span className="text-zinc-600">provider</span> / <span className="text-zinc-400">salon</span>.
          </p>
        </>
      )}
    </main>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <span>
      <span className="text-zinc-400">{label}: </span>
      <span className="font-medium tabular-nums text-zinc-800">{value}</span>
    </span>
  );
}

function RiskBadge() {
  return (
    <span className="rounded-full bg-amber-50 px-2 py-0.5 text-[10px] font-medium text-amber-700">⚠ At risk</span>
  );
}

// Tiny bar sparkline of clients-seen over the trend window; the last bar (this month) is accented.
function Sparkline({ trend }: { trend: RetentionTrendPoint[] }) {
  const max = Math.max(1, ...trend.map((t) => t.clientsSeen));
  return (
    <span className="inline-flex h-6 items-end gap-0.5" title={trend.map((t) => `${MONTHS[t.month - 1]} ${t.clientsSeen}`).join(' · ')}>
      {trend.map((t, i) => (
        <span
          key={`${t.year}-${t.month}`}
          className={`inline-block w-1.5 rounded-sm ${i === trend.length - 1 ? 'bg-zinc-700' : 'bg-zinc-300'}`}
          style={{ height: `${Math.max(8, (t.clientsSeen / max) * 100)}%` }}
        />
      ))}
    </span>
  );
}
