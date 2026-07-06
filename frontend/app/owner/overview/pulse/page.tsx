import { redirect } from 'next/navigation';
import { serverApi } from '../../../lib/serverApi';
import PageHeader from '../../../components/PageHeader';
import MonthNav from '../../../components/MonthNav';
import RevenueTabs from '../RevenueTabs';
import PulseView from './PulseView';

function shift(year: number, month: number, by: number) {
  const idx = month - 1 + by;
  return { year: year + Math.floor(idx / 12), month: ((idx % 12) + 12) % 12 + 1 };
}

export default async function RevenuePulsePage({
  searchParams,
}: {
  searchParams: Promise<{ year?: string; month?: string }>;
}) {
  const sp = await searchParams;
  const now = new Date();
  const year = Number(sp.year) || now.getUTCFullYear();
  const month = Number(sp.month) || now.getUTCMonth() + 1;

  const me = await serverApi.getMe();
  if (me?.role !== 'OWNER') redirect('/reports');

  let pulse;
  try {
    pulse = await serverApi.getRevenuePulse(year, month);
  } catch {
    pulse = null;
  }

  const prev = shift(year, month, -1);
  const next = shift(year, month, 1);

  return (
    <main className="mx-auto max-w-4xl p-4 sm:p-8">
      <PageHeader title="Revenue" role={me.role} language={me.preferredLanguage} />
      <RevenueTabs />

      <div className="mb-4">
        <MonthNav base="/owner/overview/pulse" year={year} month={month} prev={prev} next={next} language={me.preferredLanguage} />
      </div>

      {pulse ? (
        <PulseView pulse={pulse} />
      ) : (
        <div className="rounded-2xl border border-dashed border-zinc-300 p-8 text-center text-sm text-zinc-500">
          Revenue data isn&apos;t available right now — Square may be temporarily unreachable. Try again shortly.
        </div>
      )}
    </main>
  );
}
