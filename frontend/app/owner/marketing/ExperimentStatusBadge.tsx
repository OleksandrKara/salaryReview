import type { MarketingDashboardData } from '../../lib/types';

const STYLES: Record<MarketingDashboardData['experimentStatus'], string> = {
  active: 'bg-emerald-50 text-emerald-700 ring-emerald-200',
  paused: 'bg-amber-50 text-amber-700 ring-amber-200',
  none: 'bg-zinc-100 text-zinc-500 ring-zinc-200',
};

const LABELS: Record<MarketingDashboardData['experimentStatus'], string> = {
  active: 'Active',
  paused: 'Paused',
  none: 'No experiment',
};

export default function ExperimentStatusBadge({ status }: { status: MarketingDashboardData['experimentStatus'] }) {
  return (
    <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ring-1 ring-inset ${STYLES[status]}`}>
      {LABELS[status]}
    </span>
  );
}
