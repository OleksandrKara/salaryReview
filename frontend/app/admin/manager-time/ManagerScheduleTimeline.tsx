'use client';

import { useMemo, useState } from 'react';
import { t, tf, flagLabel, weekdayShort, monthShort } from '../../lib/i18n';
import type { AdminDailySchedule, AdminScheduleDay, Language } from '../../lib/types';

// One color per manager, assigned deterministically by username so it stays stable across
// months/re-renders rather than shifting with array order.
const PALETTE = ['#0284c7', '#7c3aed', '#d97706', '#e11d48', '#0d9488', '#c026d4'];

// "8:00 AM"/"2:30 PM" -> minutes since midnight. Parsing the already salon-local-formatted label
// (rather than converting the ISO instant client-side) avoids a second timezone conversion here.
function labelToMinutes(label: string): number {
  const m = /^(\d{1,2}):(\d{2})\s*(AM|PM)$/i.exec(label.trim());
  if (!m) return 0;
  let h = parseInt(m[1], 10) % 12;
  if (/pm/i.test(m[3])) h += 12;
  return h * 60 + parseInt(m[2], 10);
}

function fmtHour(min: number): string {
  const h = Math.floor(min / 60) % 24;
  const h12 = h % 12 === 0 ? 12 : h % 12;
  return `${h12}${h < 12 ? 'a' : 'p'}`;
}

function fmtHM(minutes: number): string {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  if (h <= 0) return `${m}m`;
  return m === 0 ? `${h}h` : `${h}h ${m}m`;
}

// Boundary flags (an implausible clock-in/out, or a shift left open) are the strongest "check
// this" signal; overlap-shape flags are milder ("not quite the usual pattern").
const HIGH_SEVERITY_FLAGS = new Set([
  'start_way_off', 'end_way_off', 'still_open', 'gap_in_coverage', 'no_overlap',
]);

function FlagBadge({ code, language }: { code: string; language: Language | null }) {
  const high = HIGH_SEVERITY_FLAGS.has(code);
  return (
    <span
      className={`inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-[10px] font-medium ring-1 ${
        high ? 'bg-rose-50 text-rose-700 ring-rose-300' : 'bg-amber-50 text-amber-700 ring-amber-300'
      }`}
    >
      <span aria-hidden>{high ? '⚠' : '•'}</span> {flagLabel(language, code)}
    </span>
  );
}

function dayHasAnomaly(day: AdminScheduleDay): boolean {
  return day.flags.some((f) => f !== 'no_shifts') || day.shifts.some((s) => s.flags.length > 0);
}

