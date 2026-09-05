'use client';

import { useState } from 'react';
import type { EmailSendDto } from '../../../lib/types';

const AUTOMATION_LABELS: Record<string, string> = {
  lapsed_customer_winback: 'Lapsed customer win-back',
  repeat_customer_winback: 'Repeat customer win-back',
  same_day_rebooking_discount: 'Same-day rebooking discount',
  checkout_review_request: 'Post-checkout satisfaction',
  color_booster_winback_oneoff: 'Color booster win-back (one-time)',
};

const SKIP_LABELS: Record<string, string> = {
  SKIPPED_CLICKED: 'Not sent — customer clicked the SMS link',
  SKIPPED_REPLIED: 'Not sent — customer replied to the SMS',
  SKIPPED_NO_EMAIL: 'Not sent — no email on file',
  SKIPPED_DISABLED: 'Not sent — automation was off',
  SKIPPED_NOT_CONFIGURED: 'Not sent — Mailchimp not configured',
  SKIPPED_NO_TEMPLATE: 'Not sent — no email design yet',
  SEND_FAILED: 'Send failed',
};

function formatWhen(iso: string): string {
  return new Date(iso).toLocaleString('en-US', {
    month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit',
  });
}

function EventBadge({ label, at }: { label: string; at: string | null }) {
  if (!at) {
    return (
      <span className="inline-flex items-center rounded-full bg-zinc-100 px-2 py-0.5 text-[11px] font-medium text-zinc-400">
        Not {label.toLowerCase()}
      </span>
    );
  }
  return (
    <span className="inline-flex items-center gap-1 rounded-full bg-emerald-50 px-2 py-0.5 text-[11px] font-medium text-emerald-700">
      <svg width="9" height="9" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
        <path d="M20 6 9 17l-5-5" />
      </svg>
      {label} {formatWhen(at)}
    </span>
  );
}

function EmailSendRow({ send }: { send: EmailSendDto }) {
  const [expanded, setExpanded] = useState(false);
  const isSent = send.state === 'SENT';

  return (
    <div className="rounded-lg border border-zinc-200 bg-white">
      <button
        type="button"
        onClick={() => isSent && send.contentHtml && setExpanded((v) => !v)}
        className={`flex w-full flex-col gap-1.5 p-3 text-left sm:flex-row sm:items-center sm:gap-3 ${
          isSent && send.contentHtml ? 'cursor-pointer' : 'cursor-default'
        }`}
      >
        <div className="min-w-0 flex-1">
          <div className="truncate text-sm font-medium text-zinc-900">{send.emailAddress ?? '—'}</div>
          <div className="text-xs text-zinc-500">{AUTOMATION_LABELS[send.automationKey] ?? send.automationKey}</div>
        </div>
        <div className="flex flex-wrap items-center gap-1.5">
          {isSent ? (
            <>
              <EventBadge label="Opened" at={send.openedAt} />
              <EventBadge label="Clicked" at={send.clickedAt} />
            </>
          ) : (
            <span className="inline-flex items-center rounded-full bg-red-50 px-2 py-0.5 text-[11px] font-medium text-red-700">
              {SKIP_LABELS[send.state] ?? send.state}
            </span>
          )}
        </div>
        <div className="shrink-0 text-xs tabular-nums text-zinc-400 sm:w-40 sm:text-right">{formatWhen(send.sentAt)}</div>
        {isSent && send.contentHtml && (
          <svg
            width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden
            className={`hidden shrink-0 text-zinc-400 transition-transform sm:block ${expanded ? 'rotate-180' : ''}`}
          >
            <path d="m6 9 6 6 6-6" />
          </svg>
        )}
      </button>
      {expanded && send.contentHtml && (
        <div className="border-t border-zinc-100 p-2">
          <iframe
            srcDoc={send.contentHtml}
            sandbox=""
            title={`Email sent ${formatWhen(send.sentAt)}`}
            className="h-[480px] w-full rounded border border-zinc-100"
          />
        </div>
      )}
    </div>
  );
}

/** Flat, unbounded history of every email this business has ever sent (owner request 2026-09-05) —
 * deliberately a separate list from the SMS conversation view rather than fake "conversations"
 * synthesized for customers with no phone thread at all (most of a pure-email campaign's
 * recipients), which is what a customer who only ever got an email winback actually is. */
export default function EmailSendsView({ sends }: { sends: EmailSendDto[] }) {
  if (sends.length === 0) {
    return <p className="p-4 text-sm text-zinc-400">No emails sent yet.</p>;
  }
  return (
    <div className="flex flex-col gap-2 overflow-y-auto p-4 sm:p-0 sm:pt-2">
      {sends.map((s) => (
        <EmailSendRow key={s.id} send={s} />
      ))}
    </div>
  );
}
