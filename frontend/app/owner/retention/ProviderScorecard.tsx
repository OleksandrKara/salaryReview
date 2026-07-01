import InfoTip from './InfoTip';
import type { ProviderRetentionRow } from '../../lib/types';

const pct = (n: number | null) => (n == null ? '—' : `${Math.round(n * 100)}%`);

// Best performer first: most clients, then most returning clients, then fewest fresh (leans least on
// new-client supply), then highest same-day rebook rate. Lexicographic — each tie-break only decides
// when the prior metric is equal.
export function rankProviders(providers: ProviderRetentionRow[]): ProviderRetentionRow[] {
  return [...providers].sort(
    (a, b) =>
      b.clientsSeen - a.clientsSeen ||
      b.returningToProvider - a.returningToProvider ||
      a.newToSalonViaP - b.newToSalonViaP ||
      (b.sameDayRebookRate ?? 0) - (a.sameDayRebookRate ?? 0),
  );
}

export type ScorecardTotals = { clients: number; newP: number; retP: number; fresh: number };

// Column totals. Counts sum directly; rates can't be summed, so rebook is weighted by clients seen and
// retention by (matured) cohort size — i.e. a true salon-wide rate, not an average of averages.
export function totalScorecard(ranked: ProviderRetentionRow[]) {
  const t = ranked.reduce(
    (a, p) => {
      a.clients += p.clientsSeen;
      a.newP += p.newToProvider;
      a.retP += p.returningToProvider;
      a.fresh += p.newToSalonViaP;
      if (p.sameDayRebookRate != null) {
        a.rebookNum += p.sameDayRebookRate * p.clientsSeen;
        a.rebookDen += p.clientsSeen;
      }
      if (p.cohortMatured && p.cohortSize > 0) {
        if (p.providerRetention != null) a.provRetNum += p.providerRetention * p.cohortSize;
        if (p.salonRetention != null) a.salonRetNum += p.salonRetention * p.cohortSize;
        a.cohort += p.cohortSize;
      }
      return a;
    },
    { clients: 0, newP: 0, retP: 0, fresh: 0, rebookNum: 0, rebookDen: 0, provRetNum: 0, salonRetNum: 0, cohort: 0 },
  );
  return {
    totals: { clients: t.clients, newP: t.newP, retP: t.retP, fresh: t.fresh } as ScorecardTotals,
    totRebook: t.rebookDen ? t.rebookNum / t.rebookDen : null,
    totProvRet: t.cohort ? t.provRetNum / t.cohort : null,
    totSalonRet: t.cohort ? t.salonRetNum / t.cohort : null,
  };
}

