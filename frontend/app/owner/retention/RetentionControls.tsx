'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import type { RetentionProviderOption } from '../../lib/types';

const MONTHS = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];

function years() {
  const cur = new Date().getFullYear();
  const out: number[] = [];
  for (let y = cur + 1; y >= cur - 5; y--) out.push(y);
  return out;
}

// Range picker (from/to, applied on click) + a provider filter (applied immediately). Each preserves
// the other in the URL. Matches the /overview RangePicker styling.
export default function RetentionControls({
  fromYear, fromMonth, toYear, toMonth, provider, providers,
}: {
  fromYear: number; fromMonth: number; toYear: number; toMonth: number;
  provider: string; // '' = all
  providers: RetentionProviderOption[];
}) {
  const router = useRouter();
  const [fy, setFy] = useState(fromYear);
  const [fm, setFm] = useState(fromMonth);
  const [ty, setTy] = useState(toYear);
  const [tm, setTm] = useState(toMonth);

  const sel = 'rounded border border-zinc-300 bg-white px-2 py-1.5 text-sm text-zinc-800 focus:border-zinc-500 focus:outline-none';
  const dirty = fy !== fromYear || fm !== fromMonth || ty !== toYear || tm !== toMonth;

  function url(f: number, fmo: number, t: number, tmo: number, p: string) {
    const q = new URLSearchParams({ fromYear: `${f}`, fromMonth: `${fmo}`, toYear: `${t}`, toMonth: `${tmo}` });
    if (p) q.set('provider', p);
    return `/owner/retention?${q.toString()}`;
  }

  return (
    <div className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-end sm:gap-4">
      <Field label="From">
        <select className={sel} value={fm} onChange={(e) => setFm(Number(e.target.value))}>
          {MONTHS.map((l, i) => <option key={i} value={i + 1}>{l}</option>)}
        </select>
        <select className={sel} value={fy} onChange={(e) => setFy(Number(e.target.value))}>
          {years().map((y) => <option key={y} value={y}>{y}</option>)}
        </select>
      </Field>
      <Field label="To">
        <select className={sel} value={tm} onChange={(e) => setTm(Number(e.target.value))}>
          {MONTHS.map((l, i) => <option key={i} value={i + 1}>{l}</option>)}
        </select>
        <select className={sel} value={ty} onChange={(e) => setTy(Number(e.target.value))}>
          {years().map((y) => <option key={y} value={y}>{y}</option>)}
        </select>
      </Field>
      <button
        onClick={() => router.push(url(fy, fm, ty, tm, provider))}
        disabled={!dirty}
        className={`w-full rounded px-4 py-1.5 text-sm font-medium transition-colors sm:w-auto ${
          dirty ? 'bg-zinc-800 text-white hover:bg-zinc-700' : 'cursor-default bg-zinc-100 text-zinc-400'
        }`}
      >
        Apply
      </button>
      <Field label="Provider">
        <select
          className={`${sel} w-full sm:w-44`}
          value={provider}
          onChange={(e) => router.push(url(fromYear, fromMonth, toYear, toMonth, e.target.value))}
        >
          <option value="">All providers</option>
          {providers.map((p) => <option key={p.ref} value={p.ref}>{p.name}</option>)}
        </select>
      </Field>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-1">
      <span className="text-xs font-medium text-zinc-500">{label}</span>
      <div className="flex gap-2">{children}</div>
    </div>
  );
}
