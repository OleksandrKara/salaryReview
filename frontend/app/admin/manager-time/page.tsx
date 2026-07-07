import { redirect } from 'next/navigation';
import { serverApi } from '../../lib/serverApi';
import { t } from '../../lib/i18n';
import PageHeader from '../../components/PageHeader';
import MonthNav from '../../components/MonthNav';
import ManagerTimeAdmin from './ManagerTimeAdmin';

function shift(year: number, month: number, by: number) {
  const idx = (month - 1) + by;
  return { year: year + Math.floor(idx / 12), month: ((idx % 12) + 12) % 12 + 1 };
}

// Owner payroll view of every manager's time for the month, with an inline hourly-rate editor.
export default async function ManagerTimeAdminPage({
  searchParams,
}: {
  searchParams: Promise<{ year?: string; month?: string }>;
}) {
  const me = await serverApi.getMe();
  if (me.role !== 'OWNER') {
    redirect(me.role === 'PROVIDER' ? '/me' : me.role === 'ADS_MANAGER' ? '/owner/marketing' : '/manager/time');
  }

  const sp = await searchParams;
  const now = new Date();
  const year = Number(sp.year) || now.getUTCFullYear();
  const month = Number(sp.month) || now.getUTCMonth() + 1;
  const lang = me.preferredLanguage;

  const data = await serverApi.getAdminTimesheet(year, month);
  const prev = shift(year, month, -1);
  const next = shift(year, month, 1);

  return (
    <main className="mx-auto max-w-4xl p-4 sm:p-8">
      <PageHeader title={t(lang, 'timeOwnerTitle')} role={me.role} language={lang} />
      <p className="-mt-3 mb-4 text-sm text-zinc-500">{t(lang, 'timeOwnerSubtitle')}</p>
      <div className="mb-5">
        <MonthNav base="/admin/manager-time" year={year} month={month} prev={prev} next={next} language={lang} />
      </div>
      <ManagerTimeAdmin key={`${year}-${month}`} initial={data} language={lang} />
    </main>
  );
}
