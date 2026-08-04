import { serverApi } from '../lib/serverApi';

const MONTHS = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
const usd = (n: number) =>
  n.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });

function DayRange(month: number, endDay: number, asOfTime: string | null) {
  const base = endDay <= 1 ? `${MONTHS[month - 1]} 1` : `${MONTHS[month - 1]} 1–${endDay}`;
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
    currentGross, currentCard, currentCash,
    priorGross, priorCard, priorCash, deltaPct,
    upcomingBookings, upcomingGross,
    projectedMid, projectedCard, projectedCash, projectedLow, projectedHigh,
    forecastCalibrationDataPoints, forecastHistoryMonths,
    currentMonthLength, priorMonthLength,
    priorProjected, projectedDeltaPct,
  } = pulse;
  // projectedMonthGross (the transparent naive ceiling) is kept on the DTO for debugging but not
  // shown — the calibrated/pattern projection above replaces it.

  const positive = deltaPct != null && deltaPct >= 0;
  const projPositive = projectedDeltaPct != null && projectedDeltaPct >= 0;
  const hasUpcoming = upcomingBookings > 0;
  const hasRange = projectedLow != null && projectedHigh != null;
  // Calibration state: gray dot = pattern-only or cold-start; green dot = calibration active.
  const calibrationActive = forecastCalibrationDataPoints >= 3;
  const calibratingBadge = !hasRange && forecastHistoryMonths < 3;

  const curLabel  = DayRange(month, currentEndDay, asOfTime);
  const prevMonth = month === 1 ? 12 : month - 1;
  const priorLabel = DayRange(prevMonth, priorEndDay, asOfTime);

  // Make an uneven month length obvious. Only for past-month views — the current month compares to the
  // same day+time last month, so nothing is "dropped", just not yet elapsed. When looking at a settled
  // month: flag a prior day left out (May 31 vs June 30) or full months of differing length.
  const isCurrentMonthView = asOfTime != null;
  const prevName = MONTHS[prevMonth - 1];
  let dayNote: string | null = null;
  if (!isCurrentMonthView) {
    const droppedPrior = priorMonthLength - priorEndDay;
    if (droppedPrior > 0) {
      dayNote = `${prevName} has ${priorMonthLength} days — ${
        droppedPrior === 1 ? `the ${priorMonthLength}th is` : `days ${priorEndDay + 1}–${priorMonthLength} are`
      } left out so both sides cover the same ${priorEndDay} days.`;
    } else if (currentEndDay !== priorEndDay) {
      dayNote = `Full months of different length: ${MONTHS[month - 1]} ${currentMonthLength} days vs ${prevName} ${priorMonthLength}.`;
    }
  }

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
          <TenderSplit card={currentCard} cash={currentCash} />
          <p className="mt-2 text-xs text-zinc-400">
            vs {priorLabel}: <span className="tabular-nums">{usd(priorGross)}</span>
            {priorGross > 0 && (
              <span className="text-zinc-400"> ({usd(priorCard)} card · {usd(priorCash)} cash)</span>
            )}
            {deltaPct == null && priorGross === 0 && (
              <span className="ml-1">(no prior data)</span>
            )}
          </p>
          {dayNote && (
            <p className="mt-1.5 flex items-start gap-1 text-[11px] text-amber-600">
              <span aria-hidden>⚠</span>
              <span>{dayNote}</span>
            </p>
          )}
          {isCurrentMonthView && (
            <p className="mt-1.5 text-[11px] text-zinc-400">
              Compared to the same day &amp; time last month, to the minute.
            </p>
          )}
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

          <div className="mt-1 flex flex-wrap items-baseline gap-x-3 gap-y-1">
            <span className="text-3xl font-semibold tabular-nums text-zinc-900">
              ~{usd(projectedMid)}
            </span>
            {projectedDeltaPct != null && (
              <span
                className={`text-base font-semibold ${projPositive ? 'text-green-600' : 'text-red-500'}`}
                title={`Same time last month was pacing toward ${usd(priorProjected!)}`}
              >
                {projPositive ? '↑' : '↓'} {Math.abs(Number(projectedDeltaPct)).toFixed(1)}%
              </span>
            )}
          </div>
          {hasUpcoming && (
            <p className="mt-1 text-xs text-zinc-500">
              {upcomingBookings} upcoming · {usd(upcomingGross)}
            </p>
          )}
          {projectedCard + projectedCash > 0 && (
            <TenderSplit card={projectedCard} cash={projectedCash} approx />
          )}
          {projectedDeltaPct != null && (
            <p className="mt-1.5 text-[11px] text-zinc-400">
              Same time last month, at that pace: <span className="tabular-nums">{usd(priorProjected!)}</span>
            </p>
          )}

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

// Card vs cash breakdown shown under a revenue figure — a colored dot per tender + its dollar amount.
function TenderSplit({ card, cash, approx }: { card: number; cash: number; approx?: boolean }) {
  return (
    <div className="mt-1.5 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs">
      <span className="flex items-center gap-1.5 text-zinc-600">
        <span className="inline-block h-1.5 w-1.5 rounded-full bg-indigo-500" />
        Card <span className="font-medium tabular-nums text-zinc-800">{approx ? '~' : ''}{usd(card)}</span>
      </span>
      <span className="flex items-center gap-1.5 text-zinc-600">
        <span className="inline-block h-1.5 w-1.5 rounded-full bg-emerald-500" />
        Cash <span className="font-medium tabular-nums text-zinc-800">{approx ? '~' : ''}{usd(cash)}</span>
      </span>
    </div>
  );
}
