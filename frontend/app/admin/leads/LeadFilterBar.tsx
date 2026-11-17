'use client';

import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { t } from '../../lib/i18n';
import type { Language, LeadFilterOptions } from '../../lib/types';
import {
  DATE_PRESETS, STATUS_FILTERS, activeCount, presetRange,
  type DateFilter, type DatePreset, type LeadFilters, type SortField,
} from './leadFilters';

const CLOSE_DISTANCE = 90;
const PRESET_KEY: Record<DatePreset, string> = {
  today: 'lfToday', yesterday: 'lfYesterday', last7: 'lfLast7', last30: 'lfLast30', thisMonth: 'lfThisMonth', lastMonth: 'lfLastMonth', custom: 'lfCustom',
};
const chip = 'min-h-11 rounded-full px-3 py-1 text-sm ring-1 sm:min-h-0';
const on = 'bg-zinc-900 text-white ring-zinc-900';
const off = 'bg-white text-zinc-700 ring-zinc-300 hover:bg-zinc-50';

type Lang = Language | null;
const tr = (l: Lang, k: string) => t(l, k as 'lfFilters');

export function dateLabel(l: Lang, d: DateFilter): string {
  if (!d) return tr(l, 'lfAny');
  if (d.preset !== 'custom') return tr(l, PRESET_KEY[d.preset]);
  return `${d.from || '…'} - ${d.to || '…'}`;
}

function SourceList({ language, options, value, onChange }: { language: Lang; options: LeadFilterOptions | null; value: string[]; onChange: (v: string[]) => void }) {
  const [needle, setNeedle] = useState('');
  const list = (options?.sources ?? []).filter((s) => s.source.toLowerCase().includes(needle.trim().toLowerCase()));
  const toggle = (s: string) => onChange(value.includes(s) ? value.filter((x) => x !== s) : [...value, s]);
  return (
    <div className="flex flex-col gap-2">
      {(options?.sources.length ?? 0) > 8 && (
        <input value={needle} onChange={(e) => setNeedle(e.target.value)} placeholder={tr(language, 'lfSearchSources')} aria-label={tr(language, 'lfSearchSources')}
          className="w-full rounded border border-zinc-300 px-3 py-2 text-sm focus:border-zinc-500 focus:outline-none" />
      )}
      <ul className="flex max-h-60 flex-col overflow-y-auto overscroll-contain">
        {list.map((s) => (
          <li key={s.source}>
            <label className="flex min-h-11 cursor-pointer items-center gap-3 rounded px-1 text-sm hover:bg-zinc-50 sm:min-h-9">
              <input type="checkbox" checked={value.includes(s.source)} onChange={() => toggle(s.source)} className="h-4 w-4 shrink-0" />
              <span className="min-w-0 flex-1 break-words">{s.source}</span>
              <span className="tabular-nums text-xs text-zinc-500">{s.count}</span>
            </label>
          </li>
        ))}
        {options && list.length === 0 && <li className="px-1 py-2 text-sm text-zinc-500">{tr(language, 'lfNoSources')}</li>}
      </ul>
    </div>
  );
}

function StatusList({ language, options, value, onChange }: { language: Lang; options: LeadFilterOptions | null; value: string[]; onChange: (v: string[]) => void }) {
  const toggle = (s: string) => onChange(value.includes(s) ? value.filter((x) => x !== s) : [...value, s]);
  return (
    <ul className="flex flex-col">
      {STATUS_FILTERS.map((s) => (
        <li key={s}>
          <label className="flex min-h-11 cursor-pointer items-center gap-3 rounded px-1 text-sm hover:bg-zinc-50 sm:min-h-9">
            <input type="checkbox" checked={value.includes(s)} onChange={() => toggle(s)} className="h-4 w-4 shrink-0" />
            <span className="min-w-0 flex-1">{t(language, `leadStatus${s}` as 'leadStatusNEW')}</span>
            {options && <span className="tabular-nums text-xs text-zinc-500">{options.statuses[s] ?? 0}</span>}
          </label>
        </li>
      ))}
    </ul>
  );
}

