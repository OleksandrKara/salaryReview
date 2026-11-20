import Link from 'next/link';
import { t } from '../../../lib/i18n';
import type { Language } from '../../../lib/types';
import type { LostReport } from '../../../lib/types';
import {
  LOST_PERIODS, filterLeads, lostHref, reasonCounts, sourceSummaries, toggleSource, type LostFilters,
} from '../../../lib/lostReport';

const chip = 'inline-flex max-w-full items-center gap-1.5 rounded-full px-3 py-1.5 text-sm ring-1 break-words';
const chipOn = 'bg-zinc-900 text-white! ring-zinc-900';
const chipOff = 'text-zinc-700 ring-zinc-300 hover:bg-zinc-50';

/** The lost-leads report body, shared by the CRM page and the Ads Manager's read-only copy (own base path and lead links). */
export default function LostReportView({ f, report, lang, basePath, leadHref }: {
  f: LostFilters; report: LostReport; lang: Language | null; basePath: string; leadHref: (id: number) => string;
}) {
  const label = (r: string) => {
    const key = `leadLost_${r}`;
    return t(lang, key as 'leadLost_PRICE') || r;
  };
  const sourceLabel = (s: string) => s || t(lang, 'leadLostReportUnknownSource');
  const fmtDate = (iso: string) => new Date(iso).toLocaleDateString(lang === 'RU' ? 'ru-RU' : 'en-US', { month: 'short', day: 'numeric', year: 'numeric' });

  const all = report.leads;
  const shown = filterLeads(all, f);
  const summaries = sourceSummaries(all, f);
  const reasons = reasonCounts(all.filter((l) => f.sources.length === 0 || f.sources.includes(l.source ?? '')));
  const filtered = f.sources.length > 0 || f.reason !== null;
  const hrefFor = (x: LostFilters) => lostHref(x, basePath);
  const withDays = (days: number): LostFilters => ({ ...f, days, from: null, to: null });

  return (
    <>

      <section aria-label={t(lang, 'leadLostReportFilters')} className="mt-4 flex flex-col gap-3 rounded-lg p-3 ring-1 ring-zinc-200">
        <div>
          <p className="mb-1.5 text-xs font-medium uppercase tracking-wide text-zinc-500">{t(lang, 'leadLostReportPeriod')}</p>
          <div className="flex flex-wrap gap-2">
            {LOST_PERIODS.map((d) => (
              <Link key={d} href={hrefFor(withDays(d))} className={`${chip} ${!f.from && d === f.days ? chipOn : chipOff}`}>
                {t(lang, 'leadLostReportDays').replace('{n}', String(d))}
              </Link>
            ))}
            <span className={`${chip} ${f.from ? chipOn : chipOff}`}>{t(lang, 'leadLostReportCustom')}</span>
          </div>
          <form method="get" action={basePath} className="mt-2 flex flex-wrap items-end gap-2">
            {f.sources.map((s) => <input key={s} type="hidden" name="source" value={s} />)}
            {f.reason && <input type="hidden" name="reason" value={f.reason} />}
            <label className="flex flex-col text-xs text-zinc-500">{t(lang, 'leadLostReportFrom')}
              <input type="date" name="from" required defaultValue={f.from ?? ''} className="rounded border border-zinc-300 px-2 py-1.5 text-base text-zinc-900 sm:text-sm" />
            </label>
            <label className="flex flex-col text-xs text-zinc-500">{t(lang, 'leadLostReportTo')}
              <input type="date" name="to" defaultValue={f.to ?? ''} className="rounded border border-zinc-300 px-2 py-1.5 text-base text-zinc-900 sm:text-sm" />
            </label>
            <button type="submit" className="rounded bg-zinc-900 px-3 py-1.5 text-sm text-white">{t(lang, 'leadLostReportApply')}</button>
          </form>
        </div>
        {all.length > 0 ? (
          <>
            <div>
              <p className="mb-1.5 text-xs font-medium uppercase tracking-wide text-zinc-500">{t(lang, 'leadLostReportSource')}</p>
              <div className="flex flex-wrap gap-2">
                <Link href={hrefFor({ ...f, sources: [] })} className={`${chip} ${f.sources.length === 0 ? chipOn : chipOff}`}>
                  {t(lang, 'leadLostReportAllSources')}
                </Link>
                {summaries.map((s) => (
                  <Link key={s.source} href={hrefFor(toggleSource(f, s.source))} className={`${chip} ${f.sources.includes(s.source) ? chipOn : chipOff}`}>
                    {sourceLabel(s.source)} <span className="tabular-nums opacity-70">{s.count}</span>
                  </Link>
                ))}
              </div>
            </div>
            <div>
              <p className="mb-1.5 text-xs font-medium uppercase tracking-wide text-zinc-500">{t(lang, 'leadLostReportReason')}</p>
              <div className="flex flex-wrap gap-2">
                <Link href={hrefFor({ ...f, reason: null })} className={`${chip} ${f.reason === null ? chipOn : chipOff}`}>
                  {t(lang, 'leadLostReportAllReasons')}
                </Link>
                {reasons.map((r) => (
                  <Link key={r.reason} href={hrefFor({ ...f, reason: f.reason === r.reason ? null : r.reason })} className={`${chip} ${f.reason === r.reason ? chipOn : chipOff}`}>
                    {label(r.reason)} <span className="tabular-nums opacity-70">{r.count}</span>
                  </Link>
                ))}
              </div>
            </div>
            {filtered ? (
              <Link href={hrefFor({ days: f.days, from: f.from, to: f.to, sources: [], reason: null })} className="self-start text-sm text-zinc-600 underline">{t(lang, 'leadLostReportReset')}</Link>
            ) : null}
          </>
        ) : null}
      </section>

      {all.length === 0 ? (
        <p className="mt-6 text-sm text-zinc-500">{t(lang, 'leadLostReportEmpty')}</p>
      ) : (
        <div className="mt-6 flex flex-col gap-8">
          <section>
            <h2 className="text-sm font-semibold">{t(lang, 'leadLostReportTotal')}: <span className="tabular-nums">{all.length}</span></h2>
            <p className="mt-0.5 text-xs text-zinc-500">{t(lang, 'leadLostReportSourceHint')}</p>
            <ul className="mt-3 grid grid-cols-1 gap-2 sm:grid-cols-2">
              {summaries.map((s) => {
                const on = f.sources.includes(s.source);
                return (
                  <li key={s.source} className="min-w-0">
                    <Link href={hrefFor(toggleSource(f, s.source))} aria-pressed={on} className={`block min-w-0 rounded-lg p-3 ring-1 ${on ? 'bg-zinc-50 ring-zinc-900' : 'ring-zinc-200 hover:bg-zinc-50'}`}>
                      <div className="flex items-baseline justify-between gap-3">
                        <span className="min-w-0 break-words text-sm font-medium">{sourceLabel(s.source)}</span>
                        <span className="shrink-0 text-sm tabular-nums text-zinc-600">{s.count} · {Math.round(s.share * 100)}%</span>
                      </div>
                      <div className="mt-2 h-2 rounded bg-zinc-100">
                        <div className="h-2 rounded bg-zinc-800" style={{ width: `${Math.max(2, s.share * 100)}%` }} />
                      </div>
                      <p className="mt-2 break-words text-xs text-zinc-500">
                        {t(lang, 'leadLostReportTop')}: {s.topReasons.map((r) => `${label(r.reason)} (${r.count})`).join(', ')}
                      </p>
                    </Link>
                  </li>
                );
              })}
            </ul>
          </section>

          <section>
            <h2 className="mb-2 text-sm font-semibold">{t(lang, 'leadLostReportByReason')}</h2>
            <ul className="flex flex-col gap-2.5">
              {reasons.map((r) => (
                <li key={r.reason} className="min-w-0 text-sm">
                  <div className="flex items-baseline justify-between gap-3">
                    <span className="min-w-0 break-words">{label(r.reason)}</span>
                    <span className="shrink-0 tabular-nums text-zinc-600">{r.count}</span>
                  </div>
                  <div className="mt-1 h-2 rounded bg-zinc-100">
                    <div className="h-2 rounded bg-zinc-800" style={{ width: `${Math.max(2, (r.count / Math.max(1, reasons[0].count)) * 100)}%` }} />
                  </div>
                </li>
              ))}
            </ul>
          </section>

          <section>
            <h2 className="mb-2 text-sm font-semibold">
              {t(lang, 'leadLostReportLeads')}: <span className="tabular-nums">{shown.length}</span>
              {filtered ? <span className="font-normal text-zinc-500"> {t(lang, 'leadLostReportOf')} {all.length}</span> : null}
            </h2>
            {shown.length === 0 ? (
              <p className="text-sm text-zinc-500">{t(lang, 'leadLostReportNoMatch')}</p>
            ) : (
              <ul className="flex flex-col gap-2">
                {shown.map((l) => (
                  <li key={l.id} className="min-w-0">
                    <Link href={leadHref(l.id)} aria-label={`${t(lang, 'leadLostReportOpen')} #${l.id}`} className="block min-w-0 rounded-lg p-3 ring-1 ring-zinc-200 hover:bg-zinc-50">
                      <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-0.5">
                        <span className="min-w-0 break-words text-sm font-medium underline decoration-zinc-300 underline-offset-2">
                          {l.name || t(lang, 'leadLostReportNoName')}
                          {l.phoneLast4 ? <span className="font-normal text-zinc-500"> ···{l.phoneLast4}</span> : null}
                        </span>
                        <span className="shrink-0 text-xs tabular-nums text-zinc-500">{fmtDate(l.lostAt)}</span>
                      </div>
                      <div className="mt-1.5 flex flex-wrap items-center gap-1.5 text-xs">
                        <span className="rounded-full bg-zinc-100 px-2 py-0.5 text-zinc-700">{label(l.reason)}</span>
                        <span className="rounded-full px-2 py-0.5 text-zinc-600 ring-1 ring-zinc-200">{sourceLabel(l.source ?? '')}</span>
                      </div>
                      {l.comment ? <p className="mt-1.5 line-clamp-2 break-words text-xs text-zinc-600">{l.comment}</p> : null}
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      )}
    </>
  );
}
