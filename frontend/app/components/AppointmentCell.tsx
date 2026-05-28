// A date + appointment start time cell. When a booking id is present it links to the appointment in
// the Square dashboard. Presentational (usable in server and client components).
const SQUARE_RESERVATION = 'https://app.squareup.com/dashboard/appointments/calendar/reservations/';

export function AppointmentCell({
  date,
  time,
  bookingId,
}: {
  date: string;
  time: string | null;
  bookingId: string | null;
}) {
  const label = time ? `${date} · ${time}` : date;
  if (!bookingId) return <span className="tabular-nums text-zinc-600">{label}</span>;
  return (
    <a
      href={`${SQUARE_RESERVATION}${bookingId}`}
      target="_blank"
      rel="noopener noreferrer"
      className="tabular-nums text-blue-600 hover:underline"
      title="Open appointment in Square"
    >
      {label}
    </a>
  );
}