function DateList({ language, value, now, onChange }: { language: Lang; value: DateFilter; now: number; onChange: (v: DateFilter) => void }) {
  const choose = (p: DatePreset) => onChange(p === 'custom' ? { preset: 'custom', from: value?.from ?? '', to: value?.to ?? '' } : { preset: p, ...presetRange(p, now) });
  const bad = value?.preset === 'custom' && value.from && value.to && value.from > value.to;
  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap gap-2" role="radiogroup">
        <button type="button" role="radio" aria-checked={value == null} onClick={() => onChange(null)} className={`${chip} ${value == null ? on : off}`}>{tr(language, 'lfAny')}</button>
        {DATE_PRESETS.map((p) => (
          <button key={p} type="button" role="radio" aria-checked={value?.preset === p} onClick={() => choose(p)} className={`${chip} ${value?.preset === p ? on : off}`}>
            {tr(language, PRESET_KEY[p])}
          </button>
        ))}
      </div>
      {value?.preset === 'custom' && (
        <div className="grid grid-cols-2 gap-2">
          {(['from', 'to'] as const).map((k) => (
            <label key={k} className="flex min-w-0 flex-col gap-1 text-xs text-zinc-600">
              {tr(language, k === 'from' ? 'lfFrom' : 'lfTo')}
              <input type="date" value={value[k]} onChange={(e) => onChange({ ...value, [k]: e.target.value })}
                className="min-h-11 w-full min-w-0 rounded border border-zinc-300 px-2 py-1 text-sm text-zinc-900 sm:min-h-0" />
            </label>
          ))}
        </div>
      )}
      {bad && <p className="text-xs text-red-700">{tr(language, 'lfTo')} &lt; {tr(language, 'lfFrom')}</p>}
      <p className="text-xs text-zinc-500">{tr(language, 'lfDays')}</p>
    </div>
  );
}

function SortControl({ language, value, asc, onChange }: { language: Lang; value: SortField | null; asc: boolean; onChange: (f: SortField | null, asc: boolean) => void }) {
  return (
    <div className="flex flex-wrap items-center gap-2">
      <select aria-label={tr(language, 'lfSortBy')} value={value ?? ''} onChange={(e) => onChange((e.target.value || null) as SortField | null, asc)}
        className="min-h-11 rounded-full border border-zinc-300 bg-white px-3 py-1 text-sm sm:min-h-0">
        <option value="">{tr(language, 'lfSortDefault')}</option>
        <option value="created">{tr(language, 'lfCreated')}</option>
        <option value="activity">{tr(language, 'lfActivity')}</option>
      </select>
      <button type="button" disabled={value == null} onClick={() => onChange(value, !asc)} aria-label={tr(language, asc ? 'lfOldestFirst' : 'lfNewestFirst')}
        className="inline-flex min-h-11 items-center gap-1 rounded-full border border-zinc-300 bg-white px-3 py-1 text-sm hover:bg-zinc-50 disabled:cursor-not-allowed disabled:opacity-40 sm:min-h-0">
        <span aria-hidden>{asc ? '↑' : '↓'}</span>{tr(language, asc ? 'lfOldestFirst' : 'lfNewestFirst')}
      </button>
    </div>
  );
}

type Section = 'source' | 'status' | 'created' | 'activity';

function Panel({ section, language, options, filters, now, onChange }: {
  section: Section; language: Lang; options: LeadFilterOptions | null; filters: LeadFilters; now: number; onChange: (f: LeadFilters) => void;
}) {
  if (section === 'source') return <SourceList language={language} options={options} value={filters.sources} onChange={(sources) => onChange({ ...filters, sources })} />;
  if (section === 'status') return <StatusList language={language} options={options} value={filters.statuses} onChange={(statuses) => onChange({ ...filters, statuses })} />;
  const key = section;
  return <DateList language={language} now={now} value={filters[key]} onChange={(v) => onChange({ ...filters, [key]: v })} />;
}

const SECTIONS: { id: Section; label: string }[] = [
  { id: 'source', label: 'lfSource' }, { id: 'created', label: 'lfCreated' }, { id: 'activity', label: 'lfActivity' }, { id: 'status', label: 'lfStatus' },
];

function summary(language: Lang, id: Section, f: LeadFilters): string | null {
  if (id === 'source') return f.sources.length ? (f.sources.length === 1 ? f.sources[0] : `${f.sources.length}`) : null;
  if (id === 'status') return f.statuses.length ? (f.statuses.length === 1 ? t(language, `leadStatus${f.statuses[0]}` as 'leadStatusNEW') : `${f.statuses.length}`) : null;
  const d = f[id];
  return d ? dateLabel(language, d) : null;
}

