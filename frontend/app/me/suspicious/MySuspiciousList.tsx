import type { SuspiciousBooking } from '../../lib/types';

// Same URL constant used by AppointmentCell — opens the booking in Square's dashboard.
const SQUARE_RESERVATION = 'https://app.squareup.com/dashboard/appointments/calendar/reservations/';

const usd = (n: number | null) =>
  n == null ? '—' : n.toLocaleString('en-US', { style: 'currency', currency: 'USD' });

/** Render the customer's name as a link to the appointment in Square. */
function CustomerLink({ b }: { b: SuspiciousBooking }) {
  const label = b.customerName ?? '(unnamed)';
  if (!b.bookingId) return <span className="font-medium text-zinc-700">{label}</span>;
  return (
    <a
      href={`${SQUARE_RESERVATION}${b.bookingId}`}
      target="_blank"
      rel="noopener noreferrer"
      title="Open this appointment in Square (then add a 'cashew $nn' note if it was cash)"
      className="font-medium text-blue-600 hover:underline"
    >
      {label}
    </a>
  );
}

/**
 * Read-only provider view. No Clear button — providers can't clear bookings (only owner/manager can).
 * Notes block isn't rendered because by definition this list contains only no-note bookings.
 */
export default function MySuspiciousList({ items }: { items: SuspiciousBooking[] }) {
  if (items.length === 0) {
    return (
      <p data-testid="me-suspicious-empty" className="rounded-lg p-6 text-center text-sm text-zinc-500 ring-1 ring-zinc-200">
        Nothing to review for this period. 🎉
      </p>
    );
  }

  return (
    <ul data-testid="me-suspicious-list" className="divide-y divide-zinc-100 rounded-lg ring-1 ring-zinc-200">
      {items.map((b) => (
        <li
          key={b.bookingId}
          data-testid={`me-suspicious-row-${b.bookingId}`}
          className="px-3 py-2.5 text-sm"
        >
          <div className="font-medium">
            {b.date} · <span className="font-normal text-zinc-500">{b.time}</span>
          </div>
          <div className="text-zinc-500">
            <CustomerLink b={b} /> · {b.serviceName ?? '(service?)'} · {usd(b.gross)}
          </div>
        </li>
      ))}
    </ul>
  );
}
