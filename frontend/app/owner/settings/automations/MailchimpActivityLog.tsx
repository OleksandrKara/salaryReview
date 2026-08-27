import type { MailchimpActivitySendView, MailchimpActivityResponse } from '../../../lib/types';

const AUTOMATION_LABELS: Record<string, string> = {
  lapsed_customer_winback: 'Lapsed customer win-back',
  repeat_customer_winback: 'Repeat customer win-back',
};

const STATE_LABELS: Record<string, string> = {
  SENT: 'Sent',
  SKIPPED_CLICKED: 'Skipped — clicked SMS link',
  SKIPPED_REPLIED: 'Skipped — replied to SMS',
  SKIPPED_NO_EMAIL: 'Skipped — no email on file',
  SKIPPED_DISABLED: 'Skipped — automation off',
  SKIPPED_NOT_CONFIGURED: 'Skipped — Mailchimp not configured',
  SKIPPED_NO_TEMPLATE: 'Skipped — no email design yet',
  SEND_FAILED: 'Send failed',
};

function formatWhen(iso: string): string {
  return new Date(iso).toLocaleString('en-US', {
    month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit',
  });
}

function formatPct(rate: number): string {
  return `${Math.round(rate * 1000) / 10}%`;
}

/** Compact form (no timestamp) — used where a full "Opened Aug 27, 10:20 PM" badge would be too
 * wide (the desktop table's combined Engagement column, and the mobile card's inline row); the
 * full timestamp is still available via the title tooltip on desktop / not lost, just not printed
 * inline where space is tightest. */