function Popover({ language, id, filters, options, now, onChange }: {
  language: Lang; id: Section; filters: LeadFilters; options: LeadFilterOptions | null; now: number; onChange: (f: LeadFilters) => void;
}) {
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return undefined;
    const close = (e: MouseEvent) => { if (box.current && !box.current.contains(e.target as Node)) setOpen(false); };
    const esc = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('mousedown', close);
    document.addEventListener('keydown', esc);
    return () => { document.removeEventListener('mousedown', close); document.removeEventListener('keydown', esc); };
  }, [open]);
  const label = SECTIONS.find((s) => s.id === id)!.label;
  const sum = summary(language, id, filters);
  return (
    <div ref={box} className="relative">
      <button type="button" aria-expanded={open} aria-haspopup="dialog" onClick={() => setOpen(!open)}
        className={`flex max-w-[16rem] items-center gap-1.5 rounded-full px-3 py-1 text-sm ring-1 ${sum ? 'bg-zinc-100 text-zinc-900 ring-zinc-400' : off}`}>
        <span className="shrink-0">{tr(language, label)}</span>
        {sum && <span className="truncate font-medium">: {sum}</span>}
        <span aria-hidden className="text-xs text-zinc-500">▾</span>
      </button>
      {open && (
        <div role="dialog" aria-label={tr(language, label)} className="absolute left-0 top-full z-30 mt-1 w-72 max-w-[calc(100vw-2rem)] rounded-lg bg-white p-3 shadow-lg ring-1 ring-zinc-200">
          <Panel section={id} language={language} options={options} filters={filters} now={now} onChange={onChange} />
        </div>
      )}
    </div>
  );
}

function FilterSheet({ language, filters, options, now, onChange, onClose, onClear }: {
  language: Lang; filters: LeadFilters; options: LeadFilterOptions | null; now: number; onChange: (f: LeadFilters) => void; onClose: () => void; onClear: () => void;
}) {
  const [drag, setDrag] = useState(0);
  const startY = useRef<number | null>(null);
  const panel = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const before = document.activeElement as HTMLElement | null;
    const overflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    panel.current?.focus();
    return () => { document.body.style.overflow = overflow; before?.focus?.(); };
  }, []);
  const handle = {
    onPointerDown: (e: React.PointerEvent) => { startY.current = e.clientY; e.currentTarget.setPointerCapture(e.pointerId); },
    onPointerMove: (e: React.PointerEvent) => { if (startY.current != null) setDrag(Math.max(0, e.clientY - startY.current)); },
    onPointerUp: () => { const d = drag; startY.current = null; setDrag(0); if (d > CLOSE_DISTANCE) onClose(); },
    onPointerCancel: () => { startY.current = null; setDrag(0); },
  };
  return createPortal(
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-black/45" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div ref={panel} role="dialog" aria-modal="true" aria-label={tr(language, 'lfFilters')} tabIndex={-1}
        onKeyDown={(e) => { if (e.key === 'Escape') onClose(); }}
        style={drag ? { transform: `translateY(${drag}px)`, transition: 'none' } : undefined}
        className="flex max-h-[92dvh] w-full max-w-lg flex-col rounded-t-2xl bg-white pb-[env(safe-area-inset-bottom)] shadow-xl outline-none">
        <div data-sheet-handle role="separator" aria-label={tr(language, 'remDragHandle')} {...handle} className="flex h-7 shrink-0 cursor-grab touch-none items-center justify-center">
          <span className="h-1.5 w-10 rounded-full bg-zinc-300" />
        </div>
        <div className="flex items-center justify-between gap-3 px-4 pb-2">
          <h2 className="font-sans text-lg font-semibold">{tr(language, 'lfFilters')}</h2>
          {activeCount(filters) > 0 && <button type="button" onClick={onClear} className="min-h-11 px-2 text-sm text-zinc-600 underline">{tr(language, 'lfClearAll')}</button>}
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 pb-3">
          <div className="flex flex-col gap-5">
            {SECTIONS.map((s) => (
              <section key={s.id} className="flex flex-col gap-2">
                <h3 className="font-sans text-sm font-semibold text-zinc-800">{tr(language, s.label)}</h3>
                <Panel section={s.id} language={language} options={options} filters={filters} now={now} onChange={onChange} />
              </section>
            ))}
            <section className="flex flex-col gap-2">
              <h3 className="font-sans text-sm font-semibold text-zinc-800">{tr(language, 'lfSortBy')}</h3>
              <SortControl language={language} value={filters.sort} asc={filters.asc} onChange={(sort, asc) => onChange({ ...filters, sort, asc })} />
            </section>
            <p className="text-xs text-zinc-500">{tr(language, 'lfActivityHint')}</p>
          </div>
        </div>
        <div className="border-t border-zinc-200 p-3">
          <button type="button" onClick={onClose} className="min-h-11 w-full rounded-lg bg-zinc-900 text-sm font-medium text-white">{tr(language, 'lfDone')}</button>
        </div>
      </div>
    </div>,
    document.body,
  );
}

