'use client';

import { useEffect, useRef, useState } from 'react';
import { api } from '../../../lib/api';
import type { SmsMessageDirection, SmsMessageDto } from '../../../lib/types';

const AUTOMATION_LABELS: Record<string, string> = {
  four_hand_request: '4-hand request',
  checkout_review_request: 'Checkout review request',
};

const DIRECTION_OPTIONS: { value: SmsMessageDirection | ''; label: string }[] = [
  { value: '', label: 'All' },
  { value: 'OUTBOUND', label: 'Sent' },
  { value: 'INBOUND', label: 'Received' },
];

function formatPhone(phone: string): string {
  const digits = phone.replace(/\D/g, '');
  if (digits.length === 11 && digits.startsWith('1')) {
    return `(${digits.slice(1, 4)}) ${digits.slice(4, 7)}-${digits.slice(7)}`;
  }
  if (digits.length === 10) {
    return `(${digits.slice(0, 3)}) ${digits.slice(3, 6)}-${digits.slice(6)}`;
  }
  return phone;
}

function formatWhen(iso: string): string {
  return new Date(iso).toLocaleString('en-US', {
    month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit',
  });
}

/** Only meaningful for messages that actually carry a click-tracked short link (see
 * CheckoutReviewReplyService/ShortLinkController) — most messages have none, and render nothing. */
function LinkClickBadge({ message }: { message: SmsMessageDto }) {
  if (!message.linkTarget) {
    return null;
  }
  if (message.clickedAt) {
    return (
      <span
        className="inline-flex items-center gap-1 rounded-full bg-emerald-50 px-2 py-0.5 text-[10px] font-medium text-emerald-700"
        title={new Date(message.clickedAt).toLocaleString()}
      >
        <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
          <path d="M20 6 9 17l-5-5" />
        </svg>
        Clicked {formatWhen(message.clickedAt)}
      </span>
    );
  }
  return (
    <span className="inline-flex items-center rounded-full bg-zinc-100 px-2 py-0.5 text-[10px] font-medium text-zinc-500">
      Link not clicked
    </span>
  );
}

function StatusPill({ message }: { message: SmsMessageDto }) {
  if (message.direction === 'INBOUND') {
    return (
      <span className="inline-flex items-center rounded-full bg-sky-50 px-2 py-0.5 text-[10px] font-medium text-sky-700">
        Received
      </span>
    );
  }
  if (message.status === 'SENT') {
    return (
      <span className="inline-flex items-center rounded-full bg-emerald-50 px-2 py-0.5 text-[10px] font-medium text-emerald-700">
        Sent
      </span>
    );
  }
  return (
    <span
      className="inline-flex items-center rounded-full bg-amber-50 px-2 py-0.5 text-[10px] font-medium text-amber-700"
      title={message.reason ?? undefined}
    >
      Not sent
    </span>
  );
}

