'use client';

import { Fragment, useState } from 'react';
import type { MarketingContact } from '../../../lib/types';
import { AppointmentHistoryList, HistoryToggle, SubmissionHistoryList } from '../ContactHistory';
import VipBadge from './VipBadge';

function ConsentBadge({ label, value }: { label: string; value: boolean | null }) {
  const text = value === null ? 'Unknown' : value ? 'Yes' : 'No';
  const cls =
    value === true
      ? 'bg-emerald-50 text-emerald-700 ring-emerald-200'
      : value === false
        ? 'bg-zinc-100 text-zinc-500 ring-zinc-200'
        : 'bg-zinc-50 text-zinc-400 ring-zinc-200';
  return (
    <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${cls}`}>
      {label}: {text}
    </span>
  );
}

function SourceInfo({ c }: { c: MarketingContact }) {
  const same = c.originalTrafficSource === c.marketingTrafficSource;
  return (
    <div className="text-xs">
      <div>
        <span className="text-zinc-500">First:</span> {c.originalTrafficSource ?? '—'}
      </div>
      {!same && (
        <div>
          <span className="text-zinc-500">Latest:</span> {c.marketingTrafficSource ?? '—'}
        </div>
      )}
      {(c.landingPageSlug || c.variantName) && (
        <div className="mt-1 text-zinc-400">
          {c.landingPageSlug ?? '—'}
          {c.variantName ? ` · ${c.variantName}` : ''}
        </div>
      )}
    </div>
  );
}

function DeviceInfo({ c }: { c: MarketingContact }) {
  if (!c.deviceType && !c.osName && !c.browserName) {
    return <span className="text-xs text-zinc-400">—</span>;
  }
  return (
    <div className="text-xs text-zinc-600">
      {c.deviceType && <div className="capitalize">{c.deviceType}</div>}
      <div className="text-zinc-400">
        {[c.osName, c.osVersion].filter(Boolean).join(' ')}
        {c.browserName ? ` · ${c.browserName}` : ''}
      </div>
    </div>
  );
}

/** Only rendered when a Square customer is actually known for this contact. */
function SquareProfileLink({ url }: { url: string }) {
  return (
    <a
      href={url}
      target="_blank"
      rel="noopener noreferrer"
      className="whitespace-nowrap text-xs font-medium text-blue-600 hover:underline"
    >
      View in Square →
    </a>
  );
}

function ExpandedSections({ c, showAppointments, showSubmissions }: { c: MarketingContact; showAppointments: boolean; showSubmissions: boolean }) {
  if (!showAppointments && !showSubmissions) return null;
  return (
    <div className="flex flex-col gap-4">
      {showAppointments && (
        <div>
          <h4 className="mb-2 text-xs font-semibold uppercase tracking-wide text-zinc-500">Appointment History</h4>
          <AppointmentHistoryList appointments={c.appointments} />
        </div>
      )}
      {showSubmissions && (
        <div>
          <h4 className="mb-2 text-xs font-semibold uppercase tracking-wide text-zinc-500">Submission History</h4>
          <SubmissionHistoryList submissions={c.submissions} />
        </div>
      )}
    </div>
  );
}

export default function ContactsTable({ contacts }: { contacts: MarketingContact[] }) {
  const [expandedAppointments, setExpandedAppointments] = useState<Set<string>>(new Set());
  const [expandedSubmissions, setExpandedSubmissions] = useState<Set<string>>(new Set());

  function toggle(set: Set<string>, setSet: (s: Set<string>) => void, id: string) {
    const next = new Set(set);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setSet(next);
  }

  return (
    <>
      {/* Mobile cards */}
      <div className="flex flex-col gap-3 sm:hidden">
        {contacts.map((c) => (
          <div key={c.id} className="rounded-lg p-4 ring-1 ring-zinc-200">
            <div className="flex items-center justify-between gap-2">
              <span className="flex items-center gap-1.5">
                <span className="font-medium">{c.givenName ?? '—'}</span>
                {c.vip && <VipBadge visitCount={c.visitCount} />}
              </span>
              {c.squareProfileUrl && <SquareProfileLink url={c.squareProfileUrl} />}
            </div>
            <div className="mt-1 text-sm text-zinc-600">{c.phoneNumber}</div>
            {c.emailAddress && <div className="text-sm text-zinc-600">{c.emailAddress}</div>}
            <div className="mt-2">
              <SourceInfo c={c} />
            </div>
            <div className="mt-2">
              <DeviceInfo c={c} />
            </div>
            <div className="mt-2 flex flex-wrap gap-1.5">
              <ConsentBadge label="SMS" value={c.smsMarketingConsent} />
              <ConsentBadge label="Email" value={c.emailMarketingConsent} />
            </div>
            <div className="mt-3 flex flex-wrap items-center gap-3 border-t border-zinc-100 pt-3">
              <HistoryToggle
                label="Appointments"
                count={c.appointments.length}
                open={expandedAppointments.has(c.id)}
                onClick={() => toggle(expandedAppointments, setExpandedAppointments, c.id)}
              />
              <HistoryToggle
                label="Submissions"
                count={c.submissions.length}
                open={expandedSubmissions.has(c.id)}
                onClick={() => toggle(expandedSubmissions, setExpandedSubmissions, c.id)}
              />
            </div>
            <div className="mt-3">
              <ExpandedSections c={c} showAppointments={expandedAppointments.has(c.id)} showSubmissions={expandedSubmissions.has(c.id)} />
            </div>
          </div>
        ))}
      </div>

      {/* Desktop table */}
      <div className="hidden overflow-x-auto rounded-lg ring-1 ring-zinc-200 sm:block">
        <table className="w-full text-sm">
          <thead className="bg-zinc-50 text-left text-xs uppercase tracking-wide text-zinc-500">
            <tr>
              <th className="px-3 py-2">Contact</th>
              <th className="px-3 py-2">Source</th>
              <th className="px-3 py-2">Device</th>
              <th className="px-3 py-2">Consent</th>
              <th className="px-3 py-2">Appointments</th>
              <th className="px-3 py-2">Submissions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-zinc-100">
            {contacts.map((c) => {
              const showAppointments = expandedAppointments.has(c.id);
              const showSubmissions = expandedSubmissions.has(c.id);
              return (
                <Fragment key={c.id}>
                  <tr className="hover:bg-zinc-50">
                    <td className="px-3 py-2">
                      <div className="flex items-start justify-between gap-2">
                        <div className="flex items-center gap-1.5">
                          <div className="font-medium">{c.givenName ?? '—'}</div>
                          {c.vip && <VipBadge visitCount={c.visitCount} />}
                        </div>
                        {c.squareProfileUrl && <SquareProfileLink url={c.squareProfileUrl} />}
                      </div>
                      <div className="text-xs text-zinc-500">{c.phoneNumber}</div>
                      {c.emailAddress && <div className="text-xs text-zinc-500">{c.emailAddress}</div>}
                    </td>
                    <td className="px-3 py-2">
                      <SourceInfo c={c} />
                    </td>
                    <td className="px-3 py-2">
                      <DeviceInfo c={c} />
                    </td>
                    <td className="px-3 py-2">
                      <div className="flex flex-col gap-1">
                        <ConsentBadge label="SMS" value={c.smsMarketingConsent} />
                        <ConsentBadge label="Email" value={c.emailMarketingConsent} />
                      </div>
                    </td>
                    <td className="px-3 py-2">
                      <HistoryToggle
                        label="Appointments"
                        count={c.appointments.length}
                        open={showAppointments}
                        onClick={() => toggle(expandedAppointments, setExpandedAppointments, c.id)}
                      />
                    </td>
                    <td className="px-3 py-2">
                      <HistoryToggle
                        label="Submissions"
                        count={c.submissions.length}
                        open={showSubmissions}
                        onClick={() => toggle(expandedSubmissions, setExpandedSubmissions, c.id)}
                      />
                    </td>
                  </tr>
                  {(showAppointments || showSubmissions) && (
                    <tr className="bg-zinc-50">
                      <td colSpan={6} className="px-3 py-3">
                        <ExpandedSections c={c} showAppointments={showAppointments} showSubmissions={showSubmissions} />
                      </td>
                    </tr>
                  )}
                </Fragment>
              );
            })}
          </tbody>
        </table>
      </div>
    </>
  );
}
