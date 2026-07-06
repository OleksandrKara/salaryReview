import type { RevenuePulse } from '../../../lib/types';

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const usd = (n: number) =>
  n.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });

function dayRangeLabel(month: number, endDay: number, asOfTime: string | null) {
  const base = endDay <= 1 ? `${MONTHS[month - 1]} 1` : `${MONTHS[month - 1]} 1–${endDay}`;
  return asOfTime ? `${base} (as of ${asOfTime})` : base;
}

export default function PulseView({ pulse }: { pulse: RevenuePulse }) {
  const {
    year, month, currentEndDay, priorEndDay, asOfTime,
    currentGross, currentCard, currentCash,
    priorGross, priorCard, priorCash, deltaPct,
    upcomingBookings, upcomingGross,
    projectedMid, projectedCard, projectedCash, projectedLow, projectedHigh,
    forecastCalibrationDataPoints, forecastHistoryMonths,
  } = pulse;

  const positive = deltaPct != null && deltaPct >= 0;
  const hasUpcoming = upcomingBookings > 0;
  const hasRange = projectedLow != null && projectedHigh != null;
  const calibrationActive = forecastCalibrationDataPoints >= 3;
  const calibratingBadge = !hasRange && forecastHistoryMonths < 3;

  const soFarLabel = dayRangeLabel(month, currentEndDay, asOfTime);
  const prevMonth = month === 1 ? 12 : month - 1;
  const priorLabel = dayRangeLabel(prevMonth, priorEndDay, asOfTime);

  // How much of the projection is already "in the bank" — a quick visual sense of pacing through
  // the month, not shown by the compact widget this page is based on (no room for it there).
  const pacePct = projectedMid > 0 ? Math.min(100, Math.round((currentGross / projectedMid) * 100)) : 0;

  return (
    <div className="space-y-4">
      <div className="text-xs text-zinc-400">{MONTHS[month - 1]} {year}</div>

      <div className="grid gap-4 lg:grid-cols-2">
        {/* So far */}
        <div className="rounded-2xl border border-zinc-200 bg-white p-5 sm:p-6">
          <p className="text-xs font-medium uppercase tracking-wide text-zinc-400">{soFarLabel} so far</p>
          <div className="mt-2 flex flex-wrap items-baseline gap-x-3 gap-y-1">
            <span className="text-4xl font-semibold tabular-nums text-zinc-900 sm:text-5xl">{usd(currentGross)}</span>
            {deltaPct != null && (
              <span className={`text-base font-semibold ${positive ? 'text-green-600' : 'text-red-500'}`}>
                {positive ? '↑' : '↓'} {Math.abs(Number(deltaPct)).toFixed(1)}%
              </span>
            )}
          </div>

          <TenderSplit card={currentCard} cash={currentCash} />

          <p className="mt-3 text-xs text-zinc-400">
            vs {priorLabel}: <span className="tabular-nums">{usd(priorGross)}</span>
            {priorGross > 0 && <span> ({usd(priorCard)} card · {usd(priorCash)} cash)</span>}
            {deltaPct == null && priorGross === 0 && <span className="ml-1">(no prior data)</span>}
          </p>
          {asOfTime && (
            <p className="mt-1 text-[11px] text-zinc-400">Compared to the same day &amp; time last month.</p>
          )}
        </div>

        {/* Projected */}
        <div className="rounded-2xl border border-zinc-200 bg-white p-5 sm:p-6">
          <div className="flex items-center justify-between gap-2">
            <p className="text-xs font-medium uppercase tracking-wide text-zinc-400">Projected month total</p>
            <span
              className="inline-flex items-center gap-1.5 text-[10px] text-zinc-400"
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
                  calibrationActive ? 'bg-green-500' : forecastHistoryMonths >= 3 ? 'bg-amber-400' : 'bg-zinc-300'
                }`}
              />
              {calibrationActive ? 'calibrated' : forecastHistoryMonths >= 3 ? 'warming up' : 'calibrating'}
            </span>
          </div>

          <div className="mt-2 flex flex-wrap items-baseline gap-x-3 gap-y-1">
            <span className="text-4xl font-semibold tabular-nums text-zinc-900 sm:text-5xl">~{usd(projectedMid)}</span>
          </div>

          {projectedCard + projectedCash > 0 && <TenderSplit card={projectedCard} cash={projectedCash} approx />}

          {hasUpcoming && (
            <p className="mt-3 text-xs text-zinc-500">
              {upcomingBookings} upcoming booking{upcomingBookings === 1 ? '' : 's'} · {usd(upcomingGross)}
            </p>
          )}
          {hasRange ? (
            <p className="mt-1 text-xs text-zinc-400">
              Range <span className="font-medium text-zinc-600">{usd(projectedLow!)} – {usd(projectedHigh!)}</span>
            </p>
          ) : calibratingBadge ? (
            <p className="mt-1 text-xs text-zinc-400">
              <span className="font-medium text-zinc-500">Naive estimate</span> · more history needed for a range
            </p>
          ) : null}
        </div>
      </div>

      {/* Pacing bar: how much of the projected total is already earned */}
      {projectedMid > 0 && (
        <div className="rounded-2xl border border-zinc-200 bg-white p-5 sm:p-6">
          <div className="flex items-baseline justify-between text-xs text-zinc-500">
            <span>Earned so far</span>
            <span className="font-medium text-zinc-700">{pacePct}% of projected</span>
          </div>
          <div className="mt-2 h-2.5 w-full overflow-hidden rounded-full bg-zinc-100">
            <div
              className="h-full rounded-full bg-zinc-900 transition-[width]"
              style={{ width: `${pacePct}%` }}
              role="progressbar"
              aria-valuenow={pacePct}
              aria-valuemin={0}
              aria-valuemax={100}
            />
          </div>
          <div className="mt-1.5 flex justify-between text-[11px] text-zinc-400">
            <span>{usd(currentGross)}</span>
            <span>~{usd(projectedMid)}</span>
          </div>
        </div>
      )}
    </div>
  );
}

function TenderSplit({ card, cash, approx }: { card: number; cash: number; approx?: boolean }) {
  return (
    <div className="mt-3 flex flex-wrap items-center gap-x-5 gap-y-1.5 text-sm">
      <span className="flex items-center gap-2 text-zinc-600">
        <span className="inline-block h-2 w-2 rounded-full bg-indigo-500" />
        Card <span className="font-semibold tabular-nums text-zinc-800">{approx ? '~' : ''}{usd(card)}</span>
      </span>
      <span className="flex items-center gap-2 text-zinc-600">
        <span className="inline-block h-2 w-2 rounded-full bg-emerald-500" />
        Cash <span className="font-semibold tabular-nums text-zinc-800">{approx ? '~' : ''}{usd(cash)}</span>
      </span>
    </div>
  );
}
