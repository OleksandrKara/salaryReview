import { serverApi } from '../lib/serverApi';

const MONTHS = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
const usd = (n: number) =>
  n.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });

function DayRange(month: number, endDay: number, asOfTime: string | null) {
  const base = `${MONTHS[month - 1]} 1–${endDay}`;
  return asOfTime ? `${base} (${asOfTime})` : base;
}

export default async function RevenuePulse({ year, month }: { year: number; month: number }) {
  let pulse;
  try {
    pulse = await serverApi.getRevenuePulse(year, month);
  } catch {
    return null; // Square unavailable — don't break the page
  }

  const {
    currentEndDay, priorEndDay, asOfTime,
    currentGross, priorGross, deltaPct,
    upcomingBookings, upcomingGross,
    projectedMid, projectedLow, projectedHigh,
    forecastCalibrationDataPoints, forecastHistoryMonths,
  } = pulse;
  // projectedMonthGross (the transparent naive ceiling) is kept on the DTO for debugging but not
  // shown — the calibrated/pattern projection above replaces it.

  const positive = deltaPct != null && deltaPct >= 0;
  const hasUpcoming = upcomingBookings > 0;
  const hasRange = projectedLow != null && projectedHigh != null;
  // Calibration state: gray dot = pattern-only or cold-start; green dot = calibration active.
  const calibrationActive = forecastCalibrationDataPoints >= 3;
  const calibratingBadge = !hasRange && forecastHistoryMonths < 3;

  const curLabel  = DayRange(month, currentEndDay, asOfTime);
  const prevMonth = month === 1 ? 12 : month - 1;
  const priorLabel = DayRange(prevMonth, priorEndDay, asOfTime);

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

        {/* Right: smart forecast */}
        <div className="bg-white px-5 py-4">
          <div className="flex items-center justify-between gap-2">
            <p className="text-xs font-medium text-zinc-400">Projected month total</p>
            <span
              className="inline-flex items-center gap-1 text-[10px] text-zinc-400"
              title={
                calibrationActive
                  ? `Calibrated with ${forecastCalibrationDataPoints} months of past projections`
                  : forecastHistoryMonths >= 3
                  ? `Pattern-matching from ${forecastHistoryMonths} months of history (calibration warming up)`
                  : 'More history needed for a range forecast'
              }
            >
              <span
                className={`inline-block h-1.5 w-1.5 rounded-full ${
                  calibrationActive
                    ? 'bg-green-500'
                    : forecastHistoryMonths >= 3
                    ? 'bg-amber-400'
                    : 'bg-zinc-300'
                }`}
              />
              {calibrationActive
                ? 'calibrated'
                : forecastHistoryMonths >= 3
                ? 'warming up'
                : 'calibrating'}
            </span>
          </div>

          <div className="mt-1 flex items-baseline gap-3">
            <span className="text-3xl font-semibold tabular-nums text-zinc-900">
              ~{usd(projectedMid)}
            </span>
            {hasUpcoming && (
              <span className="text-sm text-zinc-500">
                · {upcomingBookings} upcoming · {usd(upcomingGross)}
              </span>
            )}
          </div>

          {hasRange ? (
            <p className="mt-1 text-xs text-zinc-400">
              Range{' '}
              <span className="font-medium text-zinc-600">
                {usd(projectedLow!)} – {usd(projectedHigh!)}
              </span>
            </p>
          ) : calibratingBadge ? (
            <p className="mt-1 text-xs text-zinc-400">
              <span className="font-medium text-zinc-500">Naive estimate</span> · more history needed for a range
            </p>
          ) : null}
        </div>
      </div>
    </div>
  );
}