function EventDot({ label, at }: { label: string; at: string | null }) {
  return (
    <span
      className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[10px] font-medium ${
        at ? 'bg-emerald-50 text-emerald-700' : 'bg-zinc-100 text-zinc-400'
      }`}
      title={at ? `${label} ${new Date(at).toLocaleString()}` : `Not ${label.toLowerCase()} yet`}
    >
      {at && (
        <svg width="9" height="9" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
          <path d="M20 6 9 17l-5-5" />
        </svg>
      )}
      {label}
    </span>
  );
}

function StatePill({ state }: { state: string }) {
  if (state === 'SENT') {
    return <span className="inline-flex items-center rounded-full bg-emerald-50 px-2 py-0.5 text-[10px] font-medium text-emerald-700">Sent</span>;
  }
  if (state === 'SEND_FAILED') {
    return <span className="inline-flex items-center rounded-full bg-red-50 px-2 py-0.5 text-[10px] font-medium text-red-700">Send failed</span>;
  }
  return (
    <span className="inline-flex items-center rounded-full bg-zinc-100 px-2 py-0.5 text-[10px] font-medium text-zinc-500">
      {STATE_LABELS[state] ?? state}
    </span>
  );
}

function ConvertedPill({ converted }: { converted: boolean }) {
  return converted ? (
    <span className="inline-flex items-center rounded-full bg-violet-50 px-2 py-0.5 text-[10px] font-medium text-violet-700">Booked</span>
  ) : (
    <span className="inline-flex items-center rounded-full bg-zinc-100 px-2 py-0.5 text-[10px] font-medium text-zinc-500">Not yet</span>
  );
}

function SendCard({ send }: { send: MailchimpActivitySendView }) {
  return (
    <div className="rounded-lg border border-zinc-200 p-3">
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="truncate text-sm font-medium text-zinc-900">{send.emailAddress ?? '—'}</div>
          <div className="text-xs text-zinc-500">{AUTOMATION_LABELS[send.automationKey] ?? send.automationKey}</div>
        </div>
        <div className="shrink-0 text-right text-xs tabular-nums text-zinc-400">{formatWhen(send.sentAt)}</div>
      </div>
      <div className="mt-2 flex flex-wrap items-center gap-1.5">
        <StatePill state={send.state} />
        {send.state === 'SENT' && (
          <>
            <EventDot label="Opened" at={send.openedAt} />
            <EventDot label="Clicked" at={send.clickedAt} />
            <ConvertedPill converted={send.converted} />
          </>
        )}
      </div>
    </div>
  );
}

/** Read-only activity view for the win-back email fallback (see WinbackEmailFallbackScheduler) —
 * which email went to which customer, when, whether they opened/clicked it (from Mailchimp's own
 * per-recipient activity report, synced periodically — see MailchimpActivitySyncScheduler), and
 * whether they actually came back (a real completed visit, not just a click). Server-rendered from
 * a single fetch, same as the rest of this settings page — no client-side polling; the owner
 * refreshes the page to see newer numbers, which is plenty for a once-a-day automation.
 *
 * <p>Mobile: stacked cards (see SendCard) — a 7-column table forced sideways scrolling to read a
 * single row, found live 2026-08-27. Desktop: a table, but Opened/Clicked collapse into one
 * "Engagement" column (two small dots, full timestamp on hover) rather than two separate wide
 * "Opened Aug 27, 10:20 PM"-style badges — same mobile-cards/desktop-table split SmsActivityLog
 * already uses elsewhere on this page. */
export default function MailchimpActivityLog({ data }: { data: MailchimpActivityResponse }) {
  const { sends, stats } = data;

  return (
    <div className="mt-4">
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <div className="rounded-lg ring-1 ring-zinc-200 p-3">
          <div className="text-xs text-zinc-500">Sent (last {stats.windowDays}d)</div>
          <div className="mt-1 text-xl font-semibold text-zinc-900 tabular-nums">{stats.sentCount}</div>
        </div>
        <div className="rounded-lg ring-1 ring-zinc-200 p-3">
          <div className="text-xs text-zinc-500">Opened</div>
          <div className="mt-1 text-xl font-semibold text-zinc-900 tabular-nums">{formatPct(stats.openRate)}</div>
          <div className="text-[11px] text-zinc-400 tabular-nums">{stats.openedCount} of {stats.sentCount}</div>
        </div>
        <div className="rounded-lg ring-1 ring-zinc-200 p-3">
          <div className="text-xs text-zinc-500">Clicked</div>
          <div className="mt-1 text-xl font-semibold text-zinc-900 tabular-nums">{formatPct(stats.clickRate)}</div>
          <div className="text-[11px] text-zinc-400 tabular-nums">{stats.clickedCount} of {stats.sentCount}</div>
        </div>
        <div className="rounded-lg ring-1 ring-zinc-200 p-3">
          <div className="text-xs text-zinc-500">Converted</div>
          <div className="mt-1 text-xl font-semibold text-zinc-900 tabular-nums">{formatPct(stats.conversionRate)}</div>
          <div className="text-[11px] text-zinc-400 tabular-nums">{stats.convertedCount} of {stats.sentCount}</div>
        </div>
      </div>

      {sends.length === 0 ? (
        <p className="mt-4 text-sm text-zinc-400">No win-back email activity in the last {stats.windowDays} days yet.</p>
      ) : (
        <>
          {/* Mobile: stacked cards */}
          <div className="mt-4 flex flex-col gap-2 sm:hidden">
            {sends.map((s) => (
              <SendCard key={s.id} send={s} />
            ))}
          </div>

          {/* Desktop: table */}
          <div className="mt-4 hidden overflow-x-auto rounded-lg ring-1 ring-zinc-200 sm:block">
            <table className="w-full text-left text-sm">
              <thead className="bg-zinc-50 text-xs uppercase tracking-wide text-zinc-500">
                <tr>
                  <th className="px-3 py-2 font-medium">Sent</th>
                  <th className="px-3 py-2 font-medium">Email</th>
                  <th className="px-3 py-2 font-medium">Automation</th>
                  <th className="px-3 py-2 font-medium">Status</th>
                  <th className="px-3 py-2 font-medium">Engagement</th>
                  <th className="px-3 py-2 font-medium">Converted</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-zinc-100">
                {sends.map((s) => (
                  <tr key={s.id}>
                    <td className="whitespace-nowrap px-3 py-2.5 tabular-nums text-zinc-500">{formatWhen(s.sentAt)}</td>
                    <td className="max-w-[220px] truncate px-3 py-2.5 text-zinc-700" title={s.emailAddress ?? undefined}>
                      {s.emailAddress ?? '—'}
                    </td>
                    <td className="px-3 py-2.5 text-zinc-500">{AUTOMATION_LABELS[s.automationKey] ?? s.automationKey}</td>
                    <td className="px-3 py-2.5"><StatePill state={s.state} /></td>
                    <td className="px-3 py-2.5">
                      {s.state === 'SENT' ? (
                        <div className="flex items-center gap-1">
                          <EventDot label="Opened" at={s.openedAt} />
                          <EventDot label="Clicked" at={s.clickedAt} />
                        </div>
                      ) : '—'}
                    </td>
                    <td className="px-3 py-2.5">{s.state === 'SENT' ? <ConvertedPill converted={s.converted} /> : '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  );
}
