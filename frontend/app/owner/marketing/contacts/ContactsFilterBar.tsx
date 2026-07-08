'use client';

import { useMemo, useState } from 'react';
import type { MarketingContact } from '../../../lib/types';
import ContactsTable from './ContactsTable';

const SUBMISSION_TYPE_LABELS: Record<string, string> = {
  step1: 'Lead capture',
  booking: 'Booking',
  four_hand_request: '4-hand request',
};

const ALL = '__all__';

type TrafficMode = 'ads' | 'all';

interface Filters {
  search: string;
  trafficMode: TrafficMode;
  trafficSource: string;
  utmSource: string;
  utmMedium: string;
  utmCampaign: string;
  landingPage: string;
  variant: string;
  submissionTypes: Set<string>;
  createdFrom: string;
  createdTo: string;
  modifiedFrom: string;
  modifiedTo: string;
}

const EMPTY_FILTERS: Filters = {
  search: '',
  trafficMode: 'ads',
  trafficSource: ALL,
  utmSource: ALL,
  utmMedium: ALL,
  utmCampaign: ALL,
  landingPage: ALL,
  variant: ALL,
  submissionTypes: new Set(),
  createdFrom: '',
  createdTo: '',
  modifiedFrom: '',
  modifiedTo: '',
};

/** Matches the backend's own "Meta Ads"/"Google Ads" prefix convention
 * (MarketingContactsRepository's findAdsAttributedContacts) — a paid click's traffic-source label
 * always starts with one of these two, everything else (organic, direct, referral) doesn't. */
function isAdsSource(value: string | null): boolean {
  return value != null && (value.startsWith('Meta Ads') || value.startsWith('Google Ads'));
}

/** "Ads" (the default) mirrors what mani's dashboard has always effectively shown, since mani only
 * runs paid traffic — "All traffic" additionally surfaces organic/direct contacts, which mostly
 * matter for pages like the homepage that aren't ad-funded. */
function matchesTrafficMode(c: MarketingContact, mode: TrafficMode): boolean {
  if (mode === 'all') return true;
  if (isAdsSource(c.originalTrafficSource) || isAdsSource(c.marketingTrafficSource)) return true;
  return c.submissions.some((s) => isAdsSource(s.trafficSource));
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

function applyFilters(contacts: MarketingContact[], f: Filters): MarketingContact[] {
  const search = f.search.trim().toLowerCase();
  return contacts.filter((c) => {
    if (search) {
      const haystack = `${c.givenName ?? ''} ${c.phoneNumber} ${c.emailAddress ?? ''}`.toLowerCase();
      if (!haystack.includes(search)) return false;
    }
    if (!matchesTrafficMode(c, f.trafficMode)) return false;
    if (!matchesTrafficSource(c, f.trafficSource)) return false;
    if (!matchesField(c, f.utmSource, (x) => x.utmSource, (s) => s.utmSource)) return false;
    if (!matchesField(c, f.utmMedium, (x) => x.utmMedium, (s) => s.utmMedium)) return false;
    if (!matchesField(c, f.utmCampaign, (x) => x.utmCampaign, (s) => s.utmCampaign)) return false;
    if (!matchesField(c, f.landingPage, (x) => x.landingPageSlug, (s) => s.landingPageSlug)) return false;
    if (!matchesField(c, f.variant, (x) => x.variantName, (s) => s.variantName)) return false;
    if (f.submissionTypes.size > 0 && !c.submissions.some((s) => f.submissionTypes.has(s.submissionType))) return false;
    if ((f.createdFrom || f.createdTo) && !withinRange(c.createdAt, f.createdFrom, f.createdTo)) return false;
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
  contacts, initialLandingPage,
}: { contacts: MarketingContact[]; initialLandingPage?: string }) {
  // Pre-populates the "Landing page" facet from the shared page selector's ?slug= when explicitly
  // present (e.g. arriving from "Home Page") — left at "All" (today's default) otherwise, since an
  // absent slug is indistinguishable from "the default page was never touched" and forcing a
  // specific-page filter in that case would be a real behavior change, not just a convenience.
  const [filters, setFilters] = useState<Filters>(() => ({
    ...EMPTY_FILTERS,
    landingPage: initialLandingPage ?? ALL,
  }));

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

  const filtered = useMemo(() => applyFilters(contacts, filters), [contacts, filters]);

  function toggleSubmissionType(type: string) {
    setFilters((f) => {
      const next = new Set(f.submissionTypes);
      if (next.has(type)) next.delete(type);
      else next.add(type);
      return { ...f, submissionTypes: next };
    });
  }

  const hasActiveFilters =
    filters.search ||
    filters.trafficMode !== 'ads' ||
    filters.trafficSource !== ALL ||
    filters.utmSource !== ALL ||
    filters.utmMedium !== ALL ||
    filters.utmCampaign !== ALL ||
    filters.landingPage !== ALL ||
    filters.variant !== ALL ||
    filters.submissionTypes.size > 0 ||
    filters.createdFrom ||
    filters.createdTo ||
    filters.modifiedFrom ||
    filters.modifiedTo;

  return (
    <div>
      <div className="mb-4 rounded-lg p-4 ring-1 ring-zinc-200">
        <div className="flex items-center justify-between">
          <h3 className="text-xs font-semibold uppercase tracking-wide text-zinc-500">Filters</h3>
          {hasActiveFilters ? (
            <button
              type="button"
              onClick={() => setFilters(EMPTY_FILTERS)}
              className="text-xs font-medium text-blue-600 hover:underline"
            >
              Clear all
            </button>
          ) : null}
        </div>

        <div className="mt-3 flex items-center gap-3">
          <span className="text-xs font-medium text-zinc-500">Traffic</span>
          <div className="inline-flex rounded-lg bg-zinc-100 p-1">
            {(['ads', 'all'] as TrafficMode[]).map((mode) => (
              <button
                key={mode}
                type="button"
                onClick={() => setFilters((f) => ({ ...f, trafficMode: mode }))}
                className={`rounded-md px-3 py-1 text-xs font-medium transition-colors ${
                  filters.trafficMode === mode
                    ? 'bg-white text-zinc-900 shadow-sm ring-1 ring-zinc-200'
                    : 'text-zinc-500 hover:text-zinc-700'
                }`}
              >
                {mode === 'ads' ? 'Ads only' : 'All traffic'}
              </button>
            ))}
          </div>
        </div>

        <div className="mt-3">
          <input
            type="text"
            value={filters.search}
            onChange={(e) => setFilters((f) => ({ ...f, search: e.target.value }))}
            placeholder="Search name, phone, or email…"
            className="w-full rounded border border-zinc-300 px-3 py-1.5 text-sm"
          />
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
            <span className="font-medium text-zinc-500">Created from</span>
            <input type="date" value={filters.createdFrom} onChange={(e) => setFilters((f) => ({ ...f, createdFrom: e.target.value }))} className="rounded border border-zinc-300 px-2 py-1.5 text-xs" />
          </label>
          <label className="flex flex-col gap-1 text-xs">
            <span className="font-medium text-zinc-500">Created to</span>
            <input type="date" value={filters.createdTo} onChange={(e) => setFilters((f) => ({ ...f, createdTo: e.target.value }))} className="rounded border border-zinc-300 px-2 py-1.5 text-xs" />
          </label>
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