export default function ManagerScheduleTimeline({
  data,
  language,
}: {
  data: AdminDailySchedule;
  language: Language | null;
}) {
  const [anomaliesOnly, setAnomaliesOnly] = useState(false);

  const managerColor = useMemo(() => {
    const seen = new Map<number, string>();
    for (const day of data.days) for (const s of day.shifts) if (!seen.has(s.userId)) seen.set(s.userId, s.username);
    const ordered = [...seen.entries()].sort((a, b) => a[1].localeCompare(b[1]));
    const colors = new Map<number, string>();
    ordered.forEach(([id], i) => colors.set(id, PALETTE[i % PALETTE.length]));
    return colors;
  }, [data.days]);

  const legend = useMemo(() => {
    const seen = new Map<number, string>();
    for (const day of data.days) for (const s of day.shifts) if (!seen.has(s.userId)) seen.set(s.userId, s.username);
    return [...seen.entries()].sort((a, b) => a[1].localeCompare(b[1]));
  }, [data.days]);

  const businessStart = labelToMinutes(data.expectedStartLabel);
  const businessEnd = labelToMinutes(data.expectedEndLabel);
  const anyAnomaly = useMemo(() => data.days.some(dayHasAnomaly), [data.days]);
  const visibleDays = anomaliesOnly ? data.days.filter(dayHasAnomaly) : data.days;

  return (
    <div>
      <p className="mb-3 text-sm text-zinc-500">
        {tf(language, 'scheduleSubtitle', {
          start: data.expectedStartLabel,
          end: data.expectedEndLabel,
          overlap: fmtHM(data.expectedOverlapMinutes),
        })}
      </p>

      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-3">
          {legend.map(([id, name]) => (
            <span key={id} className="inline-flex items-center gap-1.5 text-xs font-medium text-zinc-600">
              <span className="h-2.5 w-2.5 rounded-full" style={{ background: managerColor.get(id) }} />
              {name}
            </span>
          ))}
        </div>
        <label className="inline-flex items-center gap-2 text-xs font-medium text-zinc-600">
          <input
            type="checkbox"
            checked={anomaliesOnly}
            onChange={(e) => setAnomaliesOnly(e.target.checked)}
            className="h-3.5 w-3.5 rounded border-zinc-300"
            data-testid="schedule-anomalies-only"
          />
          {t(language, 'scheduleAnomaliesOnly')}
        </label>
      </div>

      {!anomaliesOnly && !anyAnomaly && (
        <p className="mb-3 rounded-lg bg-emerald-50 px-3 py-2 text-sm text-emerald-700 ring-1 ring-emerald-200">
          {t(language, 'scheduleAllClear')}
        </p>
      )}

      {visibleDays.length === 0 ? (
        <p className="rounded-lg p-6 text-center text-sm text-zinc-500 ring-1 ring-zinc-200">
          {t(language, 'scheduleNoDaysMatch')}
        </p>
      ) : (
        <div className="flex flex-col gap-3">
          {visibleDays.map((day) => (
            <DayCard
              key={day.date}
              day={day}
              language={language}
              managerColor={managerColor}
              businessStart={businessStart}
              businessEnd={businessEnd}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function DayCard({
  day,
  language,
  managerColor,
  businessStart,
  businessEnd,
}: {
  day: AdminScheduleDay;
  language: Language | null;
  managerColor: Map<number, string>;
  businessStart: number;
  businessEnd: number;
}) {
  const [y, m, d] = day.date.split('-').map(Number);
  const dateObj = new Date(y, m - 1, d);
  const flagged = dayHasAnomaly(day);
  const visibleDayFlags = day.flags.filter((f) => f !== 'no_shifts');

  // Axis always covers business hours (+1h buffer each side) and widens further to keep any
  // wildly-off shift (the AM/PM-mistake case) visible instead of clipping it off-screen.
  const starts = day.shifts.map((s) => labelToMinutes(s.startLabel));
  const ends = day.shifts.map((s) => (s.endLabel ? labelToMinutes(s.endLabel) : businessEnd));
  const axisStart = Math.floor(Math.min(businessStart - 60, ...(starts.length ? starts : [businessStart])) / 60) * 60;
  const axisEnd = Math.ceil(Math.max(businessEnd + 60, ...(ends.length ? ends : [businessEnd])) / 60) * 60;
  const span = axisEnd - axisStart;
  const pct = (min: number) => `${((min - axisStart) / span) * 100}%`;

  const hourTicks: number[] = [];
  for (let h = Math.ceil(axisStart / 120) * 120; h <= axisEnd; h += 120) hourTicks.push(h);

  return (
    <div
      className={`rounded-lg p-3 ring-1 sm:p-4 ${flagged ? 'bg-amber-50/40 ring-amber-300' : 'ring-zinc-200'}`}
      data-testid={`schedule-day-${day.date}`}
    >
      <div className="mb-2 flex flex-wrap items-center justify-between gap-x-3 gap-y-1">
        <span className="text-sm font-semibold text-zinc-900">
          {weekdayShort(language, dateObj.getDay())}, {monthShort(language, m - 1)} {d}
        </span>
        {day.shifts.length > 0 && (
          <span className="text-xs tabular-nums text-zinc-400">
            {t(language, 'scheduleCoverage')} {fmtHM(day.coverageMinutes)} · {t(language, 'scheduleOverlap')} {fmtHM(day.overlapMinutes)}
          </span>
        )}
      </div>

      {visibleDayFlags.length > 0 && (
        <div className="mb-2 flex flex-wrap gap-1">
          {visibleDayFlags.map((f) => (
            <FlagBadge key={f} code={f} language={language} />
          ))}
        </div>
      )}

      {day.shifts.length === 0 ? (
        <p className="text-xs text-zinc-400">{t(language, 'scheduleNoShiftsDay')}</p>
      ) : (
        <div className="flex flex-col gap-2">
          <div className="relative ml-14 mr-1 h-3 text-[10px] text-zinc-400 sm:ml-16">
            {hourTicks.map((min) => (
              <span key={min} className="absolute -translate-x-1/2 tabular-nums" style={{ left: pct(min) }}>
                {fmtHour(min)}
              </span>
            ))}
          </div>
          {day.shifts.map((s) => {
            const startMin = labelToMinutes(s.startLabel);
            const endMin = s.endLabel ? labelToMinutes(s.endLabel) : axisEnd;
            const color = managerColor.get(s.userId) ?? '#71717a';
            const shiftFlagged = s.flags.length > 0;
            return (
              <div key={s.id} className="flex flex-col gap-1" data-testid={`schedule-shift-${s.id}`}>
                <div className="flex items-center gap-2">
                  <span className="w-12 shrink-0 truncate text-[11px] font-medium text-zinc-600 sm:w-16 sm:text-xs">
                    {s.username}
                  </span>
                  <div className="relative h-5 flex-1 rounded bg-zinc-100">
                    <div
                      className="absolute inset-y-0 rounded-sm bg-zinc-200/70"
                      style={{ left: pct(businessStart), width: `calc(${pct(businessEnd)} - ${pct(businessStart)})` }}
                    />
                    <div
                      className={`absolute inset-y-0.5 rounded-sm ${s.open ? 'animate-pulse' : ''} ${
                        shiftFlagged ? 'ring-2 ring-amber-500' : ''
                      }`}
                      style={{
                        left: pct(startMin),
                        width: `max(3px, calc(${pct(endMin)} - ${pct(startMin)}))`,
                        background: color,
                        opacity: shiftFlagged ? 0.55 : 0.85,
                      }}
                    />
                  </div>
                  <span className="w-[4.5rem] shrink-0 text-right text-[11px] tabular-nums text-zinc-500 sm:w-28 sm:text-xs">
                    {s.startLabel}–{s.endLabel ?? t(language, 'scheduleOngoing')}
                  </span>
                </div>
                {shiftFlagged && (
                  <div className="flex flex-wrap gap-1 pl-14 sm:pl-16">
                    {s.flags.map((f) => (
                      <FlagBadge key={f} code={f} language={language} />
                    ))}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
