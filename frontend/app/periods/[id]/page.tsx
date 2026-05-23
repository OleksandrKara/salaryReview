import Link from 'next/link';
import { api } from '../../lib/api';
import DeletePeriodButton from './DeletePeriodButton';
import PeriodEditor from './PeriodEditor';

// Next 16: params is a Promise — must be awaited.
export default async function PeriodPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const periodId = Number(id);

  const [detail, providers] = await Promise.all([
    api.getPeriod(periodId),
    api.listProviders(),
  ]);

  return (
    <main className="mx-auto max-w-6xl p-8">
      <div className="mb-6 flex items-baseline gap-3">
        <Link href="/" className="text-sm text-zinc-500 hover:text-zinc-700">
          ← All periods
        </Link>
        <h1 className="text-2xl font-semibold">{detail.period.label}</h1>
        <DeletePeriodButton periodId={periodId} label={detail.period.label} />
      </div>

      <PeriodEditor
        periodId={periodId}
        providers={providers}
        initialEntries={detail.entries}
      />
    </main>
  );
}
