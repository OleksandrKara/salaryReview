'use client';

import { useEffect, useRef, useState } from 'react';
import { api } from '../../lib/api';
import type { MarketingContact, SmsConversationDto, SmsMessageDto } from '../../lib/types';
import ContactInfoPanel from './ContactInfoPanel';

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

function formatListTime(iso: string): string {
  return new Date(iso).toLocaleString('en-US', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
}

function formatBubbleTime(iso: string): string {
  return new Date(iso).toLocaleString('en-US', { hour: 'numeric', minute: '2-digit' });
}

// Bubbles only ever show a time-of-day (see formatBubbleTime) — a thread spanning more than a day
// would otherwise have no way to tell which day a message landed on. Inserted once per calendar-
// day boundary, same "Today"/"Yesterday" convention as most messaging apps.
function formatDateSeparator(iso: string): string {
  const date = new Date(iso);
  const today = new Date();
  const yesterday = new Date(today);
  yesterday.setDate(today.getDate() - 1);
  if (date.toDateString() === today.toDateString()) return 'Today';
  if (date.toDateString() === yesterday.toDateString()) return 'Yesterday';
  return date.toLocaleDateString('en-US', {
    month: 'long',
    day: 'numeric',
    year: date.getFullYear() === today.getFullYear() ? undefined : 'numeric',
  });
}

function displayName(givenName: string | null | undefined, familyName: string | null | undefined): string | null {
  const parts = [givenName, familyName].filter((p): p is string => Boolean(p && p.trim()));
  return parts.length > 0 ? parts.join(' ') : null;
}

export default function MessagesView({ initialConversations }: { initialConversations: SmsConversationDto[] }) {
  const [conversations, setConversations] = useState(initialConversations);
  const [selectedPhone, setSelectedPhone] = useState<string | null>(null);
  const [thread, setThread] = useState<SmsMessageDto[]>([]);
  const [threadLoading, setThreadLoading] = useState(false);
  // undefined = still loading, null = resolved but no marketing.contacts profile for this number.
  const [contact, setContact] = useState<MarketingContact | null | undefined>(undefined);
  // Mobile only — desktop always shows the panel inline (see the sm:flex override below). Reset
  // closed on every new conversation so it doesn't carry over from whichever contact was open
  // before, matching how the thread itself always opens fresh.
  const [showContactPanel, setShowContactPanel] = useState(false);
  const [draft, setDraft] = useState('');
  const [sending, setSending] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);
  // Read once, inside the debounce effect below, then reset — lets the info-button on a list row
  // open straight into the contact panel on the same tap, without a race against the effect's own
  // showContactPanel reset (which normally closes it on every fresh selectedPhone).
  const pendingShowContactPanelRef = useRef(false);

  useEffect(() => {
    if (!selectedPhone) return;
    let cancelled = false;
    // Deferred via setTimeout (same convention as SmsActivityLog's debounced fetch) so every
    // setState call here happens inside a callback rather than synchronously in the effect body.
    const handle = setTimeout(() => {
      setShowContactPanel(pendingShowContactPanelRef.current);
      pendingShowContactPanelRef.current = false;
      setContact(undefined);
      setThreadLoading(true);
      api.getSmsThread(selectedPhone)
        .then((data) => {
          if (!cancelled) setThread(data);
        })
        .finally(() => {
          if (!cancelled) setThreadLoading(false);
        });
      api.getSmsContact(selectedPhone).then((data) => {
        if (!cancelled) setContact(data);
      });
    }, 0);
    return () => {
      cancelled = true;
      clearTimeout(handle);
    };
  }, [selectedPhone]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ block: 'end' });
  }, [thread]);

  function openThread(phoneNumber: string, openContactPanel = false) {
    pendingShowContactPanelRef.current = openContactPanel;
    setSelectedPhone(phoneNumber);
    // Optimistic — the unread badge for this contact clears the moment they open it, same
    // instant-feedback convention as SmsActivityLog's mark-read-on-click.
    setConversations((prev) => prev.map((c) => (c.phoneNumber === phoneNumber ? { ...c, unreadCount: 0 } : c)));
  }

  async function sendReply() {
    const body = draft.trim();
    if (!body || !selectedPhone || sending) return;
    setSending(true);
    try {
      const result = await api.sendSmsReply(selectedPhone, body);
      if (result.sent) {
        setDraft('');
        const [freshThread, freshConversations] = await Promise.all([
          api.getSmsThread(selectedPhone),
          api.listSmsConversations(),
        ]);
        setThread(freshThread);
        setConversations(freshConversations);
      }
    } finally {
      setSending(false);
    }
  }

  // Already in memory from the conversation list — shown immediately in the thread header while
  // the richer `contact` fetch (full marketing profile) is still loading, so the header never
  // flashes the phone number before the name arrives.
  const selectedConversation = conversations.find((c) => c.phoneNumber === selectedPhone);

  return (
    <div className="flex h-full min-h-0 overflow-hidden sm:h-[70vh] sm:min-h-[420px] sm:rounded-lg sm:ring-1 sm:ring-zinc-200">
      {/* Contact list — full width on mobile until a thread is opened, fixed sidebar on desktop. */}
      <div className={`w-full shrink-0 overflow-y-auto overflow-x-hidden border-r border-zinc-200 sm:block sm:w-72 ${selectedPhone ? 'hidden sm:block' : ''}`}>
        {conversations.length === 0 ? (
          <div className="p-6 text-center text-sm text-zinc-500">No conversations yet.</div>
        ) : (
          conversations.map((c) => {
            const name = displayName(c.givenName, c.familyName);
            return (
              // A <div> with button semantics, not a native <button> — the info button below
              // needs to be a real, independently-clickable <button>, and a <button> can't
              // contain another one (invalid HTML, breaks hydration).
              <div
                key={c.phoneNumber}
                role="button"
                tabIndex={0}
                onClick={() => openThread(c.phoneNumber)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    openThread(c.phoneNumber);
                  }
                }}
                className={`flex w-full cursor-pointer flex-col gap-0.5 border-b border-zinc-100 px-4 py-3 text-left hover:bg-zinc-50 ${
                  c.phoneNumber === selectedPhone ? 'bg-sky-50' : ''
                }`}
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="flex min-w-0 items-center gap-1.5">
                    <span
                      className={`truncate ${c.unreadCount > 0 ? 'font-semibold text-zinc-900' : 'font-medium text-zinc-700'} ${name ? '' : 'tabular-nums'}`}
                    >
                      {name ?? formatPhone(c.phoneNumber)}
                    </span>
                    {c.smsConsent && (
                      <>
                        <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-emerald-500" aria-hidden />
                        <span className="sr-only">SMS marketing consent on file</span>
                      </>
                    )}
                  </span>
                  <span className="flex shrink-0 items-center gap-1.5">
                    <span className="text-xs tabular-nums text-zinc-400">{formatListTime(c.lastMessageAt)}</span>
                    <button
                      type="button"
                      onClick={(e) => {
                        e.stopPropagation();
                        openThread(c.phoneNumber, true);
                      }}
                      aria-label={`Contact info for ${name ?? formatPhone(c.phoneNumber)}`}
                      className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-zinc-400 hover:bg-zinc-200 hover:text-zinc-600"
                    >
                      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
                        <circle cx="12" cy="12" r="10" /><path d="M12 16v-4" /><path d="M12 8h.01" />
                      </svg>
                    </button>
                  </span>
                </div>
                {name && <span className="truncate text-xs tabular-nums text-zinc-400">{formatPhone(c.phoneNumber)}</span>}
                <div className="flex items-center justify-between gap-2">
                  <span className={`truncate text-sm ${c.unreadCount > 0 ? 'font-medium text-zinc-900' : 'text-zinc-500'}`}>
                    {c.lastMessageDirection === 'OUTBOUND' ? 'You: ' : ''}
                    {c.lastMessageBody}
                  </span>
                  {c.unreadCount > 0 && (
                    <span className="flex h-[18px] min-w-[18px] shrink-0 items-center justify-center rounded-full bg-sky-600 px-1 text-[10px] font-semibold leading-none text-white">
                      {c.unreadCount > 99 ? '99+' : c.unreadCount}
                    </span>
                  )}
                </div>
              </div>
            );
          })
        )}
      </div>

      {/* Thread — full width on mobile once opened, fills remaining space on desktop. min-h-0 is
          load-bearing here (and on the message-list div below): a flex item's default min-height
          is `auto`, not 0, so without it this column refuses to shrink below its content's full
          height — the outer container's `overflow-hidden` then just clips whatever doesn't fit
          (messages cut off, composer pushed off-screen) instead of the message list's own
          `overflow-y-auto` scrolling as intended.

          The `thread-open` class carries no styling of its own — it's a pure marker that
          page.tsx's `group-has-[.thread-open]/messages` reads to hide this page's own title bar
          on mobile while a thread is open, so the thread's own back/name/info header is the only
          one on screen (see page.tsx's doc comment). */}
      <div className={`flex min-h-0 min-w-0 flex-1 flex-col ${selectedPhone ? 'thread-open flex' : 'hidden sm:flex'}`}>
        {!selectedPhone ? (
          <div className="flex flex-1 items-center justify-center text-sm text-zinc-400">
            Select a conversation
          </div>
        ) : (
          <>
            <div className="flex items-center gap-2 border-b border-zinc-200 px-4 py-3">
              <button
                type="button"
                onClick={() => setSelectedPhone(null)}
                aria-label="Back to conversations"
                className="-ml-2 flex h-11 w-11 shrink-0 items-center justify-center text-zinc-400 hover:text-zinc-600 sm:hidden"
              >
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
                  <path d="m15 18-6-6 6-6" />
                </svg>
              </button>
              <span className="min-w-0 flex-1 truncate font-medium text-zinc-900">
                {displayName(
                  contact?.givenName ?? selectedConversation?.givenName,
                  contact?.familyName ?? selectedConversation?.familyName,
                ) ?? formatPhone(selectedPhone)}
              </span>
              {/* Desktop always shows the contact panel inline (sm:flex override on the panel
                  itself) — this toggle only matters on mobile, where it opens a full overlay. */}
              <button
                type="button"
                onClick={() => setShowContactPanel((v) => !v)}
                aria-label="Contact info"
                className="flex h-11 w-11 shrink-0 items-center justify-center text-zinc-400 hover:text-zinc-600 sm:hidden"
              >
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
                  <circle cx="12" cy="12" r="10" /><path d="M12 16v-4" /><path d="M12 8h.01" />
                </svg>
              </button>
            </div>

            <div className="min-h-0 flex-1 overflow-y-auto overflow-x-hidden px-4 py-3">
              {threadLoading ? (
                <div className="text-center text-sm text-zinc-400">Loading…</div>
              ) : (
                <div className="flex flex-col gap-2">
                  {thread.map((m, i) => {
                    const prev = thread[i - 1];
                    const showDateSeparator =
                      !prev || new Date(prev.createdAt).toDateString() !== new Date(m.createdAt).toDateString();
                    return (
                      <div key={m.id}>
                        {showDateSeparator && (
                          <div className="my-2 text-center text-xs font-medium text-zinc-400">
                            {formatDateSeparator(m.createdAt)}
                          </div>
                        )}
                        <div className={`flex ${m.direction === 'OUTBOUND' ? 'justify-end' : 'justify-start'}`}>
                          <div
                            className={`max-w-[75%] rounded-2xl px-3 py-2 text-sm ${
                              m.direction === 'OUTBOUND' ? 'bg-sky-600 text-white' : 'bg-zinc-100 text-zinc-900'
                            }`}
                          >
                            <p className="whitespace-pre-wrap break-words">{m.body}</p>
                            <p className={`mt-1 text-[10px] tabular-nums ${m.direction === 'OUTBOUND' ? 'text-sky-100' : 'text-zinc-400'}`}>
                              {formatBubbleTime(m.createdAt)}
                              {m.direction === 'OUTBOUND' && m.status !== 'SENT' ? ' · Not sent' : ''}
                            </p>
                          </div>
                        </div>
                      </div>
                    );
                  })}
                  <div ref={bottomRef} />
                </div>
              )}
            </div>

            <form
              onSubmit={(e) => {
                e.preventDefault();
                void sendReply();
              }}
              className="flex items-center gap-2 border-t border-zinc-200 p-3 pb-[max(0.75rem,env(safe-area-inset-bottom))]"
            >
              {/* text-base (16px), not text-sm, on mobile — a smaller font on a focused input
                  makes iOS Safari auto-zoom the whole page, which is jarring here. */}
              <input
                value={draft}
                onChange={(e) => setDraft(e.target.value)}
                placeholder="Type a reply…"
                className="min-w-0 flex-1 rounded-full border border-zinc-300 px-4 py-2.5 text-base sm:py-2 sm:text-sm"
              />
              <button
                type="submit"
                disabled={!draft.trim() || sending}
                className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-sky-600 text-white disabled:opacity-40 sm:h-auto sm:w-auto sm:px-4 sm:py-2"
                aria-label="Send"
              >
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden className="sm:hidden">
                  <path d="m22 2-7 20-4-9-9-4Z" /><path d="M22 2 11 13" />
                </svg>
                <span className="hidden text-sm font-medium sm:inline">Send</span>
              </button>
            </form>
          </>
        )}
      </div>

      {/* Contact info — mobile: full-screen overlay toggled by the "i" button above; desktop:
          always-visible third column (sm:flex wins over the mobile-only hidden/flex toggle). */}
      {selectedPhone ? (
        <div
          className={`${showContactPanel ? 'flex' : 'hidden'} fixed inset-0 z-20 flex-col bg-white sm:static sm:z-auto sm:flex sm:w-72 sm:shrink-0 sm:border-l sm:border-zinc-200`}
        >
          <ContactInfoPanel
            phoneNumber={selectedPhone}
            contact={contact}
            squareProfileUrl={selectedConversation?.squareProfileUrl ?? null}
            conversationName={displayName(selectedConversation?.givenName, selectedConversation?.familyName)}
            onClose={() => setShowContactPanel(false)}
          />
        </div>
      ) : null}
    </div>
  );
}
