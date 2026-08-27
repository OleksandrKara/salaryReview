import type { MailchimpActivityResponse } from '../../../lib/types';

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

function EventBadge({ label, at }: { label: string; at: string | null }) {
  if (!at) {
    return <span className="inline-flex items-center rounded-full bg-zinc-100 px-2 py-0.5 text-[10px] font-medium text-zinc-500">Not yet</span>;
  }
  return (
    <span
      className="inline-flex items-center gap-1 rounded-full bg-emerald-50 px-2 py-0.5 text-[10px] font-medium text-emerald-700"
      title={new Date(at).toLocaleString()}
    >
      <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
        <path d="M20 6 9 17l-5-5" />
      </svg>
      {label} {formatWhen(at)}
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

/** Read-only activity view for the win-back email fallback (see WinbackEmailFallbackScheduler) —
 * which email went to which customer, when, whether they opened/clicked it (from Mailchimp's own
 * per-recipient activity report, synced periodically — see MailchimpActivitySyncScheduler), and
 * whether they actually came back (a real completed visit, not just a click). Server-rendered from
 * a single fetch, same as the rest of this settings page — no client-side polling; the owner
 * refreshes the page to see newer numbers, which is plenty for a once-a-day automation. */
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
        <div className="mt-4 overflow-x-auto rounded-lg ring-1 ring-zinc-200">
          <table className="w-full text-left text-sm">
            <thead className="bg-zinc-50 text-xs uppercase tracking-wide text-zinc-500">
              <tr>
                <th className="px-3 py-2 font-medium">Sent</th>
                <th className="px-3 py-2 font-medium">Email</th>
                <th className="px-3 py-2 font-medium">Automation</th>
                <th className="px-3 py-2 font-medium">Status</th>
                <th className="px-3 py-2 font-medium">Opened</th>
                <th className="px-3 py-2 font-medium">Clicked</th>
                <th className="px-3 py-2 font-medium">Converted</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-zinc-100">
              {sends.map((s) => (
                <tr key={s.id}>
                  <td className="whitespace-nowrap px-3 py-2.5 tabular-nums text-zinc-500">{formatWhen(s.sentAt)}</td>
                  <td className="whitespace-nowrap px-3 py-2.5 text-zinc-700">{s.emailAddress ?? '—'}</td>
                  <td className="px-3 py-2.5 text-zinc-500">{AUTOMATION_LABELS[s.automationKey] ?? s.automationKey}</td>
                  <td className="px-3 py-2.5"><StatePill state={s.state} /></td>
                  <td className="whitespace-nowrap px-3 py-2.5">{s.state === 'SENT' ? <EventBadge label="Opened" at={s.openedAt} /> : '—'}</td>
                  <td className="whitespace-nowrap px-3 py-2.5">{s.state === 'SENT' ? <EventBadge label="Clicked" at={s.clickedAt} /> : '—'}</td>
                  <td className="whitespace-nowrap px-3 py-2.5">
                    {s.state === 'SENT' ? (
                      s.converted ? (
                        <span className="inline-flex items-center rounded-full bg-violet-50 px-2 py-0.5 text-[10px] font-medium text-violet-700">Booked</span>
                      ) : (
                        <span className="inline-flex items-center rounded-full bg-zinc-100 px-2 py-0.5 text-[10px] font-medium text-zinc-500">Not yet</span>
                      )
                    ) : '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
