import type { MarketingContact } from '../../../lib/types';

const usd = (n: number) => n.toLocaleString('en-US', { style: 'currency', currency: 'USD' });

const fmtDate = (iso: string) =>
  new Date(iso).toLocaleString('en-US', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });

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

function AppointmentInfo({ c }: { c: MarketingContact }) {
  if (!c.hasAppointment) {
    return <span className="text-xs text-zinc-400">No appointment yet</span>;
  }
  return (
    <div className="text-sm">
      <div className="font-medium">
        {c.bookingStartAt ? fmtDate(c.bookingStartAt) : '—'}
        {c.bookingArtistName ? ` · ${c.bookingArtistName}` : ''}
      </div>
      <div className="text-xs text-zinc-500">
        {c.bookingServiceName ?? '—'}
        {c.bookingPrice != null ? ` · ${usd(c.bookingPrice)}` : ''}
        {c.bookingStatus ? ` · ${c.bookingStatus}` : ''}
      </div>
      {c.squareProfileUrl && (
        <a href={c.squareProfileUrl} target="_blank" rel="noopener noreferrer" className="text-xs font-medium text-blue-600 hover:underline">
          View in Square →
        </a>
      )}
    </div>
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

export default function ContactsTable({ contacts }: { contacts: MarketingContact[] }) {
  return (
    <>
      {/* Mobile cards */}
      <div className="flex flex-col gap-3 sm:hidden">
        {contacts.map((c) => (
          <div key={c.id} className="rounded-lg p-4 ring-1 ring-zinc-200">
            <div className="flex items-center justify-between gap-2">
              <span className="font-medium">{c.givenName ?? '—'}</span>
              <span className={`text-xs font-medium ${c.hasAppointment ? 'text-emerald-700' : 'text-zinc-400'}`}>
                {c.hasAppointment ? 'Booked' : 'Lead only'}
              </span>
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
            <div className="mt-3 border-t border-zinc-100 pt-3">
              <AppointmentInfo c={c} />
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
              <th className="px-3 py-2">Appointment</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-zinc-100">
            {contacts.map((c) => (
              <tr key={c.id} className="hover:bg-zinc-50">
                <td className="px-3 py-2">
                  <div className="font-medium">{c.givenName ?? '—'}</div>
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
                  <AppointmentInfo c={c} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}
