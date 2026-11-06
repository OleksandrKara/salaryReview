import Link from 'next/link';
import { serverApi } from '../../../lib/serverApi';
import { t } from '../../../lib/i18n';
import PageHeader from '../../../components/PageHeader';

const PERIODS = [30, 90, 180, 365];

export default async function LostReportPage({ searchParams }: { searchParams: Promise<{ days?: string }> }) {
  const { days: raw } = await searchParams;
  const days = PERIODS.includes(Number(raw)) ? Number(raw) : 90;
  const me = await serverApi.getMe();
  const lang = me.preferredLanguage;
  const report = await serverApi.getLostReport(days);
  const label = (r: string) => {
    const key = `leadLost_${r}`;
    return t(lang, key as 'leadLost_PRICE') || r;
  };

  return (
    <main className="mx-auto max-w-4xl p-4 sm:p-8">
      <PageHeader title={t(lang, 'leadLostReportTitle')} role={me.role} language={lang} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />
      <Link href="/admin/leads" className="text-sm text-zinc-600 underline">{t(lang, 'leadsBack')}</Link>
      <div className="mt-4 flex flex-wrap gap-2">
        {PERIODS.map((d) => (
          <Link
            key={d}
            href={`/admin/leads/lost-report?days=${d}`}
            className={`rounded-full px-3 py-1 text-sm ring-1 ${d === days ? 'bg-zinc-900 text-white ring-zinc-900' : 'text-zinc-700 ring-zinc-300'}`}
          >
            {t(lang, 'leadLostReportDays').replace('{n}', String(d))}
          </Link>
        ))}
      </div>
      {report.total === 0 ? (
        <p className="mt-6 text-sm text-zinc-500">{t(lang, 'leadLostReportEmpty')}</p>
      ) : (
        <div className="mt-6 flex flex-col gap-8">
          <section>
            <h2 className="mb-2 text-sm font-semibold">{t(lang, 'leadLostReportTotal')}: {report.total}</h2>
            <ul className="flex flex-col gap-2">
              {report.byReason.map((r) => (
                <li key={r.reason} className="flex items-center gap-3 text-sm">
                  <span className="w-64 shrink-0">{label(r.reason)}</span>
                  <span className="h-3 rounded bg-zinc-800" style={{ width: `${Math.max(2, (r.count / report.total) * 100)}%` }} />
                  <span className="tabular-nums text-zinc-600">{r.count}</span>
                </li>
              ))}
            </ul>
          </section>
          <section>
            <h2 className="mb-2 text-sm font-semibold">{t(lang, 'leadLostReportBySource')}</h2>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead>
                  <tr className="border-b border-zinc-200 text-xs text-zinc-500">
                    <th className="py-1 pr-4 font-normal">{t(lang, 'leadLostReportSource')}</th>
                    <th className="py-1 pr-4 font-normal">{t(lang, 'leadLostPick')}</th>
                    <th className="py-1 text-right font-normal">{t(lang, 'leadLostReportCount')}</th>
                  </tr>
                </thead>
                <tbody>
                  {report.bySource.map((r) => (
                    <tr key={`${r.source}-${r.reason}`} className="border-b border-zinc-100">
                      <td className="py-1 pr-4">{r.source}</td>
                      <td className="py-1 pr-4">{label(r.reason)}</td>
                      <td className="py-1 text-right tabular-nums">{r.count}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        </div>
      )}
    </main>
  );
}
