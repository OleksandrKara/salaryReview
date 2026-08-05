'use client';

import { useEffect, useRef, useState, type ReactNode } from 'react';
import { api } from '../../lib/api';
import type { MarketingContact, SmsConversationDto, SmsConversationSearchHitDto, SmsMessageDto } from '../../lib/types';
import ContactInfoPanel from './ContactInfoPanel';
import SmsConsentIcon from './SmsConsentIcon';
import NegativeFeedbackIcon from './NegativeFeedbackIcon';
import VipIcon from './VipIcon';
import BlockedIcon from './BlockedIcon';
import GoogleReviewClickedIcon from './GoogleReviewClickedIcon';
import FeedbackFormClickedIcon from './FeedbackFormClickedIcon';
import ConversationMenu from './ConversationMenu';
import { dispatchSmsUnreadCountChanged } from '../../lib/smsUnreadEvent';

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

/** Human label for a message's tracked link — null for a message with no link at all (most
 * messages), which the caller uses to skip rendering the click-status row. Covers the
 * checkout-review-request automation's two fixed targets and the same-day-rebooking-discount
 * automation's signed promo link (`REBOOK:<epochSeconds>`, see ShortLinkController). */
function linkTargetLabel(linkTarget: string | null): string | null {
  if (linkTarget === 'GOOGLE_REVIEW') return 'Google review link';
  if (linkTarget === 'FEEDBACK_FORM') return 'Feedback form link';
  if (linkTarget?.startsWith('REBOOK:')) return 'Booking link';
  return null;
}

// Twilio's two terminal failure states — the ones worth interrupting the owner over. "queued" /
// "sending" / "sent" are all still in flight or already fine; "delivered" needs no callout.
function deliveryFailed(deliveryStatus: string | null): boolean {
  return deliveryStatus === 'undelivered' || deliveryStatus === 'failed';
}

// Wraps the first case-insensitive occurrence of `query` in `text` with a <mark> — used by the
// conversation list's search box so a match is visually obvious at a glance, not just implied by
// the row being present in a filtered list.
function highlightMatch(text: string, query: string): ReactNode {
  if (!query) return text;
  const idx = text.toLowerCase().indexOf(query.toLowerCase());
  if (idx === -1) return text;
  return (
    <>
      {text.slice(0, idx)}
      <mark className="rounded bg-amber-200 text-inherit">{text.slice(idx, idx + query.length)}</mark>
      {text.slice(idx + query.length)}
    </>
  );
}

