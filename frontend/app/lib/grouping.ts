import type { AttributedService } from './types';

export interface AppointmentGroup {
  key: string;
  bookingId: string | null;
  date: string;
  time: string | null;
  customer: string | null;
  lines: AttributedService[];
}

// Group attributed service lines by appointment (Square booking). Lines sharing a bookingId belong
// to the same visit; falls back to a per-line key when there's no booking. Groups keep first-seen
// order (the backend already sorts lines by date).
export function groupByAppointment(lines: AttributedService[]): AppointmentGroup[] {
  const groups = new Map<string, AppointmentGroup>();
  lines.forEach((l, i) => {
    const key = l.bookingId ?? `nobooking-${l.date}-${l.customer ?? ''}-${i}`;
    let g = groups.get(key);
    if (!g) {
      g = { key, bookingId: l.bookingId, date: l.date, time: l.time, customer: l.customer, lines: [] };
      groups.set(key, g);
    }
    g.lines.push(l);
  });
  return [...groups.values()];
}
