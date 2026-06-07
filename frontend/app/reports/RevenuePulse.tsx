import { serverApi } from '../lib/serverApi';

const MONTHS = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
const usd = (n: number) =>
  n.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });

function DayRange(month: number, endDay: number) {
  return `${MONTHS[month - 1]} 1–${endDay}`;
}

export default async function RevenuePulse({ year, month }: { year: number; month: number }) {
  let pulse;
  try {
    pulse = await serverApi.getRevenuePulse(year, month);
  } catch {
    return null; // Square unavailable — don't break the page
  }

  const {
    currentEndDay, priorEndDay,
    currentGross, priorGross, deltaPct,
    upcomingBookings, upcomingGross, projectedMonthGross,
  } = pulse;

  const positive = deltaPct != null && deltaPct >= 0;
  const hasUpcoming = upcomingBookings > 0;

  const curLabel  = DayRange(month, currentEndDay);
  const prevMonth = month === 1 ? 12 : month - 1;
  const priorLabel = DayRange(prevMonth, priorEndDay);

  return (
    <div className="mb-4 overflow-hidden rounded-xl border border-zinc-200 bg-white">
      {/* Header strip */}
      <div className="flex items-center justify-between border-b border-zinc-100 bg-zinc-50 px-4 py-2">
        <span className="text-xs font-semibold uppercase tracking-wide text-zinc-400">
          Revenue pulse
        </span>
        <span className="text-xs text-zinc-400">{MONTHS[month - 1]} {year}</span>
      </div>

      <div className="grid gap-px bg-zinc-100 sm:grid-cols-2">
        {/* Left: same-period comparison */}
        <div className="bg-white px-5 py-4">
          <p className="text-xs font-medium text-zinc-400">{curLabel} so far</p>
          <div className="mt-1 flex items-baseline gap-3">
            <span className="text-3xl font-semibold tabular-nums text-zinc-900">
              {usd(currentGross)}
            </span>
            {deltaPct != null && (
              <span className={`text-base font-semibold ${positive ? 'text-green-600' : 'text-red-500'}`}>
                {positive ? '↑' : '↓'} {Math.abs(Number(deltaPct)).toFixed(1)}%
              </span>
            )}
          </div>
          <p className="mt-1 text-xs text-zinc-400">
            vs {priorLabel}: {usd(priorGross)}
            {deltaPct == null && priorGross === 0 && (
              <span className="ml-1">(no prior data)</span>
            )}
          </p>
        </div>

        {/* Right: upcoming projection */}
        <div className="bg-white px-5 py-4">
          <p className="text-xs font-medium text-zinc-400">Upcoming this month</p>
          {hasUpcoming ? (
            <>
              <div className="mt-1 flex items-baseline gap-3">
                <span className="text-3xl font-semibold tabular-nums text-zinc-900">
                  {usd(upcomingGross)}
                </span>
                <span className="text-sm text-zinc-500">
                  est. · {upcomingBookings} booking{upcomingBookings !== 1 ? 's' : ''}
                </span>
              </div>
              <p className="mt-1 text-xs text-zinc-400">
                Projected month total{' '}
                <span className="font-medium text-zinc-600">~{usd(projectedMonthGross)}</span>
                <span className="ml-1 text-zinc-300">(confirmed only)</span>
              </p>
            </>
          ) : (
            <div className="mt-1">
              <span className="text-xl font-semibold text-zinc-400">No upcoming bookings</span>
              <p className="mt-1 text-xs text-zinc-300">for the rest of this month</p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