export default function MessagesView({
  initialConversations,
  initialSelectedPhone,
}: {
  initialConversations: SmsConversationDto[];
  /** From the page's own `?phone=` query param — deep-links straight into that customer's
   * thread (see page.tsx's doc comment), same as if the manager had tapped that row themselves.
   * Seeded straight into `selectedPhone`'s initial state so the existing selectedPhone effect
   * below (fetch thread/contact, mark read) just runs on mount, no separate code path needed. */
  initialSelectedPhone?: string | null;
}) {
  const [conversations, setConversations] = useState(initialConversations);
  const [selectedPhone, setSelectedPhone] = useState<string | null>(initialSelectedPhone ?? null);
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
  // Search box (conversation list). Name/phone matching is instant and purely client-side, since
  // every conversation is already loaded; `searchHits` is only populated for message-content
  // matches, which need a backend lookup since older messages in a thread aren't loaded until
  // that thread is opened.
  const [query, setQuery] = useState('');
  const [searchHits, setSearchHits] = useState<SmsConversationSearchHitDto[]>([]);
  const bottomRef = useRef<HTMLDivElement>(null);
  // Read once, inside the debounce effect below, then reset — lets the info-button on a list row
  // open straight into the contact panel on the same tap, without a race against the effect's own
  // showContactPanel reset (which normally closes it on every fresh selectedPhone).
  const pendingShowContactPanelRef = useRef(false);
  // Mirrors selectedPhone for the SSE handler below, which is set up once on mount (not re-run on
  // every selection change — reopening the EventSource per click would be wasteful and would drop
  // events during the brief reconnect window) and so can't close over selectedPhone directly.
  const selectedPhoneRef = useRef<string | null>(selectedPhone);

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
      // Persists the read state opening this thread already shows optimistically (see
      // openThread's own local unreadCount reset) — without this, the backend's read_at never
      // actually changes, so the next unread-count poll (MessagesNotifierIcon) sees the thread as
      // still unread and the badge silently comes back. Fire-and-forget: a failure here just means
      // the badge might not clear until the next successful open, not worth surfacing to the user.
      api.markSmsThreadRead(selectedPhone).catch(() => {});
    }, 0);
    return () => {
      cancelled = true;
      clearTimeout(handle);
    };
  }, [selectedPhone]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ block: 'end' });
  }, [thread]);

  // Debounced message-content search — skipped for very short queries (1 character) so we're not
  // firing a broad, mostly-useless lookup on every keystroke. Name/phone filtering (below, in
  // render) doesn't wait on this — it's instant either way.
  useEffect(() => {
    const trimmed = query.trim();
    let cancelled = false;
    const handle = setTimeout(
      () => {
        if (trimmed.length < 2) {
          setSearchHits([]);
          return;
        }
        api.searchSmsConversations(trimmed).then((hits) => {
          if (!cancelled) setSearchHits(hits);
        });
      },
      trimmed.length < 2 ? 0 : 300,
    );
    return () => {
      cancelled = true;
      clearTimeout(handle);
    };
  }, [query]);

  useEffect(() => {
    selectedPhoneRef.current = selectedPhone;
  }, [selectedPhone]);

  // Live updates — an inbound text, a delivery-status change, a read/block toggle from another
  // tab, etc. all land here as a bare "this phone number changed" ping (see backend
  // SmsEventBroadcaster's own doc for why it's not a full payload); refetching the already-loaded
  // conversation list and, if it's the open thread, the thread too, reuses the same well-tested
  // loading logic as every other refresh in this component instead of hand-rolling incremental
  // state merges. SSE over polling: customer texts arrive sporadically, so instant push beats
  // trading off staleness against wasted requests either way; native EventSource also handles
  // reconnect on a dropped connection with zero code here.
  useEffect(() => {
    const source = new EventSource('/api/owner/automations/activity/stream');
    let debounceHandle: ReturnType<typeof setTimeout> | null = null;
    source.addEventListener('update', (e: MessageEvent) => {
      let phoneNumber: string | null = null;
      try {
        phoneNumber = (JSON.parse(e.data) as { phoneNumber?: string }).phoneNumber ?? null;
      } catch {
        // Malformed payload — still worth a full refresh below.
      }
      if (debounceHandle) clearTimeout(debounceHandle);
      // A short debounce coalesces a burst of near-simultaneous events (e.g. an inbound message
      // plus an automated reply) into one refetch instead of several back-to-back ones.
      debounceHandle = setTimeout(() => {
        api.listSmsConversations().then((fresh) => {
          setConversations(fresh);
          dispatchSmsUnreadCountChanged(fresh.reduce((sum, c) => sum + c.unreadCount, 0));
        });
        if (phoneNumber && phoneNumber === selectedPhoneRef.current) {
          api.getSmsThread(phoneNumber).then(setThread);
          // The manager is already looking at this thread — same "already read" convention as
          // opening it fresh (see the selectedPhone effect above).
          api.markSmsThreadRead(phoneNumber).catch(() => {});
        }
      }, 250);
    });
    return () => {
      if (debounceHandle) clearTimeout(debounceHandle);
      source.close();
    };
  }, []);

  function openThread(phoneNumber: string, openContactPanel = false) {
    pendingShowContactPanelRef.current = openContactPanel;
    setSelectedPhone(phoneNumber);
    // Optimistic — the unread badge for this contact clears the moment they open it, same
    // instant-feedback convention as SmsActivityLog's mark-read-on-click.
    const next = conversations.map((c) => (c.phoneNumber === phoneNumber ? { ...c, unreadCount: 0 } : c));
    setConversations(next);
    // Tells the header's MessagesNotifierIcon (a separate component tree — see
    // smsUnreadEvent's own doc comment) about the new total immediately, instead of leaving it
    // stuck showing the old count until its own next poll cycle or a full page refresh.
    dispatchSmsUnreadCountChanged(next.reduce((sum, c) => sum + c.unreadCount, 0));
  }

  // "Mark as unread" — same iMessage/Gmail convention as the backend doc comment describes:
  // marking the *currently open* thread unread also backs out to the conversation list, since
  // leaving it open would otherwise immediately re-mark it read (see the selectedPhone effect
  // above, which calls markSmsThreadRead on every open).
  async function markUnread(phoneNumber: string) {
    const wasSelected = phoneNumber === selectedPhone;
    const next = conversations.map((c) =>
      c.phoneNumber === phoneNumber ? { ...c, unreadCount: Math.max(c.unreadCount, 1) } : c,
    );
    setConversations(next);
    dispatchSmsUnreadCountChanged(next.reduce((sum, c) => sum + c.unreadCount, 0));
    if (wasSelected) setSelectedPhone(null);
    try {
      await api.markSmsThreadUnread(phoneNumber);
    } catch {
      // Best-effort — a failure here just means the badge might not stick until the next full
      // conversations refresh, not worth surfacing to the user.
    }
  }

  async function toggleBlock(phoneNumber: string, currentlyBlocked: boolean) {
    const previous = conversations;
    setConversations(conversations.map((c) => (c.phoneNumber === phoneNumber ? { ...c, blocked: !currentlyBlocked } : c)));
    try {
      if (currentlyBlocked) {
        await api.unblockSmsNumber(phoneNumber);
      } else {
        await api.blockSmsNumber(phoneNumber);
      }
    } catch {
      // Roll back the optimistic flip on failure so the UI doesn't lie about the real state.
      setConversations(previous);
    }
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
        dispatchSmsUnreadCountChanged(freshConversations.reduce((sum, c) => sum + c.unreadCount, 0));
      }
    } finally {
      setSending(false);
    }
  }

  // Already in memory from the conversation list — shown immediately in the thread header while
  // the richer `contact` fetch (full marketing profile) is still loading, so the header never
  // flashes the phone number before the name arrives.
  const selectedConversation = conversations.find((c) => c.phoneNumber === selectedPhone);

  const trimmedQuery = query.trim();
  const lowerQuery = trimmedQuery.toLowerCase();
  const queryDigits = trimmedQuery.replace(/\D/g, '');
  const searchHitByPhone = new Map(searchHits.map((h) => [h.phoneNumber, h]));
  const visibleConversations =
    trimmedQuery === ''
      ? conversations
      : conversations.filter((c) => {
          const name = displayName(c.givenName, c.familyName);
          const nameMatches = name != null && name.toLowerCase().includes(lowerQuery);
          const phoneMatches = queryDigits.length > 0 && c.phoneNumber.replace(/\D/g, '').includes(queryDigits);
          return nameMatches || phoneMatches || searchHitByPhone.has(c.phoneNumber);
        });

  return (
    <div data-testid="messages-view-root" className="flex h-full min-h-0 overflow-hidden sm:h-[70vh] sm:min-h-[420px] sm:rounded-lg sm:ring-1 sm:ring-zinc-200">
      {/* Contact list — full width on mobile until a thread is opened, fixed sidebar on desktop. */}
      <div
        data-testid="conversation-list"
        className={`flex w-full shrink-0 flex-col overflow-hidden border-r border-zinc-200 sm:flex sm:w-72 ${selectedPhone ? 'hidden sm:flex' : ''}`}
      >
        {conversations.length > 0 && (
          // text-base (16px), not text-sm, on mobile — matches the composer input's own note
          // below: a smaller font on a focused input makes iOS Safari auto-zoom the whole page.
          <div className="shrink-0 border-b border-zinc-100 p-2">
            <div className="relative">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-zinc-400">
                <circle cx="11" cy="11" r="8" /><path d="m21 21-4.3-4.3" />
              </svg>
              <input
                data-testid="conversation-search-input"
                type="text"
                inputMode="search"
                autoComplete="off"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="Search name, phone, or message…"
                aria-label="Search conversations"
                className="w-full rounded-full border border-zinc-200 bg-zinc-50 py-2.5 pl-9 pr-9 text-base text-zinc-900 placeholder:text-zinc-400 focus:border-sky-400 focus:bg-white focus:outline-none focus:ring-2 focus:ring-sky-100 sm:py-2 sm:text-sm"
              />
              {query && (
                <button
                  type="button"
                  data-testid="conversation-search-clear-button"
                  onClick={() => setQuery('')}
                  aria-label="Clear search"
                  className="absolute right-1.5 top-1/2 flex h-7 w-7 -translate-y-1/2 items-center justify-center rounded-full text-zinc-400 hover:bg-zinc-200 hover:text-zinc-600"
                >
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
                    <path d="M18 6 6 18" /><path d="m6 6 12 12" />
                  </svg>
                </button>
              )}
            </div>
          </div>
        )}
        <div className="min-h-0 flex-1 overflow-y-auto overflow-x-hidden">
        {conversations.length === 0 ? (
          <div className="p-6 text-center text-sm text-zinc-500">No conversations yet.</div>
        ) : visibleConversations.length === 0 ? (
          <div data-testid="conversation-search-empty" className="p-6 text-center text-sm text-zinc-500">
            No conversations match &ldquo;{trimmedQuery}&rdquo;.
          </div>
        ) : (
          visibleConversations.map((c) => {
            const name = displayName(c.givenName, c.familyName);
            const searchHit = searchHitByPhone.get(c.phoneNumber);
            // Prefer showing *why* this row matched when it's not obvious from the last message
            // itself — e.g. the match is buried a few messages back in the thread's history.
            const lastMessageMatches = trimmedQuery !== '' && c.lastMessageBody.toLowerCase().includes(lowerQuery);
            const showSnippet = searchHit != null && !lastMessageMatches;
            return (
              // A <div> with button semantics, not a native <button> — the info button below
              // needs to be a real, independently-clickable <button>, and a <button> can't
              // contain another one (invalid HTML, breaks hydration).
              <div
                key={c.phoneNumber}
                data-testid="conversation-row"
                data-phone={c.phoneNumber}
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
                      data-testid="conversation-row-name"
                      className={`truncate ${c.unreadCount > 0 ? 'font-semibold text-zinc-900' : 'font-medium text-zinc-700'} ${name ? '' : 'tabular-nums'}`}
                    >
                      {name ? highlightMatch(name, trimmedQuery) : formatPhone(c.phoneNumber)}
                    </span>
                    {c.vip && <span data-testid="conversation-row-vip-icon"><VipIcon visitCount={c.visitCount} /></span>}
                    {c.smsConsent && <span data-testid="conversation-row-consent-icon"><SmsConsentIcon /></span>}
                    {c.hasNegativeFeedback && <span data-testid="conversation-row-negative-feedback-icon"><NegativeFeedbackIcon /></span>}
                    {c.blocked && <span data-testid="conversation-row-blocked-icon"><BlockedIcon /></span>}
                    {c.clickedGoogleReview && <span data-testid="conversation-row-google-review-icon"><GoogleReviewClickedIcon /></span>}
                    {c.clickedFeedbackForm && <span data-testid="conversation-row-feedback-form-icon"><FeedbackFormClickedIcon /></span>}
                  </span>
                  <span className="flex shrink-0 items-center gap-1.5">
                    <span data-testid="conversation-row-time" className="text-xs tabular-nums text-zinc-400">{formatListTime(c.lastMessageAt)}</span>
                    <ConversationMenu
                      phoneNumber={c.phoneNumber}
                      blocked={c.blocked}
                      onMarkUnread={() => void markUnread(c.phoneNumber)}
                      onToggleBlock={() => void toggleBlock(c.phoneNumber, c.blocked)}
                      variant="row"
                    />
                    <button
                      type="button"
                      data-testid="conversation-row-info-button"
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
                {name && (
                  <span data-testid="conversation-row-phone" className="truncate text-xs tabular-nums text-zinc-400">
                    {highlightMatch(formatPhone(c.phoneNumber), trimmedQuery)}
                  </span>
                )}
                <div className="flex min-w-0 items-center justify-between gap-2">
                  {/* min-w-0 here (and w-full on page.tsx's <main>) is what actually lets this
                      truncate: a flex item's default min-width is `auto`, not 0, so without it the
                      preview text's own unbreakable content (e.g. a tracked SMS short link with no
                      spaces) can force this row — and the whole page — wider than the viewport. */}
                  <span
                    data-testid="conversation-row-preview"
                    data-is-snippet={showSnippet}
                    className={`min-w-0 truncate text-sm ${c.unreadCount > 0 ? 'font-medium text-zinc-900' : 'text-zinc-500'}`}
                  >
                    {showSnippet && searchHit ? (
                      <>
                        {searchHit.direction === 'OUTBOUND' ? 'You: ' : ''}
                        {highlightMatch(searchHit.snippet, trimmedQuery)}
                      </>
                    ) : (
                      <>
                        {c.lastMessageDirection === 'OUTBOUND' ? 'You: ' : ''}
                        {highlightMatch(c.lastMessageBody, trimmedQuery)}
                      </>
                    )}
                  </span>
                  <span className="flex shrink-0 items-center gap-1">
                    {c.lastMessageDirection === 'OUTBOUND' && deliveryFailed(c.lastMessageDeliveryStatus) && (
                      <span
                        data-testid="conversation-row-delivery-warning"
                        title={`Last message not delivered${c.lastMessageDeliveryErrorMessage ? ` — ${c.lastMessageDeliveryErrorMessage}` : ''}`}
                        className="flex h-[18px] w-[18px] items-center justify-center rounded-full bg-red-100 text-red-600"
                      >
                        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
                          <path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z" />
                          <line x1="12" x2="12" y1="9" y2="13" /><line x1="12" x2="12.01" y1="17" y2="17" />
                        </svg>
                        <span className="sr-only">Last message not delivered</span>
                      </span>
                    )}
                    {c.unreadCount > 0 && (
                      <span data-testid="conversation-row-unread-badge" className="flex h-[18px] min-w-[18px] items-center justify-center rounded-full bg-sky-600 px-1 text-[10px] font-semibold leading-none text-white">
                        {c.unreadCount > 99 ? '99+' : c.unreadCount}
                      </span>
                    )}
                  </span>
                </div>
              </div>
            );
          })
        )}
        </div>
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
      <div data-testid="thread-column" className={`flex min-h-0 min-w-0 flex-1 flex-col ${selectedPhone ? 'thread-open flex' : 'hidden sm:flex'}`}>
        {!selectedPhone ? (
          <div className="flex flex-1 items-center justify-center text-sm text-zinc-400">
            Select a conversation
          </div>
        ) : (
          <>
            <div data-testid="thread-header" className="flex items-center gap-2 border-b border-zinc-200 px-4 py-3">
              <button
                type="button"
                data-testid="thread-back-button"
                onClick={() => setSelectedPhone(null)}
                aria-label="Back to conversations"
                className="-ml-2 flex h-11 w-11 shrink-0 items-center justify-center text-zinc-400 hover:text-zinc-600 sm:hidden"
              >
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
                  <path d="m15 18-6-6 6-6" />
                </svg>
              </button>
              <span className="flex min-w-0 flex-1 items-center gap-1.5">
                <span data-testid="thread-header-name" className="min-w-0 truncate font-medium text-zinc-900">
                  {displayName(
                    contact?.givenName ?? selectedConversation?.givenName,
                    contact?.familyName ?? selectedConversation?.familyName,
                  ) ?? formatPhone(selectedPhone)}
                </span>
                {selectedConversation?.vip && <span data-testid="thread-header-vip-icon"><VipIcon visitCount={selectedConversation.visitCount} /></span>}
                {selectedConversation?.smsConsent && <span data-testid="thread-header-consent-icon"><SmsConsentIcon /></span>}
                {selectedConversation?.hasNegativeFeedback && <span data-testid="thread-header-negative-feedback-icon"><NegativeFeedbackIcon /></span>}
                {selectedConversation?.blocked && <span data-testid="thread-header-blocked-icon"><BlockedIcon /></span>}
                {selectedConversation?.clickedGoogleReview && <span data-testid="thread-header-google-review-icon"><GoogleReviewClickedIcon /></span>}
                {selectedConversation?.clickedFeedbackForm && <span data-testid="thread-header-feedback-form-icon"><FeedbackFormClickedIcon /></span>}
              </span>
              {/* Visible on both mobile and desktop — unlike the info-panel toggle below (which
                  only matters on mobile, since desktop always shows that panel inline), unread/
                  copy/block have no other entry point on desktop. */}
              <ConversationMenu
                phoneNumber={selectedPhone}
                blocked={selectedConversation?.blocked ?? false}
                onMarkUnread={() => void markUnread(selectedPhone)}
                onToggleBlock={() => void toggleBlock(selectedPhone, selectedConversation?.blocked ?? false)}
                variant="header"
              />
              {/* Desktop always shows the contact panel inline (sm:flex override on the panel
                  itself) — this toggle only matters on mobile, where it opens a full overlay. */}
              <button
                type="button"
                data-testid="thread-header-info-button"
                onClick={() => setShowContactPanel((v) => !v)}
                aria-label="Contact info"
                className="flex h-11 w-11 shrink-0 items-center justify-center text-zinc-400 hover:text-zinc-600 sm:hidden"
              >
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
                  <circle cx="12" cy="12" r="10" /><path d="M12 16v-4" /><path d="M12 8h.01" />
                </svg>
              </button>
            </div>

            <div data-testid="thread-message-list" className="min-h-0 flex-1 overflow-y-auto overflow-x-hidden px-4 py-3">
              {threadLoading ? (
                <div className="text-center text-sm text-zinc-400">Loading…</div>
              ) : (
                <div className="flex flex-col gap-2">
                  {thread.map((m, i) => {
                    const prev = thread[i - 1];
                    const showDateSeparator =
                      !prev || new Date(prev.createdAt).toDateString() !== new Date(m.createdAt).toDateString();
                    return (
                      <div key={m.id} data-testid="thread-message" data-message-id={m.id}>
                        {showDateSeparator && (
                          <div data-testid="thread-date-separator" className="my-2 text-center text-xs font-medium text-zinc-400">
                            {formatDateSeparator(m.createdAt)}
                          </div>
                        )}
                        <div className={`flex ${m.direction === 'OUTBOUND' ? 'justify-end' : 'justify-start'}`}>
                          <div
                            data-testid="thread-message-bubble"
                            data-direction={m.direction}
                            className={`max-w-[75%] rounded-2xl px-3 py-2 text-sm ${
                              m.direction === 'OUTBOUND' ? 'bg-sky-600 text-white' : 'bg-zinc-100 text-zinc-900'
                            }`}
                          >
                            <p className="whitespace-pre-wrap break-words">{m.body}</p>
                            <p className={`mt-1 text-[10px] tabular-nums ${m.direction === 'OUTBOUND' ? 'text-sky-100' : 'text-zinc-400'}`}>
                              {formatBubbleTime(m.createdAt)}
                              {m.direction === 'OUTBOUND' && m.status !== 'SENT' ? ' · Not sent' : ''}
                            </p>
                            {/* Twilio's delivery-status callback — distinct from "Not sent" above,
                                which only means our own send attempt to Twilio failed. This means
                                Twilio accepted it but the carrier/handset never got it. A light-red
                                chip against the blue bubble background so it can't be missed. */}
                            {m.direction === 'OUTBOUND' && deliveryFailed(m.deliveryStatus) ? (
                              <p
                                data-testid="thread-message-delivery-warning"
                                data-delivery-status={m.deliveryStatus}
                                className="mt-1 flex items-start gap-1 rounded-md bg-red-50 px-1.5 py-1 text-[10px] font-medium leading-tight text-red-700"
                              >
                                <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden className="mt-px shrink-0">
                                  <path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z" />
                                  <line x1="12" x2="12" y1="9" y2="13" /><line x1="12" x2="12.01" y1="17" y2="17" />
                                </svg>
                                <span>Not delivered{m.deliveryErrorMessage ? ` — ${m.deliveryErrorMessage}` : ''}</span>
                              </p>
                            ) : null}
                            {/* Click status for the checkout-review automation's tracked links
                                (Google review / feedback form) — most messages have no linkTarget
                                at all and skip this row entirely. */}
                            {linkTargetLabel(m.linkTarget) ? (
                              <p
                                data-testid="thread-message-link-status"
                                data-link-target={m.linkTarget}
                                data-clicked={m.clickedAt != null}
                                className={`mt-1 flex items-center gap-1 text-[10px] ${
                                  m.direction === 'OUTBOUND' ? 'text-sky-100' : 'text-zinc-400'
                                }`}
                              >
                                <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden className="shrink-0">
                                  <path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71" />
                                  <path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71" />
                                </svg>
                                {linkTargetLabel(m.linkTarget)}
                                {m.clickedAt ? ` · Opened ${formatBubbleTime(m.clickedAt)}` : ' · Not opened yet'}
                              </p>
                            ) : null}
                          </div>
                        </div>
                      </div>
                    );
                  })}
                  <div ref={bottomRef} />
                </div>
              )}
            </div>

            {selectedConversation?.blocked ? (
              // The backend silently refuses to send to a blocked number (see TwilioSmsService) —
              // surfacing that here explicitly, with a one-tap way out, beats letting a manager
              // type a reply that quietly never sends.
              <div
                data-testid="thread-composer-blocked-banner"
                className="flex items-center justify-between gap-3 border-t border-zinc-200 bg-red-50 p-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] text-sm text-red-700"
              >
                <span>This number is blocked — messages won&rsquo;t send.</span>
                <button
                  type="button"
                  data-testid="thread-composer-unblock-button"
                  onClick={() => void toggleBlock(selectedPhone, true)}
                  className="shrink-0 rounded-full bg-red-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-red-700"
                >
                  Unblock
                </button>
              </div>
            ) : (
              <form
                data-testid="thread-composer"
                onSubmit={(e) => {
                  e.preventDefault();
                  void sendReply();
                }}
                className="flex items-center gap-2 border-t border-zinc-200 p-3 pb-[max(0.75rem,env(safe-area-inset-bottom))]"
              >
                {/* text-base (16px), not text-sm, on mobile — a smaller font on a focused input
                    makes iOS Safari auto-zoom the whole page, which is jarring here. */}
                <input
                  data-testid="thread-composer-input"
                  value={draft}
                  onChange={(e) => setDraft(e.target.value)}
                  placeholder="Type a reply…"
                  className="min-w-0 flex-1 rounded-full border border-zinc-300 px-4 py-2.5 text-base sm:py-2 sm:text-sm"
                />
                <button
                  type="submit"
                  data-testid="thread-composer-send-button"
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
            )}
          </>
        )}
      </div>

      {/* Contact info — mobile: full-screen overlay toggled by the "i" button above; desktop:
          always-visible third column (sm:flex wins over the mobile-only hidden/flex toggle). */}
      {selectedPhone ? (
        <div
          data-testid="contact-info-panel-wrapper"
          className={`${showContactPanel ? 'flex' : 'hidden'} fixed inset-0 z-20 flex-col bg-white sm:static sm:z-auto sm:flex sm:w-72 sm:shrink-0 sm:border-l sm:border-zinc-200`}
        >
          <ContactInfoPanel
            phoneNumber={selectedPhone}
            contact={contact}
            squareProfileUrl={selectedConversation?.squareProfileUrl ?? null}
            conversationName={displayName(selectedConversation?.givenName, selectedConversation?.familyName)}
            smsConsent={selectedConversation?.smsConsent ?? false}
            hasNegativeFeedback={selectedConversation?.hasNegativeFeedback ?? false}
            vip={selectedConversation?.vip ?? false}
            visitCount={selectedConversation?.visitCount ?? null}
            blocked={selectedConversation?.blocked ?? false}
            clickedGoogleReview={selectedConversation?.clickedGoogleReview ?? false}
            clickedFeedbackForm={selectedConversation?.clickedFeedbackForm ?? false}
            onClose={() => setShowContactPanel(false)}
          />
        </div>
      ) : null}
    </div>
  );
}
