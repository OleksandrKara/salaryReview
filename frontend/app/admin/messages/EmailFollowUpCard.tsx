'use client';

import { useState } from 'react';
import type { EmailFollowUpDto } from '../../lib/types';

const SKIP_LABELS: Record<string, string> = {
  SKIPPED_CLICKED: 'No email needed — customer clicked the SMS link',
  SKIPPED_REPLIED: 'No email needed — customer replied to the SMS',
  SKIPPED_NO_EMAIL: 'No email on file for this customer',
  SKIPPED_DISABLED: 'Automation was turned off by evening',
  SKIPPED_NOT_CONFIGURED: 'Mailchimp not configured',
  SKIPPED_NO_TEMPLATE: 'No email design for this business yet',
};

function formatTime(iso: string): string {
  return new Date(iso).toLocaleString('en-US', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
}

function EventBadge({ label, at }: { label: string; at: string | null }) {
  if (!at) {
    return <span className="inline-flex items-center rounded-full bg-zinc-100 px-1.5 py-0.5 text-[10px] font-medium text-zinc-500">Not {label.toLowerCase()} yet</span>;
  }
  return (
    <span className="inline-flex items-center gap-1 rounded-full bg-emerald-50 px-1.5 py-0.5 text-[10px] font-medium text-emerald-700">
      <svg width="9" height="9" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
        <path d="M20 6 9 17l-5-5" />
      </svg>
      {label} {formatTime(at)}
    </span>
  );
}

/** Inline annotation under the original SMS bubble it followed up on — completes the story the
 * evening email fallback tells: SMS sent → (customer didn't click/reply) → email sent, opened?,
 * clicked? Collapsed by default (a one-line summary, same spirit as the SMS link-click-status row
 * already on the bubble); expands to a real preview of the exact HTML that was sent, sandboxed
 * (no scripts, no same-origin) since it's raw email HTML from an external source. */
export default function EmailFollowUpCard({ followUp }: { followUp: EmailFollowUpDto }) {
  const [expanded, setExpanded] = useState(false);

  if (followUp.state !== 'SENT') {
    return (
      <div
        data-testid="thread-email-followup-skipped"
        className="mt-1 flex max-w-[75%] items-center gap-1.5 self-end rounded-lg bg-zinc-50 px-2.5 py-1.5 text-[11px] text-zinc-400"
      >
        <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden className="shrink-0">
          <rect x="3" y="5" width="18" height="14" rx="2" />
          <path d="m3 6 9 6 9-6" />
        </svg>
        {SKIP_LABELS[followUp.state] ?? followUp.state}
      </div>
    );
  }

  return (
    <div data-testid="thread-email-followup" className="mt-1 flex w-full max-w-[420px] flex-col self-end overflow-hidden rounded-lg border border-amber-200 bg-amber-50/60">
      <button
        type="button"
        onClick={() => setExpanded((v) => !v)}
        className="flex w-full items-center gap-2 px-2.5 py-1.5 text-left text-[11px] text-amber-900"
      >
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden className="shrink-0">
          <rect x="3" y="5" width="18" height="14" rx="2" />
          <path d="m3 6 9 6 9-6" />
        </svg>
        <span className="font-medium">Follow-up email sent {formatTime(followUp.sentAt)}</span>
        <svg
          width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden
          className={`ml-auto shrink-0 transition-transform ${expanded ? 'rotate-180' : ''}`}
        >
          <path d="m6 9 6 6 6-6" />
        </svg>
      </button>
      <div className="flex flex-wrap gap-1 px-2.5 pb-1.5">
        <EventBadge label="Opened" at={followUp.openedAt} />
        <EventBadge label="Clicked" at={followUp.clickedAt} />
      </div>
      {expanded && followUp.contentHtml && (
        <div className="border-t border-amber-200 bg-white p-1.5">
          <iframe
            data-testid="thread-email-followup-preview"
            srcDoc={followUp.contentHtml}
            sandbox=""
            title={`Email sent ${formatTime(followUp.sentAt)}`}
            className="h-[480px] w-full rounded border border-zinc-100"
          />
        </div>
      )}
    </div>
  );
}
