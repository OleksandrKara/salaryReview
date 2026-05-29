'use client';

import { Fragment, useState } from 'react';
import type { AttributedService } from '../lib/types';
import { AppointmentCell } from '../components/AppointmentCell';
import { groupByDay, formatDay } from '../lib/grouping';

const usd = (n: number) =>
  n.toLocaleString('en-US', { style: 'currency', currency: 'USD' });

// Shows, per period, how much in discounts the salon absorbed on this provider's appointments —
// and an expandable list so they can trace each one. Makes it explicit that discounts are fully
// compensated (pay is on the full menu price).
export default function DiscountBreakdown({ services }: { services: AttributedService[] }) {
  const first = services.filter((s) => s.half === 'FIRST' && s.discount > 0);
  const second = services.filter((s) => s.half === 'SECOND' && s.discount > 0);

  return (
    <section className="mt-8">
      <h2 className="mb-1 text-sm font-semibold">Discounts the salon covered</h2>
      <p className="mb-3 text-xs text-zinc-500">
        The salon absorbs 100% of discounts — your pay is calculated on the full menu price, so these
        don&apos;t reduce what you earn. The amounts below were compensated to you.
      </p>
      <div className="flex flex-col gap-3">
        <HalfDiscounts title="1–15" lines={first} />
        <HalfDiscounts title="16–end" lines={second} />
      </div>
    </section>
  );
}

function HalfDiscounts({ title, lines }: { title: string; lines: AttributedService[] }) {
  const [open, setOpen] = useState(false);
  const total = lines.reduce((s, l) => s + l.discount, 0);

  return (
    <div className="rounded-lg ring-1 ring-zinc-200">
      <div className="flex items-center justify-between px-4 py-3">
        <div>
          <span className="text-sm font-medium">{title}</span>
          <span className="ml-3 text-sm text-zinc-600">
            {usd(total)} covered
            <span className="text-zinc-400"> · {lines.length} {lines.length === 1 ? 'discount' : 'discounts'}</span>
          </span>
        </div>
        {lines.length > 0 && (
          <button onClick={() => setOpen((o) => !o)} className="text-xs text-blue-600 hover:underline">
            {open ? 'Hide' : 'Show'} breakdown
          </button>
        )}
      </div>

      {open && lines.length > 0 && (
        <div className="border-t border-zinc-200">
          {/* Phone: stacked appointment cards. */}
          <div className="flex flex-col gap-4 p-3 sm:hidden">
            {groupByDay(lines).map((day) => (
              <div key={day.date}>
                <div className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-zinc-500">{formatDay(day.date)}</div>
                <div className="flex flex-col gap-2">
                  {day.appointments.map((g) => (
                    <div key={g.key} className="overflow-hidden rounded-lg ring-1 ring-zinc-200">
                      <div className="flex flex-wrap items-baseline gap-x-1.5 bg-zinc-50 px-3 py-1.5 text-xs">
                        <span className="font-medium">
                          <AppointmentCell date={g.date} time={g.time} bookingId={g.bookingId} label={g.time ?? 'Appointment'} />
                        </span>
                        {g.customer && <span className="text-zinc-500">· {g.customer}</span>}
                      </div>
                      <div className="divide-y divide-zinc-100">
                        {g.lines.map((l, i) => (
                          <div key={i} className="flex items-start justify-between gap-3 px-3 py-2 text-sm">
                            <div className="min-w-0 break-words">{l.service}</div>
                            <div className="shrink-0 text-right tabular-nums">
                              <div className="text-emerald-700">−{usd(l.discount)}</div>
                              <div className="text-[11px] text-zinc-400">{usd(l.gross)} → {usd(l.net)}</div>
                            </div>
                          </div>
                        ))}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            ))}
            <div className="flex items-baseline justify-between text-xs font-medium">
              <span>Total covered by salon</span>
              <span className="tabular-nums text-emerald-700">−{usd(total)}</span>
            </div>
          </div>

          {/* Tablet / desktop: the full table. */}
          <div className="hidden overflow-x-auto sm:block">
            <table className="w-full text-sm">
              <thead className="bg-zinc-50 text-left text-xs uppercase tracking-wide text-zinc-500">
                <tr>
                  <th className="px-3 py-2">Service</th>
                  <th className="px-3 py-2 text-right">Menu price</th>
                  <th className="px-3 py-2 text-right">Discount</th>
                  <th className="px-3 py-2 text-right">Client paid</th>
                </tr>
              </thead>
              <tbody>
                {groupByDay(lines).map((day) => (
                  <Fragment key={day.date}>
                    <tr className="border-t-2 border-zinc-300 bg-zinc-100">
                      <td colSpan={4} className="px-3 py-1.5 text-xs font-semibold text-zinc-700">{formatDay(day.date)}</td>
                    </tr>
                    {day.appointments.map((g) => (
                      <Fragment key={g.key}>
                        <tr className="border-t border-zinc-200 bg-zinc-50">
                          <td colSpan={4} className="px-3 py-1.5 pl-4 text-xs">
                            <span className="font-medium">
                              <AppointmentCell date={g.date} time={g.time} bookingId={g.bookingId} label={g.time ?? 'Appointment'} />
                            </span>
                            {g.customer && <span className="text-zinc-500"> · {g.customer}</span>}
                          </td>
                        </tr>
                        {g.lines.map((l, i) => (
                          <tr key={i} className="hover:bg-zinc-50">
                            <td className="px-3 py-2 pl-8">{l.service}</td>
                            <td className="px-3 py-2 text-right tabular-nums">{usd(l.gross)}</td>
                            <td className="px-3 py-2 text-right tabular-nums text-emerald-700">−{usd(l.discount)}</td>
                            <td className="px-3 py-2 text-right tabular-nums text-zinc-500">{usd(l.net)}</td>
                          </tr>
                        ))}
                      </Fragment>
                    ))}
                  </Fragment>
                ))}
              </tbody>
              <tfoot className="border-t border-zinc-200 bg-zinc-50 text-xs font-medium">
                <tr>
                  <td className="px-3 py-2" colSpan={2}>Total covered by salon</td>
                  <td className="px-3 py-2 text-right tabular-nums text-emerald-700">−{usd(total)}</td>
                  <td className="px-3 py-2"></td>
                </tr>
              </tfoot>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}
