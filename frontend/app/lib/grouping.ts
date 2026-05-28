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

export interface DayGroup {
  date: string;
  appointments: AppointmentGroup[];
}

// Two-level grouping for readability: day → appointments (→ services). Order is preserved (the
// backend already sorts lines oldest-first by date and time).
export function groupByDay(lines: AttributedService[]): DayGroup[] {
  const days = new Map<string, DayGroup>();
  for (const appt of groupByAppointment(lines)) {
    let d = days.get(appt.date);
    if (!d) {
      d = { date: appt.date, appointments: [] };
      days.set(appt.date, d);
    }
    d.appointments.push(appt);
  }
  return [...days.values()];
}

// "2026-05-01" → "Thu, May 1" (parsed as a plain calendar date, no timezone shift).
export function formatDay(dateStr: string): string {
  const [y, m, d] = dateStr.split('-').map(Number);
  if (!y || !m || !d) return dateStr;
  return new Date(y, m - 1, d).toLocaleDateString('en-US', {
    weekday: 'short',
    month: 'short',
    day: 'numeric',
  });
}
