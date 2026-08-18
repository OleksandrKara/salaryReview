'use client';

import { useMemo, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import type { MarketingContact, TrafficSourceKey } from '../../../lib/types';
import TrafficSourceFilter, { ADS_ONLY_SOURCES, ALL_TRAFFIC_SOURCES } from '../TrafficSourceFilter';
import PeriodFilter from '../PeriodFilter';
import { parsePeriodParams, periodToBounds } from '../period';
import type { PeriodSelection } from '../period';
import ContactsTable from './ContactsTable';

const SUBMISSION_TYPE_LABELS: Record<string, string> = {
  step1: 'Lead capture',
  booking: 'Booking',
  four_hand_request: '4-hand request',
};

const ALL = '__all__';

interface Filters {
  search: string;
  sources: Set<TrafficSourceKey>;
  trafficSource: string;
  utmSource: string;
  utmMedium: string;
  utmCampaign: string;
  landingPage: string;
  variant: string;
  submissionTypes: Set<string>;
  /** The shared marketing period filter (see PeriodFilter/../period), applied to createdAt — the
   * natural "when was this lead captured" mapping to a period, same concept every other tab's
   * period filter narrows by. modifiedFrom/modifiedTo below are a separate, second date facet,
   * unrelated to this shared control. */
  period: PeriodSelection;
  modifiedFrom: string;
  modifiedTo: string;
  /** Narrows to contacts flagged VIP (see MarketingContact#vip) — off by default, since most
   * days the owner wants the whole list, not just the repeat-customer subset. */
  vipOnly: boolean;
}

function emptyFilters(): Filters {
  return {
    search: '',
    sources: new Set(ADS_ONLY_SOURCES),
    trafficSource: ALL,
    utmSource: ALL,
    utmMedium: ALL,
    utmCampaign: ALL,
    landingPage: ALL,
    variant: ALL,
    submissionTypes: new Set(),
    period: { period: 'mtd' },
    modifiedFrom: '',
    modifiedTo: '',
    vipOnly: false,
  };
}

/** "Ads only" (the default) mirrors what mani's dashboard has always effectively shown, since mani
 * only runs paid traffic — selecting every bucket ("All traffic") additionally surfaces organic/
 * direct contacts, which mostly matter for pages like the homepage that aren't ad-funded. Channel
 * is computed server-side (see MarketingContactDto.Contact#channel) — more reliable than the raw
 * originalTrafficSource/marketingTrafficSource labels, which can mislabel an organic Instagram
 * bio-link click as "Meta Ads". */
function matchesSources(c: MarketingContact, sources: Set<TrafficSourceKey>): boolean {
  if (sources.size === ALL_TRAFFIC_SOURCES.length) return true;
  return c.channel !== null && sources.has(c.channel);
}

/** Distinct, sorted, non-empty values pulled from both the contact's own field and every one
 * of their submissions' matching field — so a facet like "Instagram (organic)" is selectable
 * even if it only ever showed up on an intermediate submission, not the contact's current state.
 */
function distinctValues(
  contacts: MarketingContact[],
  contactField: (c: MarketingContact) => string | null,
  submissionField: (s: MarketingContact['submissions'][number]) => string | null
): string[] {
  const values = new Set<string>();
  for (const c of contacts) {
    const v = contactField(c);
    if (v) values.add(v);
    for (const s of c.submissions) {
      const sv = submissionField(s);
      if (sv) values.add(sv);
    }
  }
  return Array.from(values).sort((a, b) => a.localeCompare(b));
}

function matchesField(c: MarketingContact, value: string, contactField: (c: MarketingContact) => string | null, submissionField: (s: MarketingContact['submissions'][number]) => string | null): boolean {
  if (value === ALL) return true;
  if (contactField(c) === value) return true;
  return c.submissions.some((s) => submissionField(s) === value);
}

/** Traffic source matches on either the contact's first-touch or latest-touch label, or any
 * historical submission's label — "show me everyone connected to X", regardless of when. */
function matchesTrafficSource(c: MarketingContact, value: string): boolean {
  if (value === ALL) return true;
  if (c.originalTrafficSource === value || c.marketingTrafficSource === value) return true;
  return c.submissions.some((s) => s.trafficSource === value);
}

function withinRange(iso: string, from: string, to: string): boolean {
  const t = new Date(iso).getTime();
  if (from && t < new Date(from).getTime()) return false;
  if (to && t > new Date(to + 'T23:59:59').getTime()) return false;
  return true;
}

function applyFilters(contacts: MarketingContact[], f: Filters, timeZone: string | undefined): MarketingContact[] {
  const search = f.search.trim().toLowerCase();
  return contacts.filter((c) => {
    if (search) {
      const haystack = `${c.givenName ?? ''} ${c.phoneNumber} ${c.emailAddress ?? ''}`.toLowerCase();
      if (!haystack.includes(search)) return false;
    }
    if (!matchesSources(c, f.sources)) return false;
    if (!matchesTrafficSource(c, f.trafficSource)) return false;
    if (!matchesField(c, f.utmSource, (x) => x.utmSource, (s) => s.utmSource)) return false;
    if (!matchesField(c, f.utmMedium, (x) => x.utmMedium, (s) => s.utmMedium)) return false;
    if (!matchesField(c, f.utmCampaign, (x) => x.utmCampaign, (s) => s.utmCampaign)) return false;
    if (!matchesField(c, f.landingPage, (x) => x.landingPageSlug, (s) => s.landingPageSlug)) return false;
    if (!matchesField(c, f.variant, (x) => x.variantName, (s) => s.variantName)) return false;
    if (f.submissionTypes.size > 0 && !c.submissions.some((s) => f.submissionTypes.has(s.submissionType))) return false;
    if (f.vipOnly && !c.vip) return false;
    const { from: periodFrom, to: periodTo } = periodToBounds(f.period, timeZone);
    if ((periodFrom || periodTo) && !withinRange(c.createdAt, periodFrom ?? '', periodTo ?? '')) return false;
    if ((f.modifiedFrom || f.modifiedTo) && !withinRange(c.updatedAt, f.modifiedFrom, f.modifiedTo)) return false;
    return true;
  });
}

function Select({ label, value, options, onChange }: { label: string; value: string; options: string[]; onChange: (v: string) => void }) {
  return (
    <label className="flex flex-col gap-1 text-xs">
      <span className="font-medium text-zinc-500">{label}</span>
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="rounded border border-zinc-300 px-2 py-1.5 text-xs"
      >
        <option value={ALL}>All</option>
        {options.map((o) => (
          <option key={o} value={o}>
            {o}
          </option>
        ))}
      </select>
    </label>
  );
}

export default function ContactsFilterBar({
  contacts: initialContacts, initialLandingPage, timeZone,
}: {
  contacts: MarketingContact[];
  initialLandingPage?: string;
  // Phase 6.3: the business's real configured timezone — see ../period's own doc.
  timeZone?: string;
}) {
  const searchParams = useSearchParams();
  // Pre-populates the "Landing page" facet from the shared page selector's ?slug= when explicitly
  // present (e.g. arriving from "Home Page") — left at "All" (today's default) otherwise, since an
  // absent slug is indistinguishable from "the default page was never touched" and forcing a
  // specific-page filter in that case would be a real behavior change, not just a convenience.
  // period is likewise seeded from ?period=&from=&to= (see ../period) so arriving from another
  // tab, or reloading, keeps the same period selected — everything here is filtered client-side
  // over the already-fetched contact list, so unlike the other tabs there's no server refetch
  // involved, just a different initial predicate.
  const [filters, setFilters] = useState<Filters>(() => ({
    ...emptyFilters(),
    landingPage: initialLandingPage ?? ALL,
    period: parsePeriodParams(searchParams),
  }));
  const [contacts, setContacts] = useState(initialContacts);
  // "Sync appointments" now lives in MarketingTabs' shared header (visible on every marketing tab,
  // not just this one) — it triggers a router.refresh() rather than updating this component
  // directly, so this adjusts local state from the fresh server-fetched prop that produces
  // instead. Doing this during render (not inside an effect) is React's own documented pattern
  // for "reset state when a prop changes".
  const [prevInitialContacts, setPrevInitialContacts] = useState(initialContacts);
  if (initialContacts !== prevInitialContacts) {
    setPrevInitialContacts(initialContacts);
    setContacts(initialContacts);
  }

  const trafficSources = useMemo(() => {
    const values = new Set<string>();
    for (const c of contacts) {
      if (c.originalTrafficSource) values.add(c.originalTrafficSource);
      if (c.marketingTrafficSource) values.add(c.marketingTrafficSource);
      for (const s of c.submissions) if (s.trafficSource) values.add(s.trafficSource);
    }
    return Array.from(values).sort((a, b) => a.localeCompare(b));
  }, [contacts]);
  const utmSources = useMemo(() => distinctValues(contacts, (c) => c.utmSource, (s) => s.utmSource), [contacts]);
  const utmMediums = useMemo(() => distinctValues(contacts, (c) => c.utmMedium, (s) => s.utmMedium), [contacts]);
  const utmCampaigns = useMemo(() => distinctValues(contacts, (c) => c.utmCampaign, (s) => s.utmCampaign), [contacts]);
  const landingPages = useMemo(() => distinctValues(contacts, (c) => c.landingPageSlug, (s) => s.landingPageSlug), [contacts]);
  const variants = useMemo(() => distinctValues(contacts, (c) => c.variantName, (s) => s.variantName), [contacts]);

  const filtered = useMemo(() => applyFilters(contacts, filters, timeZone), [contacts, filters, timeZone]);

  function toggleSubmissionType(type: string) {
    setFilters((f) => {
      const next = new Set(f.submissionTypes);
      if (next.has(type)) next.delete(type);
      else next.add(type);
      return { ...f, submissionTypes: next };
    });
  }

  const isDefaultSources = filters.sources.size === ADS_ONLY_SOURCES.length && ADS_ONLY_SOURCES.every((s) => filters.sources.has(s));
  const hasActiveFilters =
    filters.search ||
    !isDefaultSources ||
    filters.trafficSource !== ALL ||
    filters.utmSource !== ALL ||
    filters.utmMedium !== ALL ||
    filters.utmCampaign !== ALL ||
    filters.landingPage !== ALL ||
    filters.variant !== ALL ||
    filters.submissionTypes.size > 0 ||
    filters.period.period !== 'mtd' ||
    filters.modifiedFrom ||
    filters.modifiedTo ||
    filters.vipOnly;

  return (
    <div>
      <div className="mb-4 rounded-lg p-4 ring-1 ring-zinc-200">
        <div className="flex items-center justify-between">
          <h3 className="text-xs font-semibold uppercase tracking-wide text-zinc-500">Filters</h3>
          {hasActiveFilters ? (
            <button
              type="button"
              onClick={() => setFilters(emptyFilters())}
              className="text-xs font-medium text-blue-600 hover:underline"
            >
              Clear all
            </button>
          ) : null}
        </div>

        <div className="mt-3">
          <TrafficSourceFilter
            selected={filters.sources}
            onChange={(next) => setFilters((f) => ({ ...f, sources: next }))}
            description="Filters contacts by channel — computed from each contact's own tracking data (see the 'Traffic source' facet below for the raw label)."
          />
        </div>

        <div className="mt-3 flex flex-col gap-2 sm:flex-row sm:items-center">
          <input
            type="text"
            value={filters.search}
            onChange={(e) => setFilters((f) => ({ ...f, search: e.target.value }))}
            placeholder="Search name, phone, or email…"
            className="w-full rounded border border-zinc-300 px-3 py-1.5 text-sm sm:flex-1"
          />
          <label className="flex shrink-0 items-center gap-1.5 text-xs font-medium text-zinc-700">
            <input
              type="checkbox"
              checked={filters.vipOnly}
              onChange={(e) => setFilters((f) => ({ ...f, vipOnly: e.target.checked }))}
            />
            VIP only
          </label>
        </div>

        <div className="mt-3">
          <span className="mb-1 block text-xs font-medium text-zinc-500">Period (created)</span>
          <PeriodFilter value={filters.period} onChange={(next) => setFilters((f) => ({ ...f, period: next }))} timeZone={timeZone} />
        </div>

        <div className="mt-3 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
          <Select label="Traffic source" value={filters.trafficSource} options={trafficSources} onChange={(v) => setFilters((f) => ({ ...f, trafficSource: v }))} />
          <Select label="UTM source" value={filters.utmSource} options={utmSources} onChange={(v) => setFilters((f) => ({ ...f, utmSource: v }))} />
          <Select label="UTM medium" value={filters.utmMedium} options={utmMediums} onChange={(v) => setFilters((f) => ({ ...f, utmMedium: v }))} />
          <Select label="UTM campaign" value={filters.utmCampaign} options={utmCampaigns} onChange={(v) => setFilters((f) => ({ ...f, utmCampaign: v }))} />
          <Select label="Landing page" value={filters.landingPage} options={landingPages} onChange={(v) => setFilters((f) => ({ ...f, landingPage: v }))} />
          <Select label="Variant" value={filters.variant} options={variants} onChange={(v) => setFilters((f) => ({ ...f, variant: v }))} />
        </div>

        <div className="mt-3 flex flex-wrap items-end gap-4">
          <div>
            <span className="mb-1 block text-xs font-medium text-zinc-500">Submission type</span>
            <div className="flex flex-wrap gap-3">
              {Object.entries(SUBMISSION_TYPE_LABELS).map(([type, label]) => (
                <label key={type} className="flex items-center gap-1.5 text-xs text-zinc-700">
                  <input type="checkbox" checked={filters.submissionTypes.has(type)} onChange={() => toggleSubmissionType(type)} />
                  {label}
                </label>
              ))}
            </div>
          </div>

          <label className="flex flex-col gap-1 text-xs">
            <span className="font-medium text-zinc-500">Modified from</span>
            <input type="date" value={filters.modifiedFrom} onChange={(e) => setFilters((f) => ({ ...f, modifiedFrom: e.target.value }))} className="rounded border border-zinc-300 px-2 py-1.5 text-xs" />
          </label>
          <label className="flex flex-col gap-1 text-xs">
            <span className="font-medium text-zinc-500">Modified to</span>
            <input type="date" value={filters.modifiedTo} onChange={(e) => setFilters((f) => ({ ...f, modifiedTo: e.target.value }))} className="rounded border border-zinc-300 px-2 py-1.5 text-xs" />
          </label>
        </div>
      </div>

      <p className="mb-3 text-xs text-zinc-500">
        {filtered.length === contacts.length
          ? `${contacts.length} contact${contacts.length === 1 ? '' : 's'}`
          : `${filtered.length} of ${contacts.length} contacts match your filters`}
      </p>

      {filtered.length === 0 ? (
        <div className="rounded-lg border border-dashed border-zinc-300 p-8 text-center text-sm text-zinc-500">
          No contacts match the current filters.
        </div>
      ) : (
        <ContactsTable contacts={filtered} />
      )}
    </div>
  );
}