// Collapsed by default — nothing is fetched at all until the owner actually expands this
// section. The log can run to 100 real message rows/bodies, and this page already loads
// automations, every template variant, and coupon terms up front; shipping the activity log too
// on every single page load was both unnecessary payload and (owner feedback, same as the
// message-wording section above) "too many messages" clutter on first paint.
export default function SmsActivityLog() {
  const [open, setOpen] = useState(false);
  const [messages, setMessages] = useState<SmsMessageDto[] | null>(null);
  const [phoneQuery, setPhoneQuery] = useState('');
  const [direction, setDirection] = useState<SmsMessageDirection | ''>('');
  const [automationKey, setAutomationKey] = useState('');
  const [loading, setLoading] = useState(false);
  const mounted = useRef(false);

  async function fetchMessages() {
    setLoading(true);
    try {
      const data = await api.listSmsActivity({
        phoneNumber: phoneQuery || undefined,
        direction: direction || undefined,
        automationKey: automationKey || undefined,
        limit: 100,
      });
      setMessages(data);
    } finally {
      setLoading(false);
    }
  }

  function toggleOpen() {
    setOpen((prev) => {
      const next = !prev;
      if (next && messages === null) {
        void fetchMessages();
      }
      return next;
    });
  }

  // Debounced re-fetch on any filter change — phone search included, so typing doesn't fire a
  // request per keystroke. Only runs once the section is actually open and has fetched at least
  // once (the toggle above handles that very first fetch itself).
  useEffect(() => {
    if (!mounted.current) {
      mounted.current = true;
      return;
    }
    if (!open || messages === null) {
      return;
    }
    const handle = setTimeout(() => void fetchMessages(), 300);
    return () => clearTimeout(handle);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [phoneQuery, direction, automationKey]);

  async function markRead(id: number) {
    setMessages((prev) => (prev ? prev.map((m) => (m.id === id ? { ...m, readAt: new Date().toISOString() } : m)) : prev));
    try {
      await api.markSmsMessageRead(id);
    } catch {
      // Not worth reverting a read-receipt on a network hiccup — worst case it stays "read"
      // locally until the next full re-fetch, which is harmless.
    }
  }

  return (
    <div className="mt-4 flex flex-col gap-3">
      <button
        type="button"
        onClick={toggleOpen}
        aria-expanded={open}
        className="flex w-full items-center justify-between gap-3 rounded-lg px-4 py-3 text-left ring-1 ring-zinc-200"
      >
        <span className="font-medium text-zinc-900">Activity log</span>
        <span className="flex items-center gap-2 text-xs text-zinc-400">
          {open ? 'Hide' : 'Show'}
          <svg
            width="12"
            height="12"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2.5"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden
            className={`shrink-0 transition-transform ${open ? 'rotate-180' : ''}`}
          >
            <polyline points="6 9 12 15 18 9" />
          </svg>
        </span>
      </button>
      {open && (
      <div className="flex flex-col gap-3">
      <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
        <input
          value={phoneQuery}
          onChange={(e) => setPhoneQuery(e.target.value)}
          placeholder="Search phone number…"
          className="w-full rounded border border-zinc-300 px-3 py-2 text-sm sm:max-w-[200px]"
        />
        <select
          value={direction}
          onChange={(e) => setDirection(e.target.value as SmsMessageDirection | '')}
          className="w-full rounded border border-zinc-300 px-3 py-2 text-sm sm:w-auto"
        >
          {DIRECTION_OPTIONS.map((o) => (
            <option key={o.value} value={o.value}>{o.label}</option>
          ))}
        </select>
        <select
          value={automationKey}
          onChange={(e) => setAutomationKey(e.target.value)}
          className="w-full rounded border border-zinc-300 px-3 py-2 text-sm sm:w-auto"
        >
          <option value="">All automations</option>
          {Object.entries(AUTOMATION_LABELS).map(([key, label]) => (
            <option key={key} value={key}>{label}</option>
          ))}
        </select>
      </div>

      {!messages || messages.length === 0 ? (
        <div className="rounded-lg border border-dashed border-zinc-300 p-8 text-center text-sm text-zinc-500">
          {loading || !messages ? 'Loading…' : 'No messages match these filters.'}
        </div>
      ) : (
        <>
          {/* Mobile: stacked cards */}
          <div className="flex flex-col gap-2 sm:hidden">
            {messages.map((m) => (
              <MessageCard key={m.id} message={m} onMarkRead={() => markRead(m.id)} />
            ))}
          </div>

          {/* Desktop: table */}
          <div className="hidden overflow-x-auto rounded-lg ring-1 ring-zinc-200 sm:block">
            <table className="w-full text-left text-sm">
              <thead className="bg-zinc-50 text-xs uppercase tracking-wide text-zinc-500">
                <tr>
                  <th className="px-3 py-2 font-medium">When</th>
                  <th className="px-3 py-2 font-medium">Phone</th>
                  <th className="px-3 py-2 font-medium">Automation</th>
                  <th className="px-3 py-2 font-medium">Message</th>
                  <th className="px-3 py-2 font-medium">Status</th>
                  <th className="px-3 py-2 font-medium">Link</th>
                </tr>
              </thead>
              <tbody>
                {messages.map((m) => {
                  const unread = m.direction === 'INBOUND' && !m.readAt;
                  return (
                    <tr
                      key={m.id}
                      onClick={() => unread && markRead(m.id)}
                      className={`border-t border-zinc-100 ${unread ? 'cursor-pointer bg-sky-50/60 hover:bg-sky-50' : ''}`}
                    >
                      <td className="whitespace-nowrap px-3 py-2.5 tabular-nums text-zinc-500">{formatWhen(m.createdAt)}</td>
                      <td className={`whitespace-nowrap px-3 py-2.5 tabular-nums ${unread ? 'font-semibold text-zinc-900' : 'text-zinc-700'}`}>
                        {unread && <span className="mr-1.5 inline-block h-1.5 w-1.5 rounded-full bg-sky-600 align-middle" aria-hidden />}
                        {formatPhone(m.phoneNumber)}
                      </td>
                      <td className="px-3 py-2.5 text-zinc-500">{m.automationKey ? AUTOMATION_LABELS[m.automationKey] ?? m.automationKey : '—'}</td>
                      <td className={`max-w-[360px] truncate px-3 py-2.5 ${unread ? 'font-medium text-zinc-900' : 'text-zinc-600'}`} title={m.body}>
                        {m.body}
                      </td>
                      <td className="px-3 py-2.5"><StatusPill message={m} /></td>
                      <td className="whitespace-nowrap px-3 py-2.5"><LinkClickBadge message={m} /></td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </>
      )}
      </div>
      )}
    </div>
  );
}

function MessageCard({ message, onMarkRead }: { message: SmsMessageDto; onMarkRead: () => void }) {
  const unread = message.direction === 'INBOUND' && !message.readAt;
  return (
    <div
      onClick={() => unread && onMarkRead()}
      className={`rounded-lg p-3 ring-1 ring-zinc-200 ${unread ? 'cursor-pointer bg-sky-50/60' : ''}`}
    >
      <div className="flex items-center justify-between gap-2">
        <span className={`inline-flex items-center gap-1.5 tabular-nums ${unread ? 'font-semibold text-zinc-900' : 'font-medium text-zinc-700'}`}>
          {unread && <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-sky-600" aria-hidden />}
          {formatPhone(message.phoneNumber)}
        </span>
        <span className="shrink-0 text-xs tabular-nums text-zinc-400">{formatWhen(message.createdAt)}</span>
      </div>
      <p className={`mt-1.5 text-sm ${unread ? 'font-medium text-zinc-900' : 'text-zinc-600'}`}>{message.body}</p>
      <div className="mt-2 flex items-center justify-between">
        <span className="text-xs text-zinc-400">
          {message.automationKey ? AUTOMATION_LABELS[message.automationKey] ?? message.automationKey : 'Not automation-linked'}
        </span>
        <div className="flex items-center gap-1.5">
          <LinkClickBadge message={message} />
          <StatusPill message={message} />
        </div>
      </div>
    </div>
  );
}