export default function LeadFilterBar({ language, filters, options, now, onChange }: {
  language: Lang; filters: LeadFilters; options: LeadFilterOptions | null; now: number; onChange: (f: LeadFilters) => void;
}) {
  const [sheet, setSheet] = useState(false);
  const n = activeCount(filters);
  const clear = () => onChange({ ...filters, sources: [], statuses: [], created: null, activity: null });
  return (
    <div className="flex flex-col gap-2" data-testid="lead-filter-bar">
      <div className="flex flex-wrap items-center gap-2 sm:hidden">
        <button type="button" onClick={() => setSheet(true)} aria-haspopup="dialog"
          className={`flex min-h-11 items-center gap-2 rounded-full px-4 py-1 text-sm ring-1 ${n > 0 ? on : off}`}>
          {tr(language, 'lfFilters')}
          {n > 0 && <span className="rounded-full bg-white/20 px-1.5 text-xs tabular-nums">{n}</span>}
        </button>
        <SortControl language={language} value={filters.sort} asc={filters.asc} onChange={(sort, asc) => onChange({ ...filters, sort, asc })} />
      </div>
      <div className="hidden flex-wrap items-center gap-2 sm:flex">
        {SECTIONS.map((s) => <Popover key={s.id} id={s.id} language={language} filters={filters} options={options} now={now} onChange={onChange} />)}
        <span className="mx-1 h-5 w-px bg-zinc-200" aria-hidden />
        <SortControl language={language} value={filters.sort} asc={filters.asc} onChange={(sort, asc) => onChange({ ...filters, sort, asc })} />
      </div>
      {n > 0 && (
        <div className="flex flex-wrap items-center gap-2" data-testid="lead-filter-chips">
          {filters.sources.map((s) => <Chip key={`s${s}`} label={`${tr(language, 'lfSource')}: ${s}`} language={language} onRemove={() => onChange({ ...filters, sources: filters.sources.filter((x) => x !== s) })} />)}
          {filters.statuses.map((s) => <Chip key={`t${s}`} label={`${tr(language, 'lfStatus')}: ${t(language, `leadStatus${s}` as 'leadStatusNEW')}`} language={language} onRemove={() => onChange({ ...filters, statuses: filters.statuses.filter((x) => x !== s) })} />)}
          {filters.created && <Chip label={`${tr(language, 'lfCreated')}: ${dateLabel(language, filters.created)}`} language={language} onRemove={() => onChange({ ...filters, created: null })} />}
          {filters.activity && <Chip label={`${tr(language, 'lfActivity')}: ${dateLabel(language, filters.activity)}`} language={language} onRemove={() => onChange({ ...filters, activity: null })} />}
          <button type="button" onClick={clear} className="min-h-11 px-2 text-sm text-zinc-600 underline sm:min-h-0">{tr(language, 'lfClearAll')}</button>
        </div>
      )}
      {sheet && <FilterSheet language={language} filters={filters} options={options} now={now} onChange={onChange} onClose={() => setSheet(false)} onClear={clear} />}
    </div>
  );
}

function Chip({ label, language, onRemove }: { label: string; language: Lang; onRemove: () => void }) {
  return (
    <span className="inline-flex max-w-full items-center gap-1 rounded-full bg-zinc-100 py-0.5 pl-3 pr-1 text-sm text-zinc-800 ring-1 ring-zinc-300">
      <span className="min-w-0 break-words">{label}</span>
      <button type="button" onClick={onRemove} aria-label={`${tr(language, 'lfRemove')}: ${label}`}
        className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-zinc-600 hover:bg-zinc-200">
        <span aria-hidden>×</span>
      </button>
    </span>
  );
}
