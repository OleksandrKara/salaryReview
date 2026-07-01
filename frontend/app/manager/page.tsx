import Link from 'next/link';
import { redirect } from 'next/navigation';
import { serverApi } from '../lib/serverApi';
import { t } from '../lib/i18n';
import PageHeader from '../components/PageHeader';
import ProviderScorecard, { rankProviders, totalScorecard } from '../owner/retention/ProviderScorecard';

const MONTHS = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];

// Manager home — a focused hub for the few things a manager owns: redos, the knowledge base, SOPs,
// and the floating assistant. Managers don't manage salaries, so this replaces the salary report as
// their landing (the proxy also keeps them out of owner areas). Owner/provider are redirected to
// their own homes; the proxy gates the page edge-side too.
//
// Retention: managers get the same provider scorecard owners see (view-only — reused wholesale from
// /owner/retention, which has no edit actions) surfaced right here rather than tucked behind a menu
// link; "View full report" leads to the date-range/chart view, which managers can also reach directly.
export default async function ManagerPage() {
  const me = await serverApi.getMe();
  if (me.role === 'PROVIDER') redirect('/me');
  if (me.role === 'OWNER') redirect('/reports');
  const lang = me.preferredLanguage;

  const tiles = [
    { href: '/admin/redos', title: t(lang, 'mgrRedos'), desc: t(lang, 'mgrRedosDesc') },
    { href: '/kb', title: t(lang, 'mgrKb'), desc: t(lang, 'mgrKbDesc') },
    { href: '/sops', title: t(lang, 'mgrSops'), desc: t(lang, 'mgrSopsDesc') },
  ];

  // Latest complete month (same default as /owner/retention) so the snapshot never shows an
  // unfinished, partial-data month.
  const now = new Date();
  const curYear = now.getUTCFullYear();
  const curMonth = now.getUTCMonth() + 1;
  const year = curMonth === 1 ? curYear - 1 : curYear;
  const month = curMonth === 1 ? 12 : curMonth - 1;

  let report;
  try {
    report = await serverApi.getRetention(year, month);
  } catch {
    report = { year, month, retentionWindowDays: 60, providers: [] };
  }
  const ranked = rankProviders(report.providers);
  const { totals, totRebook, totProvRet, totSalonRet } = totalScorecard(ranked);

  return (
    <main className="mx-auto max-w-3xl px-4 py-10">
      <PageHeader title={t(lang, 'mgrTitle')} role={me.role} language={lang} />
      <p className="mt-1 text-sm text-zinc-500">{t(lang, 'mgrSubtitle')}</p>

      <div className="mt-6 grid gap-3 sm:grid-cols-3">
        {tiles.map((tile) => (
          <Link
            key={tile.href}
            href={tile.href}
            className="rounded-xl p-4 ring-1 ring-zinc-200 transition hover:ring-zinc-400"
          >
            <div className="text-sm font-semibold text-zinc-800">{tile.title}</div>
            <div className="mt-1 text-xs text-zinc-500">{tile.desc}</div>
          </Link>
        ))}
      </div>

      <div className="my-8 border-t border-zinc-200" />
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-sm font-semibold text-zinc-700">Retention</h2>
        <Link href="/owner/retention" className="text-xs font-medium text-zinc-500 hover:text-zinc-800">
          View full report →
        </Link>
      </div>
      <ProviderScorecard
        ranked={ranked}
        retentionWindowDays={report.retentionWindowDays}
        monthLabel={`${MONTHS[month - 1]} ${year}`}
        totals={totals}
        totRebook={totRebook}
        totProvRet={totProvRet}
        totSalonRet={totSalonRet}
      />
    </main>
  );
}
