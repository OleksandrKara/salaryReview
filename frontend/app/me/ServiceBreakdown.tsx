import type { ProviderDetail } from '../lib/types';
import CollapsibleHalf from '../components/CollapsibleHalf';

// Provider's per-period service breakdown: each period is a collapsible card (show/hide) that
// expands to the #salary block + the appointment-grouped service table.
export default function ServiceBreakdown({ detail }: { detail: ProviderDetail }) {
  if (!detail.payout) return null;
  const first = detail.services.filter((s) => s.half === 'FIRST');
  const second = detail.services.filter((s) => s.half === 'SECOND');
  const { tierApplied, firstHalf, secondHalf } = detail.payout;

  return (
    <section className="mt-8">
      <h2 className="mb-1 text-sm font-semibold">Service breakdown</h2>
      <p className="mb-3 text-xs text-zinc-500">
        Every service this period, with discounts and cash notes, so you can check your numbers.
      </p>
      <div className="flex flex-col gap-3">
        <CollapsibleHalf title="1–15" lines={first} settlement={firstHalf} message={detail.firstHalfMessage} tierApplied={tierApplied} baseRate={firstHalf.appliedRate} showSalary={false} />
        <CollapsibleHalf title="16–end" lines={second} settlement={secondHalf} message={detail.secondHalfMessage} tierApplied={tierApplied} baseRate={firstHalf.appliedRate} showSalary={false} />
      </div>
    </section>
  );
}
