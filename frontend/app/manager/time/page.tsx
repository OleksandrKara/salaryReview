import { redirect } from 'next/navigation';
import { serverApi } from '../../lib/serverApi';
import { t } from '../../lib/i18n';
import PageHeader from '../../components/PageHeader';
import MonthNav from '../../components/MonthNav';
import TimeTracker from './TimeTracker';

function shift(year: number, month: number, by: number) {
  const idx = (month - 1) + by;
  return { year: year + Math.floor(idx / 12), month: ((idx % 12) + 12) % 12 + 1 };
}

// A manager's own timesheet: live clock in/out plus manual shift entry, with per-pay-period totals.
// Owners are sent to the payroll view; providers don't track time here.
export default async function ManagerTimePage({
  searchParams,
}: {
  searchParams: Promise<{ year?: string; month?: string }>;
}) {
  const me = await serverApi.getMe();
  if (me.role === 'PROVIDER') redirect('/me');
  if (me.role === 'OWNER') redirect('/admin/manager-time');

  const sp = await searchParams;
  const now = new Date();
  const year = Number(sp.year) || now.getUTCFullYear();
  const month = Number(sp.month) || now.getUTCMonth() + 1;
  const lang = me.preferredLanguage;

  const ts = await serverApi.getMyTimesheet(year, month);
  const prev = shift(year, month, -1);
  const next = shift(year, month, 1);

  return (
    <main className="mx-auto max-w-3xl p-4 sm:p-8">
      <PageHeader title={t(lang, 'timeMyTitle')} role={me.role} language={lang} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />
      <div className="-mt-3 mb-4 flex items-center justify-between">
        <p className="text-sm text-zinc-500">{t(lang, 'timeMySubtitle')}</p>
      </div>
      <div className="mb-5">
        <MonthNav base="/manager/time" year={year} month={month} prev={prev} next={next} language={lang} />
      </div>
      <TimeTracker key={`${year}-${month}`} initial={ts} language={lang} />
    </main>
  );
}
