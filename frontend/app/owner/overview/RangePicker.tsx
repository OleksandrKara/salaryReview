'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';

const MONTH_LABELS = [
  'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
  'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec',
];

function yearOptions() {
  const cur = new Date().getFullYear();
  const out: number[] = [];
  for (let y = cur + 1; y >= cur - 5; y--) out.push(y);
  return out;
}

function MonthYearSelect({
  label,
  month,
  year,
  onMonth,
  onYear,
}: {
  label: string;
  month: number;
  year: number;
  onMonth: (m: number) => void;
  onYear: (y: number) => void;
}) {
  const sel =
    'rounded border border-zinc-300 bg-white px-2 py-1.5 text-sm text-zinc-800 focus:border-zinc-500 focus:outline-none';
  return (
    <div className="flex flex-col gap-1">
      <span className="text-xs font-medium text-zinc-500">{label}</span>
      <div className="flex gap-2">
        <select data-testid={`range-picker-${label.toLowerCase()}-month`} className={sel} value={month} onChange={(e) => onMonth(Number(e.target.value))}>
          {MONTH_LABELS.map((l, i) => (
            <option key={i + 1} value={i + 1}>{l}</option>
          ))}
        </select>
        <select data-testid={`range-picker-${label.toLowerCase()}-year`} className={sel} value={year} onChange={(e) => onYear(Number(e.target.value))}>
          {yearOptions().map((y) => (
            <option key={y} value={y}>{y}</option>
          ))}
        </select>
      </div>
    </div>
  );
}

export default function RangePicker({
  fromYear, fromMonth, toYear, toMonth, basePath = '/owner/overview',
}: {
  fromYear: number; fromMonth: number; toYear: number; toMonth: number;
  /** Which Revenue tab's route to apply the new range to — each tab (Gross/Net) fetches the same
   * range-scoped data independently, so this must match wherever RangePicker is actually rendered. */
  basePath?: string;
}) {
  const router = useRouter();
  const [fy, setFy] = useState(fromYear);
  const [fm, setFm] = useState(fromMonth);
  const [ty, setTy] = useState(toYear);
  const [tm, setTm] = useState(toMonth);

  function apply() {
    router.push(`${basePath}?fromYear=${fy}&fromMonth=${fm}&toYear=${ty}&toMonth=${tm}`);
  }

  const isDirty = fy !== fromYear || fm !== fromMonth || ty !== toYear || tm !== toMonth;

  return (
    <div className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-end sm:gap-4">
      <div className="flex gap-3 sm:gap-4">
        <MonthYearSelect label="From" month={fm} year={fy} onMonth={setFm} onYear={setFy} />
        <MonthYearSelect label="To"   month={tm} year={ty} onMonth={setTm} onYear={setTy} />
      </div>
      <button
        data-testid="range-picker-apply"
        onClick={apply}
        className={`w-full rounded px-4 py-1.5 text-sm font-medium transition-colors sm:w-auto ${
          isDirty
            ? 'bg-zinc-800 text-white hover:bg-zinc-700'
            : 'cursor-default bg-zinc-100 text-zinc-400'
        }`}
        disabled={!isDirty}
      >
        Apply
      </button>
    </div>
  );
}