// Per-provider scorecard for one month — mobile cards + desktop table, with a weighted totals row.
// Shared by the owner/manager retention page and the Manager Home retention section.
export default function ProviderScorecard({
  ranked, retentionWindowDays, monthLabel, totals, totRebook, totProvRet, totSalonRet,
}: {
  ranked: ProviderRetentionRow[];
  retentionWindowDays: number;
  monthLabel: string;
  totals: ScorecardTotals;
  totRebook: number | null;
  totProvRet: number | null;
  totSalonRet: number | null;
}) {
  const t = totals;
  return (
    <>
      <div className="mb-3 flex items-baseline gap-2">
        <h2 className="text-sm font-semibold text-zinc-700">Provider scorecard</h2>
        <span className="text-xs text-zinc-400">{monthLabel} · best performer first</span>
      </div>
      {ranked.length === 0 ? (
        <p className="rounded-lg px-4 py-4 text-sm text-zinc-400 ring-1 ring-zinc-200">No visits this month yet.</p>
      ) : (
        <>
          {/* Mobile: cards */}
          <ul data-testid="retention-scorecard-cards" className="space-y-3 sm:hidden">
            {ranked.map((p) => (
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
              </li>
            ))}
            {/* Mobile totals card. */}
            <li className="rounded-xl bg-zinc-50 p-4 ring-1 ring-zinc-300">
              <span className="font-semibold text-zinc-800">Total · {ranked.length} providers</span>
              <div className="mt-2 grid grid-cols-2 gap-x-4 gap-y-1 text-xs text-zinc-600">
                <Stat label="Clients seen" value={`${t.clients}`} />
                <Stat label="New / returning" value={`${t.newP} / ${t.retP}`} />
                <Stat label="Fresh (new to salon)" value={`${t.fresh}`} />
                <Stat label="Same-day rebook" value={pct(totRebook)} />
                <Stat label="Returned to provider" value={pct(totProvRet)} />
                <Stat label="Returned to salon" value={pct(totSalonRet)} />
              </div>
            </li>
          </ul>

          {/* Desktop: table. Numeric columns are right-aligned so values (and the totals row) line up. */}
          <div className="hidden overflow-x-auto rounded-lg ring-1 ring-zinc-200 sm:block">
            <table data-testid="retention-scorecard-table" className="w-full text-sm">
              <thead className="bg-zinc-50 text-[11px] uppercase tracking-wide text-zinc-500">
                <tr>
                  <th className="px-3 py-2 text-left font-medium">Provider</th>
                  <th className="px-3 py-2 text-right font-medium">
                    Clients<InfoTip label="Clients" text="Distinct clients this provider served this month." />
                  </th>
                  <th className="px-3 py-2 text-right font-medium">
                    New / Ret.
                    <InfoTip label="New / Returning" text="New to this provider this month / clients they had served before." />
                  </th>
                  <th className="px-3 py-2 text-right font-medium">
                    Fresh
                    <InfoTip label="Fresh" text="Brand-new salon clients this provider personally acquired — their first-ever visit was with this provider." />
                  </th>
                  <th className="px-3 py-2 text-right font-medium">
                    Rebook
                    <InfoTip label="Rebook" text="Share of this provider's visits where the client booked their next appointment the same day." />
                  </th>
                  <th className="px-3 py-2 text-right font-medium">
                    Retention
                    <InfoTip
                      label="Retention"
                      text={`Of this provider's new clients, the share who returned within ${retentionWindowDays} days — to this provider / to the salon. Recent cohorts show "too soon".`}
                    />
                  </th>
                  <th className="px-3 py-2 text-right font-medium">
                    <span className="sr-only">Risk</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {ranked.map((p) => (
                  <tr key={p.providerRef} className="border-t border-zinc-100">
                    <td className="px-3 py-2 font-medium text-zinc-800">{p.providerName}</td>
                    <td className="px-3 py-2 text-right tabular-nums">{p.clientsSeen}</td>
                    <td className="px-3 py-2 text-right tabular-nums">
                      <span className="text-green-700">{p.newToProvider}</span> / {p.returningToProvider}
                    </td>
                    <td className="px-3 py-2 text-right tabular-nums">{p.newToSalonViaP}</td>
                    <td className="px-3 py-2 text-right tabular-nums">{pct(p.sameDayRebookRate)}</td>
                    <td className="px-3 py-2 text-right tabular-nums">
                      {p.cohortMatured ? (
                        <span>
                          <span className="font-medium text-zinc-800">{pct(p.providerRetention)}</span>
                          <span className="text-zinc-400"> / {pct(p.salonRetention)}</span>
                        </span>
                      ) : (
                        <span className="text-xs italic text-zinc-400">too soon</span>
                      )}
                    </td>
                    <td className="px-3 py-2 text-right">{p.leakRisk ? <RiskBadge /> : null}</td>
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr className="border-t-2 border-zinc-300 bg-zinc-50 font-medium text-zinc-800">
                  <td className="px-3 py-2">
                    Total <span className="font-normal text-zinc-400">· {ranked.length}</span>
                  </td>
                  <td className="px-3 py-2 text-right tabular-nums">{t.clients}</td>
                  <td className="px-3 py-2 text-right tabular-nums">
                    <span className="text-green-700">{t.newP}</span> / {t.retP}
                  </td>
                  <td className="px-3 py-2 text-right tabular-nums">{t.fresh}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{pct(totRebook)}</td>
                  <td className="px-3 py-2 text-right tabular-nums">
                    <span>{pct(totProvRet)}</span>
                    <span className="font-normal text-zinc-400"> / {pct(totSalonRet)}</span>
                  </td>
                  <td className="px-3 py-2"></td>
                </tr>
              </tfoot>
            </table>
          </div>

          <p className="mt-3 text-[11px] text-zinc-400">
            <span className="font-medium">Fresh</span> = brand-new salon clients this provider acquired.{' '}
            <span className="font-medium text-amber-700">⚠ At risk</span> = many fresh clients but a low return
            rate. Retention shows <span className="text-zinc-600">provider</span> /{' '}
            <span className="text-zinc-400">salon</span>; recent cohorts read <span className="italic">too soon</span>{' '}
            until the {retentionWindowDays}-day window elapses. Total-row rates are salon-wide (weighted),
            not an average of the rows.
          </p>
        </>
      )}
    </>
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
