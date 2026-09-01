'use client';

import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';
import { api } from '../../lib/api';
import type { MarketingContact, SmsConversationDto, SmsConversationSearchHitDto, SmsMessageDto } from '../../lib/types';
import { Spinner } from '../../components/Spinner';
import ContactInfoPanel from './ContactInfoPanel';
import SmsConsentIcon from './SmsConsentIcon';
import NegativeFeedbackIcon from './NegativeFeedbackIcon';
import VipIcon from './VipIcon';
import BlockedIcon from './BlockedIcon';
import GoogleReviewClickedIcon from './GoogleReviewClickedIcon';
import FeedbackFormClickedIcon from './FeedbackFormClickedIcon';
import SpamFlagIcon from './SpamFlagIcon';
import ConversationMenu from './ConversationMenu';
import EmojiPicker from './EmojiPicker';
import EmailFollowUpCard from './EmailFollowUpCard';
import { dispatchSmsUnreadCountChanged } from '../../lib/smsUnreadEvent';

// A compact, generally-useful set for the composer's "insert emoji" button — not exhaustive (no
// full category picker), just enough to cover common reply copy without switching keyboards.
const COMPOSER_EMOJIS = [
  '😀', '😂', '🥰', '😊', '😉', '😍', '🙏', '👍', '👎', '💪', '🙌', '👏',
  '❤️', '💛', '💕', '✨', '🎉', '🔥', '💯', '⭐', '✅', '❌', '⏰', '📅',
  '💅', '💇', '💆', '💖', '😅', '😢', '😮', '🤔', '👋', '🙋', '😴', '☕',
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
 * checkout-review-request automation's three fixed targets (Google review, Yelp review — asked
 * only of a customer who already left a Google review, feedback form), the same-day-rebooking-
 * discount automation's signed promo link (`REBOOK:<epochSeconds>`), lapsed-customer-winback's own
 * signed promo link (`WINBACK:<epochSeconds>`), and repeat-customer-winback's plain (no promo, no
 * per-send suffix) `BOOK_NOW` link target — all share the same ShortLinkController click-tracked-
 * redirect mechanism. Each missing branch here has silently hidden the click-status row for that
 * automation's messages before: WINBACK: (fixed 2026-08-07), BOOK_NOW (fixed 2026-08-09),
 * YELP_REVIEW (fixed 2026-09-01) — a new automation adding its own link target needs a branch
 * added here too, or its clicks go invisible in the thread even though ShortLinkController is
 * tracking them correctly. */
function linkTargetLabel(linkTarget: string | null): string | null {
  if (linkTarget === 'GOOGLE_REVIEW') return 'Google review link';
  if (linkTarget === 'YELP_REVIEW') return 'Yelp review link';
  if (linkTarget === 'FEEDBACK_FORM') return 'Feedback form link';
  if (linkTarget === 'BOOK_NOW') return 'Booking link';
  if (linkTarget?.startsWith('REBOOK:') || linkTarget?.startsWith('WINBACK:')) return 'Booking link';
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
  initialNextCursor,
  initialHasMore,
  initialSelectedPhone,
}: {
  /** First page only (default 10) — see page.tsx, which now fetches via
   * serverApi.listSmsConversationsPage instead of the old unbounded listSmsConversations, so
   * opening this page doesn't pay for every conversation the salon has ever had. */
  initialConversations: SmsConversationDto[];
  /** Cursor for the next page (this page's oldest lastMessageAt), or null if this first page was
   * already the whole list — passed straight to api.listSmsConversationsPage by loadMoreConversations. */
  initialNextCursor: string | null;
  /** Hint, not a guarantee (see ConversationPageDto's own doc on the backend) — true only when the
   * first page came back full, so the "load more" sentinel below only renders when there's
   * actually more to fetch. */
  initialHasMore: boolean;
  /** From the page's own `?phone=` query param — deep-links straight into that customer's
   * thread (see page.tsx's doc comment), same as if the manager had tapped that row themselves.
   * Seeded straight into `selectedPhone`'s initial state so the existing selectedPhone effect
   * below (fetch thread/contact, mark read) just runs on mount, no separate code path needed. */
  initialSelectedPhone?: string | null;
}) {
  const [conversations, setConversations] = useState(initialConversations);
  // Lets the search-hit-fetching effect below check what's already loaded without needing
  // `conversations` itself in its dependency array (which would re-run it, and its fetches, on
  // every single upsert — including the ones it just did).
  const conversationsRef = useRef(conversations);
  useEffect(() => { conversationsRef.current = conversations; }, [conversations]);
  // Cursor pagination for the conversation list — see loadMoreConversations/the IntersectionObserver
  // effect below. Mirrored into refs so the observer (set up once on mount) always reads the
  // latest values without needing to be torn down/recreated on every page load, same convention as
  // selectedPhoneRef below.
  const [nextCursor, setNextCursor] = useState(initialNextCursor);
  const [hasMore, setHasMore] = useState(initialHasMore);
  const [loadingMore, setLoadingMore] = useState(false);
  const nextCursorRef = useRef(nextCursor);
  const hasMoreRef = useRef(hasMore);
  const loadingMoreRef = useRef(loadingMore);
  useEffect(() => { nextCursorRef.current = nextCursor; }, [nextCursor]);
  useEffect(() => { hasMoreRef.current = hasMore; }, [hasMore]);
  useEffect(() => { loadingMoreRef.current = loadingMore; }, [loadingMore]);
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
  // AI "Generate" button state — see generateDraft below. draftError is cleared on the next
  // attempt/thread switch, not on a timer, so it stays visible until the manager either retries or
  // moves on.
  const [drafting, setDrafting] = useState(false);
  const [draftError, setDraftError] = useState<string | null>(null);
  // Photos staged for the next send — cleared (and their object URLs revoked) once the send
  // completes or the composer is abandoned for a different thread.
  const [attachedFiles, setAttachedFiles] = useState<File[]>([]);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const draftInputRef = useRef<HTMLTextAreaElement>(null);
  // Search box (conversation list). Name/phone matching is instant and purely client-side, since
  // every conversation is already loaded; `searchHits` is only populated for message-content
  // matches, which need a backend lookup since older messages in a thread aren't loaded until
  // that thread is opened.
  const [query, setQuery] = useState('');
  const [searchHits, setSearchHits] = useState<SmsConversationSearchHitDto[]>([]);
  // Fetched summaries for search hits not already in `conversations` — deliberately kept separate
  // from (never merged into) the permanent `conversations` state. See the search-hit-fetching
  // effect below for why: `conversations` only ever grows for the life of the page, and merging
  // every search match into it permanently accumulates memory across a whole session of searching,
  // which is exactly what caused the mobile-Chrome-on-iOS-only OOM crash found live 2026-08-28
  // (Safari on the same iPhone was fine — WKWebView-hosted browsers like iOS Chrome run under a
  // materially lower per-tab memory ceiling than Safari itself, so this never reproduced on desktop
  // regardless of emulated screen size). Reset to {} on every new search (see the effect), so it
  // never holds more than the current query's matches.
  const [searchHitSummaries, setSearchHitSummaries] = useState<Record<string, SmsConversationDto>>({});
  const bottomRef = useRef<HTMLDivElement>(null);
  const messageListRef = useRef<HTMLDivElement>(null);
  // Scrollable conversation-list container — handed to the row virtualizer below (see
  // visibleConversations/rowVirtualizer) so it knows what to measure scroll position against.
  const conversationListScrollRef = useRef<HTMLDivElement>(null);
  // Gates the auto-scroll-to-latest effect below — without this, every background thread refetch
  // (an SSE ping for a delivery-status change, a reaction, a click event, none of which the reader
  // asked to jump away from what they're currently reading) yanked a manager scrolled up into
  // history straight back down to the newest message. Starts true so a freshly opened thread still
  // lands at the bottom on first load, same as any other chat app; updated on scroll and forced
  // back to true right when the manager opens a thread or sends a reply themselves — both cases
  // where snapping to the bottom is exactly what's wanted.
  const isNearBottomRef = useRef(true);
  // Read once, inside the debounce effect below, then reset — lets the info-button on a list row
  // open straight into the contact panel on the same tap, without a race against the effect's own
  // showContactPanel reset (which normally closes it on every fresh selectedPhone).
  const pendingShowContactPanelRef = useRef(false);
  // Mirrors selectedPhone for the SSE handler below, which is set up once on mount (not re-run on
  // every selection change — reopening the EventSource per click would be wasteful and would drop
  // events during the brief reconnect window) and so can't close over selectedPhone directly.
  const selectedPhoneRef = useRef<string | null>(selectedPhone);

  // This whole page is built from internally-scrollable regions (the conversation list, the
  // thread's own message list, the contact-info panel) — nothing about it is meant to page-scroll
  // as a whole. Without this, `body` could still scroll (or, on iOS, elastically bounce past its
  // own edge) whenever its natural height ended up even a pixel taller than the visible viewport
  // — e.g. `body`'s own `min-h-full` (see layout.tsx) is measured against the *layout* viewport,
  // which doesn't always exactly match `main`'s `--vvh`-driven height (see page.tsx's own doc
  // comment on that mismatch). The visible symptom: dragging on what looks like blank space below
  // the composer actually scrolled the whole page, revealing more blank space that has no content
  // in it at all. Locking body scroll while this page is mounted closes that off entirely — every
  // scroll gesture is then unambiguously handled by whichever internal panel it started in.
  useEffect(() => {
    const previousBodyOverflow = document.body.style.overflow;
    const previousHtmlOverscroll = document.documentElement.style.overscrollBehaviorY;
    document.body.style.overflow = 'hidden';
    document.documentElement.style.overscrollBehaviorY = 'none';
    return () => {
      document.body.style.overflow = previousBodyOverflow;
      document.documentElement.style.overscrollBehaviorY = previousHtmlOverscroll;
    };
  }, []);

  // Object URLs for the staged attachment previews — revoked whenever the staged set changes or
  // the component unmounts, so switching photos (or threads) doesn't leak blob URLs.
  const attachedPreviews = useMemo(() => attachedFiles.map((f) => URL.createObjectURL(f)), [attachedFiles]);
  useEffect(() => {
    return () => {
      attachedPreviews.forEach((u) => URL.revokeObjectURL(u));
    };
  }, [attachedPreviews]);

  // Auto-grow the composer textarea with its content (capped, then it scrolls internally like
  // any other chat app) — re-runs on every `draft` change, not just typing, so it also shrinks
  // back down after a programmatic clear (send, thread switch) or grows after an emoji insert.
  //
  // The `el.style.height = 'auto'` remeasure step below collapses the box for a tick to read its
  // true scrollHeight — a normal trick for auto-grow textareas, but it has a side effect: once
  // content exceeds maxHeightPx and the box is internally scrolling, collapsing/restoring the
  // height resets scrollTop to 0 without the browser re-syncing it back to wherever the caret is.
  // Caught this via an isolated repro (typing past the cap silently scrolled the just-typed line
  // out of view, above the visible box) — exactly the "can't see what I'm typing" shape of bug.
  // Forcing scrollTop back to the bottom after every resize keeps the most recently typed line
  // (where the caret almost always is) visible instead of hidden above the fold.
  useEffect(() => {
    const el = draftInputRef.current;
    if (!el) return;
    const maxHeightPx = 128; // ~5-6 lines at the composer's font size — matches the max-h-32 cap below
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, maxHeightPx)}px`;
    el.scrollTop = el.scrollHeight;
  }, [draft]);

  // THE actual fix for the on-screen keyboard covering the composer on mobile. `100dvh` alone
  // (the previous attempt — see page.tsx's older comment, still visible in git history) turned
  // out not to be enough: despite the name, "dynamic viewport" units in most mobile engines only
  // reliably track browser-chrome changes (the address bar hiding on scroll), not the keyboard —
  // so `main` stayed sized as if no keyboard was open, and the composer at its bottom ended up
  // physically underneath it with nothing to scroll into view (nothing overflowed; the layout
  // just never shrank). `window.visualViewport.height`, unlike dvh, IS reliably shrunk by every
  // mobile engine's keyboard — so this drives a `--vvh` custom property on the document root that
  // page.tsx's root container sizes itself from (`h-[var(--vvh,100dvh)]`, dvh only as the
  // pre-hydration/no-VisualViewport-support fallback). Runs once on mount to set the initial
  // value, then on every resize (keyboard open/close, browser chrome change, rotation, ...).
  useEffect(() => {
    const viewport = typeof window !== 'undefined' ? window.visualViewport : undefined;
    if (!viewport) return;
    function syncViewportHeight() {
      document.documentElement.style.setProperty('--vvh', `${viewport!.height}px`);
      // Belt-and-suspenders: if the composer is mid-focus when the keyboard finishes animating
      // open, make sure it's still the thing on screen rather than trusting the resize alone.
      if (document.activeElement === draftInputRef.current) {
        draftInputRef.current?.scrollIntoView({ block: 'end' });
      }
    }
    syncViewportHeight();
    viewport.addEventListener('resize', syncViewportHeight);
    return () => {
      viewport.removeEventListener('resize', syncViewportHeight);
      document.documentElement.style.removeProperty('--vvh');
    };
  }, []);

  // A second, immediate nudge on focus itself — covers the moment right as the keyboard starts
  // animating open, before any visualViewport resize event has fired yet, and covers browsers
  // without VisualViewport support at all (scrollIntoView alone still helps there).
  function scrollComposerIntoView() {
    requestAnimationFrame(() => {
      draftInputRef.current?.scrollIntoView({ block: 'end' });
    });
  }

  useEffect(() => {
    if (!selectedPhone) return;
    let cancelled = false;
    // Deferred via setTimeout (same convention as SmsActivityLog's debounced fetch) so every
    // setState call here happens inside a callback rather than synchronously in the effect body.
    const handle = setTimeout(() => {
      setShowContactPanel(pendingShowContactPanelRef.current);
      pendingShowContactPanelRef.current = false;
      setContact(undefined);
      // Staged photos and any half-typed draft don't belong to whatever thread comes next —
      // without this, switching conversations mid-reply silently carried the old draft into the
      // new customer's composer.
      setAttachedFiles([]);
      setDraft('');
      setDraftError(null);
      // A freshly opened thread should land at the newest message, regardless of where the reader
      // happened to leave the scroll position of whichever thread they had open before.
      isNearBottomRef.current = true;
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
    if (isNearBottomRef.current) {
      bottomRef.current?.scrollIntoView({ block: 'end' });
    }
  }, [thread]);

  // Threshold, not an exact ==0 check — scroll position rarely lands on the precise pixel, and a
  // reader who's basically at the bottom (say, mid-inertial-scroll on mobile) should still count
  // as "at the bottom" for the auto-scroll-on-new-message behavior above.
  const NEAR_BOTTOM_THRESHOLD_PX = 120;

  function handleMessageListScroll() {
    const el = messageListRef.current;
    if (!el) return;
    isNearBottomRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < NEAR_BOTTOM_THRESHOLD_PX;
  }

  // Debounced backend search — covers message-content, name, and phone-digit matches (see
  // SmsMessageLogService#searchConversations), including phone numbers the conversation list
  // hasn't loaded yet (fetched individually by the effect just below). Skipped for very short
  // queries (1 character) so we're not firing a broad, mostly-useless lookup on every keystroke.
  // The client-side name/phone filter below (in render) still runs instantly against whatever's
  // already loaded, with or without this.
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

  // Merges a fresh conversation summary into the list and re-sorts by lastMessageAt — the
  // single-conversation refresh used by both the SSE handler and sendReply below, so a live update
  // or a just-sent reply never has to re-fetch (and truncate) the whole, possibly paginated, list.
  // A phone number not yet loaded (e.g. a dormant conversation that just got a new text) is simply
  // added, then sorted into its correct place like everything else.
  //
  // Re-sorts rather than unconditionally moving `fresh` to index 0: this used to always jump the
  // updated row straight to the top, which was right for an actual new message but wrong for the
  // *other* thing that broadcasts the exact same SSE "this phone number changed" ping — opening a
  // thread also calls markSmsThreadRead, which the backend treats as a change worth telling every
  // open tab about (see SmsMessageLogService#markThreadRead). That meant simply opening a
  // conversation lower in the list bounced it to the top, even though its lastMessageAt never
  // actually moved (found live 2026-08-21). Sorting by the real timestamp fixes both at once: a
  // genuine new message still sorts first (it has the newest lastMessageAt), while a read-only
  // refresh leaves the row exactly where it already was.
  function upsertConversation(fresh: SmsConversationDto) {
    setConversations((prev) => {
      const idx = prev.findIndex((c) => c.phoneNumber === fresh.phoneNumber);
      const next = idx === -1 ? [...prev, fresh] : [...prev.slice(0, idx), fresh, ...prev.slice(idx + 1)];
      next.sort((a, b) => new Date(b.lastMessageAt).getTime() - new Date(a.lastMessageAt).getTime());
      return next;
    });
  }

  // Authoritative total-unread count for the header's MessagesNotifierIcon badge (see
  // smsUnreadEvent) — a direct backend count, not summed from `conversations`, since that array
  // only ever holds the currently-loaded page(s) once pagination is in play and would otherwise
  // silently under-count once the salon has more conversations than fit on one page.
  function refreshUnreadBadge() {
    api.getSmsUnreadCount().then(({ unreadCount }) => dispatchSmsUnreadCountChanged(unreadCount)).catch(() => {});
  }

  // Fetches the next page and appends it — guarded via refs (not the state values directly) so the
  // IntersectionObserver below can call this without needing to be recreated every time a page
  // loads. Dedupes against whatever's already loaded: a conversation that moved via upsertConversation
  // in between page loads (a live SSE event bumping it to the top) could otherwise show up twice.
  async function loadMoreConversations() {
    if (loadingMoreRef.current || !hasMoreRef.current) return;
    setLoadingMore(true);
    try {
      const page = await api.listSmsConversationsPage(nextCursorRef.current);
      setConversations((prev) => {
        const seen = new Set(prev.map((c) => c.phoneNumber));
        return [...prev, ...page.items.filter((c) => !seen.has(c.phoneNumber))];
      });
      setNextCursor(page.nextCursor);
      setHasMore(page.hasMore);
    } catch {
      // Leave hasMore/nextCursor untouched — the sentinel stays visible/in view, so the next
      // scroll tick (or the observer re-firing) retries automatically; not worth a dedicated
      // error UI for a background "load more" this minor.
    } finally {
      setLoadingMore(false);
    }
  }

  const loadMoreSentinelRef = useRef<HTMLDivElement>(null);

  // Infinite scroll — observes a sentinel div rendered at the bottom of the (already internally
  // scrollable) conversation list; scrolling it into view fetches the next page. Set up once
  // (empty deps): the callback reads current cursor/hasMore/loading state through the refs above
  // rather than closing over the component's own state, so this never needs to be torn down and
  // recreated as more pages load — matches selectedPhoneRef's own ref-mirroring convention above.
  useEffect(() => {
    const el = loadMoreSentinelRef.current;
    if (!el) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting) void loadMoreConversations();
      },
      // rootMargin fires the fetch a couple hundred px before the sentinel is actually visible —
      // the next page is already loading by the time a reader finishes scrolling to the bottom,
      // rather than them seeing a loading spinner appear only once they get there.
      { root: el.parentElement, rootMargin: '200px' },
    );
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  // A conversation deep-linked via ?phone= (see page.tsx) may not be on the first loaded page —
  // fetch and prepend it so the thread header/list row can show its name/VIP/consent badges
  // immediately instead of only the bare phone number until enough "load more" scrolling reaches it.
  useEffect(() => {
    if (initialSelectedPhone && !initialConversations.some((c) => c.phoneNumber === initialSelectedPhone)) {
      api.getSmsConversationSummary(initialSelectedPhone).then((fresh) => {
        if (fresh) upsertConversation(fresh);
      });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Surfaces search hits for phone numbers the conversation list hasn't loaded yet — the
  // name/phone filter below (in render) only ever matches already-loaded `conversations` rows,
  // and the backend search covers name/phone matches too now, not just message-body content (see
  // SmsMessageRepository#findPhoneNumbersMatchingNameOrDigits), so a hit here can legitimately
  // point at a phone number nowhere in the currently-loaded page(s).
  //
  // Populates searchHitSummaries, NOT conversations — deliberately kept out of the permanent list
  // (see that state's own doc comment above for why: merging every match in permanently accumulates
  // memory for the life of the page, which is what actually caused the mobile-Chrome-iOS crash, not
  // rendering cost — see visibleConversations below for how these get shown alongside the real list,
  // and openThread for the one place a hit gets promoted into `conversations`, on actually being
  // opened). Reset at the top of every run so a cleared/changed search doesn't keep stale entries
  // from a previous query around.
  //
  // This used to instead bulk-load *every* remaining page the moment a query was non-empty, so the
  // purely-client-side name/phone filter would cover every conversation, not just the loaded ones.
  // Found live 2026-08-27/28, in three stages: first, overlapping loop instances from rapid
  // keystrokes caused a tight busy-loop pinning the main thread (fixed once); then, once that
  // overlap was fixed, a *single* well-behaved loop still froze the tab for this business's 400+
  // conversations — dozens of sequential fetches each triggering a full re-render of an ever-
  // growing, unvirtualized list (fixed by fetching only actual matches, then by virtualizing the
  // list itself); then, once render cost was no longer the bottleneck, permanently merging every
  // match into `conversations` turned out to still slowly grow the page's live memory footprint
  // across a session, tall enough by day's end to tip iOS Chrome's tighter per-tab memory ceiling
  // over the edge on specifically the more common letters (more matches merged in) — Safari on the
  // same phone, with a higher ceiling, never hit it.
  useEffect(() => {
    if (searchHits.length === 0) {
      setSearchHitSummaries({});
      return;
    }
    let cancelled = false;
    setSearchHitSummaries({});
    const loaded = new Set(conversationsRef.current.map((c) => c.phoneNumber));
    // Capped, not just deduped: a common single/double-letter query can legitimately match a large
    // slice of the salon's real history (e.g. "s" or "an" hitting dozens of names/messages at once)
    // — firing one fetch per match unconditionally reintroduces the exact fan-out this effect was
    // written to replace (see this effect's own doc comment above). The visible/loaded rows already
    // narrow to what's actually relevant; a match past the cap simply doesn't have its row fetched
    // until the search is narrowed further, same UX as any "showing top N results" search box.
    const MAX_HITS_TO_FETCH = 25;
    const missing = searchHits.map((h) => h.phoneNumber).filter((p) => !loaded.has(p)).slice(0, MAX_HITS_TO_FETCH);
    missing.forEach((phoneNumber) => {
      api.getSmsConversationSummary(phoneNumber).then((summary) => {
        if (!cancelled && summary) {
          setSearchHitSummaries((prev) => ({ ...prev, [phoneNumber]: summary }));
        }
      }).catch(() => {});
    });
    return () => {
      cancelled = true;
    };
  }, [searchHits]);

  // Live updates — an inbound text, a delivery-status change, a read/block toggle from another
  // tab, etc. all land here as a bare "this phone number changed" ping (see backend
  // SmsEventBroadcaster's own doc for why it's not a full payload). Refreshes just that one
  // conversation (upsertConversation) rather than the old "refetch the whole conversations list"
  // — with pagination in play, a full-list refetch would silently truncate/reorder whatever the
  // manager had already scrolled to load further down the list. SSE over polling: customer texts
  // arrive sporadically, so instant push beats trading off staleness against wasted requests
  // either way; native EventSource also handles reconnect on a dropped connection with zero code
  // here.
  useEffect(() => {
    const source = new EventSource('/api/owner/automations/activity/stream');
    let debounceHandle: ReturnType<typeof setTimeout> | null = null;
    source.addEventListener('update', (e: MessageEvent) => {
      let phoneNumber: string | null = null;
      try {
        phoneNumber = (JSON.parse(e.data) as { phoneNumber?: string }).phoneNumber ?? null;
      } catch {
        // Malformed payload — falls through to the "unknown phone number" branch below.
      }
      if (debounceHandle) clearTimeout(debounceHandle);
      // A short debounce coalesces a burst of near-simultaneous events (e.g. an inbound message
      // plus an automated reply) into one refetch instead of several back-to-back ones.
      debounceHandle = setTimeout(() => {
        if (phoneNumber) {
          api.getSmsConversationSummary(phoneNumber).then((fresh) => {
            if (fresh) upsertConversation(fresh);
          });
        } else {
          // No phone number to target (malformed payload) — can't upsert a single row, so refresh
          // just the first page instead of the old unbounded full-list fetch.
          api.listSmsConversationsPage().then((page) => {
            setConversations(page.items);
            setNextCursor(page.nextCursor);
            setHasMore(page.hasMore);
          });
        }
        refreshUnreadBadge();
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
    setConversations((prev) => {
      const idx = prev.findIndex((c) => c.phoneNumber === phoneNumber);
      if (idx !== -1) {
        return prev.map((c) => (c.phoneNumber === phoneNumber ? { ...c, unreadCount: 0 } : c));
      }
      // Only reachable for a row that exists purely as a search hit (see searchHitSummaries'
      // own doc comment) — promote it into the permanent list now that it's actually being
      // opened, so the thread header has its name/VIP/consent badges immediately instead of
      // falling back to a bare phone number until the next unrelated upsert.
      const fromSearch = searchHitSummaries[phoneNumber];
      if (!fromSearch) return prev;
      const next = [...prev, { ...fromSearch, unreadCount: 0 }];
      next.sort((a, b) => new Date(b.lastMessageAt).getTime() - new Date(a.lastMessageAt).getTime());
      return next;
    });
    // Tells the header's MessagesNotifierIcon (a separate component tree — see
    // smsUnreadEvent's own doc comment) about the new total immediately, instead of leaving it
    // stuck showing the old count until its own next poll cycle or a full page refresh.
    refreshUnreadBadge();
  }

  // "Mark as unread" — same iMessage/Gmail convention as the backend doc comment describes:
  // marking the *currently open* thread unread also backs out to the conversation list, since
  // leaving it open would otherwise immediately re-mark it read (see the selectedPhone effect
  // above, which calls markSmsThreadRead on every open).
  async function markUnread(phoneNumber: string) {
    const wasSelected = phoneNumber === selectedPhone;
    setConversations((prev) =>
      prev.map((c) => (c.phoneNumber === phoneNumber ? { ...c, unreadCount: Math.max(c.unreadCount, 1) } : c)),
    );
    refreshUnreadBadge();
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
    setConversations(conversations.map((c) => (c.phoneNumber === phoneNumber ? { ...c, blocked: !currentlyBlocked, optedOut: false } : c)));
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
    // A pure-photo message (no text) is valid — only a genuinely empty send (no text, no photos)
    // is blocked.
    if ((!body && attachedFiles.length === 0) || !selectedPhone || sending) return;
    setSending(true);
    try {
      const result = attachedFiles.length > 0
        ? await api.sendSmsReplyWithMedia(selectedPhone, body, attachedFiles)
        : await api.sendSmsReply(selectedPhone, body);
      if (result.sent) {
        setDraft('');
        setAttachedFiles([]);
        // A manager sending their own reply always expects to see it land at the bottom, even if
        // they'd scrolled up to reference something earlier in the thread first.
        isNearBottomRef.current = true;
        const [freshThread, freshSummary] = await Promise.all([
          api.getSmsThread(selectedPhone),
          api.getSmsConversationSummary(selectedPhone),
        ]);
        setThread(freshThread);
        if (freshSummary) upsertConversation(freshSummary);
        refreshUnreadBadge();
      }
    } finally {
      setSending(false);
    }
  }

  // Inserts at the current cursor position (falling back to the end, if the input never had
  // focus) rather than always appending — lets a manager drop an emoji mid-sentence without
  // retyping anything.
  function insertEmojiIntoDraft(emoji: string) {
    const el = draftInputRef.current;
    const start = el?.selectionStart ?? draft.length;
    const end = el?.selectionEnd ?? draft.length;
    const next = draft.slice(0, start) + emoji + draft.slice(end);
    setDraft(next);
    requestAnimationFrame(() => {
      el?.focus();
      const cursor = start + emoji.length;
      el?.setSelectionRange(cursor, cursor);
    });
  }

  // AI-drafted reply suggestion ("Generate" button) — fills the composer for the manager to review
  // and edit, never sends on its own. Overwrites whatever's already typed, same as clicking any
  // other "insert" control in this composer (attach photo, emoji).
  async function generateDraft() {
    if (!selectedPhone || drafting) return;
    setDrafting(true);
    setDraftError(null);
    try {
      const result = await api.draftSmsReply(selectedPhone);
      setDraft(result.body);
      requestAnimationFrame(() => draftInputRef.current?.focus());
    } catch {
      setDraftError('Could not generate a draft right now — please write a reply manually, or try again.');
    } finally {
      setDrafting(false);
    }
  }

  function addAttachedFiles(files: FileList | null) {
    if (!files || files.length === 0) return;
    setAttachedFiles((prev) => [...prev, ...Array.from(files)]);
  }

  function removeAttachedFile(index: number) {
    setAttachedFiles((prev) => prev.filter((_, i) => i !== index));
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
      : (() => {
          const matched = conversations.filter((c) => {
            const name = displayName(c.givenName, c.familyName);
            const nameMatches = name != null && name.toLowerCase().includes(lowerQuery);
            const phoneMatches = queryDigits.length > 0 && c.phoneNumber.replace(/\D/g, '').includes(queryDigits);
            return nameMatches || phoneMatches || searchHitByPhone.has(c.phoneNumber);
          });
          // Rows for matches not (yet, or ever permanently) in `conversations` — see
          // searchHitSummaries' own doc comment on why these are kept separate rather than merged
          // into the permanent list.
          const matchedPhones = new Set(matched.map((c) => c.phoneNumber));
          const extra = Object.values(searchHitSummaries).filter((c) => !matchedPhones.has(c.phoneNumber));
          return [...matched, ...extra];
        })();

  // Only ~10-20 rows ever actually mount in the DOM, regardless of how many conversations are
  // loaded (this business alone has 400+) — without this, every keystroke in the search box
  // re-filtered *and fully re-rendered* the entire matching set (each row carrying several icon
  // components, a highlighted-match calculation, badges...), which was cheap while the list was
  // short but became a multi-hundred-row synchronous re-render once enough conversations had been
  // paged/searched into `conversations`. Fast desktops absorbed it as jank (surfacing as a stray
  // React error after the long blocked frame); phones just froze — found live 2026-08-28, the third
  // and root form of this same "list this size needs to be handled boundedly" issue (see the
  // search-hit-fetching effect's own doc comment above for the first two). estimateSize is a rough
  // single-line-row guess; measureElement (wired via the row's ref below) corrects it once a row
  // with a phone-number subtitle actually mounts, so scroll position stays accurate either way.
  const rowVirtualizer = useVirtualizer({
    count: visibleConversations.length,
    getScrollElement: () => conversationListScrollRef.current,
    estimateSize: () => 72,
    overscan: 8,
  });

  return (
    // Desktop height now comes from page.tsx (sm:h-[calc(100vh-8rem)] on `main`) — this just fills
    // whatever that gives it, rather than inventing its own independent sm:h-[70vh] guess (see
    // page.tsx's doc comment on why that guess didn't track actual available screen space).
    <div data-testid="messages-view-root" className="flex h-full min-h-0 overflow-hidden sm:rounded-lg sm:ring-1 sm:ring-zinc-200">
      {/* Contact list — full width on mobile until a thread is opened, fixed sidebar on desktop.
          sm:w-96 (not sm:w-72) — narrower than this cut real customer names off mid-word before a
          manager could tell who they were looking at without opening the thread. */}
      <div
        data-testid="conversation-list"
        className={`flex w-full shrink-0 flex-col overflow-hidden border-r border-zinc-200 sm:flex sm:w-96 ${selectedPhone ? 'hidden sm:flex' : ''}`}
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
        <div ref={conversationListScrollRef} className="min-h-0 flex-1 overflow-y-auto overflow-x-hidden">
        {conversations.length === 0 ? (
          <div className="p-6 text-center text-sm text-zinc-500">No conversations yet.</div>
        ) : visibleConversations.length === 0 ? (
          <div data-testid="conversation-search-empty" className="p-6 text-center text-sm text-zinc-500">
            No conversations match &ldquo;{trimmedQuery}&rdquo;.
          </div>
        ) : (
          <div style={{ position: 'relative', height: rowVirtualizer.getTotalSize() }}>
          {rowVirtualizer.getVirtualItems().map((virtualRow) => {
            const c = visibleConversations[virtualRow.index];
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
                ref={rowVirtualizer.measureElement}
                data-index={virtualRow.index}
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
                style={{ position: 'absolute', top: 0, left: 0, width: '100%', transform: `translateY(${virtualRow.start}px)` }}
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
                    {c.blocked && <span data-testid="conversation-row-blocked-icon"><BlockedIcon optedOut={c.optedOut} /></span>}
                    {c.flaggedAsSpam && <span data-testid="conversation-row-spam-flag-icon"><SpamFlagIcon /></span>}
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
          })}
          </div>
        )}
        {/* Infinite-scroll sentinel — invisible, just an IntersectionObserver target (see the
            effect above). Only rendered once there's confirmed more to fetch, so it never lingers
            as a dead scroll-target once the whole list is loaded. */}
        {hasMore && visibleConversations.length > 0 && (
          <div ref={loadMoreSentinelRef} data-testid="conversation-list-load-more-sentinel" className="h-1" />
        )}
        {loadingMore && (
          <div data-testid="conversation-list-loading-more" className="flex items-center justify-center gap-2 py-4 text-xs text-zinc-400">
            <Spinner className="h-4 w-4" />
            <span>Loading more…</span>
          </div>
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
                {selectedConversation?.blocked && <span data-testid="thread-header-blocked-icon"><BlockedIcon optedOut={selectedConversation?.optedOut} /></span>}
                {selectedConversation?.flaggedAsSpam && <span data-testid="thread-header-spam-flag-icon"><SpamFlagIcon /></span>}
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

            <div
              ref={messageListRef}
              onScroll={handleMessageListScroll}
              data-testid="thread-message-list"
              className="min-h-0 flex-1 overflow-y-auto overflow-x-hidden px-4 py-3"
            >
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
                        <div className={`flex min-w-0 flex-col ${m.direction === 'OUTBOUND' ? 'items-end' : 'items-start'}`}>
                          <div
                            data-testid="thread-message-bubble"
                            data-direction={m.direction}
                            className={`max-w-[75%] rounded-2xl px-3 py-2 text-sm ${
                              m.direction === 'OUTBOUND' ? 'bg-sky-600 text-white' : 'bg-zinc-100 text-zinc-900'
                            }`}
                          >
                            {m.media.length > 0 && (
                              <div data-testid="thread-message-media" className={`grid gap-1 ${m.media.length > 1 ? 'grid-cols-2' : ''} ${m.body ? 'mb-1.5' : ''}`}>
                                {m.media.map((media, mi) => (
                                  <a key={mi} href={media.url} target="_blank" rel="noreferrer" className="block overflow-hidden rounded-lg">
                                    {/* eslint-disable-next-line @next/next/no-img-element -- remote,
                                        opaque-token-served images from our own /api/public/sms-media
                                        endpoint; next/image's optimizer adds nothing here. */}
                                    <img src={media.url} alt="" loading="lazy" className="h-full max-h-56 w-full object-cover" />
                                  </a>
                                ))}
                              </div>
                            )}
                            {m.body && <p className="whitespace-pre-wrap break-words">{m.body}</p>}
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
                          {m.emailFollowUp && <EmailFollowUpCard followUp={m.emailFollowUp} />}
                          {m.reactions.length > 0 && (
                            <div data-testid="thread-message-reactions" className="mt-0.5 flex flex-wrap gap-1">
                              {m.reactions.map((r, ri) => (
                                <span
                                  key={ri}
                                  title="Customer reacted"
                                  className="flex items-center rounded-full border border-zinc-200 bg-white px-1.5 py-0.5 text-xs shadow-sm"
                                >
                                  {r.emoji}
                                </span>
                              ))}
                            </div>
                          )}
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
              <div className="border-t border-zinc-200 pb-[max(0.75rem,env(safe-area-inset-bottom))]">
                {attachedFiles.length > 0 && (
                  <div data-testid="thread-composer-attachments" className="flex gap-2 overflow-x-auto px-3 pt-3">
                    {attachedFiles.map((file, i) => (
                      <div key={i} className="relative shrink-0">
                        {/* eslint-disable-next-line @next/next/no-img-element -- a local blob:
                            object URL for an in-memory File; next/image doesn't apply. */}
                        <img src={attachedPreviews[i]} alt="" className="h-16 w-16 rounded-lg object-cover ring-1 ring-zinc-200" />
                        <button
                          type="button"
                          data-testid="thread-composer-attachment-remove"
                          onClick={() => removeAttachedFile(i)}
                          aria-label={`Remove ${file.name}`}
                          className="absolute -right-1.5 -top-1.5 flex h-5 w-5 items-center justify-center rounded-full bg-zinc-900 text-white shadow"
                        >
                          <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
                            <path d="M18 6 6 18" /><path d="m6 6 12 12" />
                          </svg>
                        </button>
                      </div>
                    ))}
                  </div>
                )}
                {draftError && (
                  <p data-testid="thread-composer-draft-error" className="px-3 pt-2 text-xs text-red-600">
                    {draftError}
                  </p>
                )}
                <form
                  data-testid="thread-composer"
                  onSubmit={(e) => {
                    e.preventDefault();
                    void sendReply();
                  }}
                  className="flex items-end gap-2 p-3"
                >
                  <input
                    ref={fileInputRef}
                    type="file"
                    accept="image/*"
                    multiple
                    data-testid="thread-composer-file-input"
                    className="hidden"
                    onChange={(e) => {
                      addAttachedFiles(e.target.files);
                      e.target.value = '';
                    }}
                  />
                  <button
                    type="button"
                    data-testid="thread-composer-attach-button"
                    onClick={() => fileInputRef.current?.click()}
                    aria-label="Attach a photo"
                    className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full text-zinc-400 hover:bg-zinc-100 hover:text-zinc-600 sm:h-9 sm:w-9"
                  >
                    <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
                      <path d="m21.44 11.05-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48" />
                    </svg>
                  </button>
                  <EmojiPicker
                    emojis={COMPOSER_EMOJIS}
                    onSelect={insertEmojiIntoDraft}
                    ariaLabel="Insert an emoji"
                    trigger={
                      <span
                        data-testid="thread-composer-emoji-button"
                        className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full text-zinc-400 hover:bg-zinc-100 hover:text-zinc-600 sm:h-9 sm:w-9"
                      >
                        <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
                          <circle cx="12" cy="12" r="10" /><path d="M8 14s1.5 2 4 2 4-2 4-2" /><line x1="9" x2="9.01" y1="9" y2="9" /><line x1="15" x2="15.01" y1="9" y2="9" />
                        </svg>
                      </span>
                    }
                  />
                  {/* text-base (16px), not text-sm, on mobile — a smaller font on a focused input
                      makes iOS Safari auto-zoom the whole page, which is jarring here.

                      A <textarea>, not a single-line <input> — inside a <form>, an <input>'s Enter
                      key submits (sends) instead of adding a line break, which is both a jarring
                      mobile UX (the "return" key on the on-screen keyboard silently fires a send)
                      and made a longer reply hard to compose/review since it could never wrap to
                      more than one visible line. Enter now does what it does in any chat app: adds
                      a new line, no keydown override needed — that's a plain <textarea>'s default
                      behavior inside a form. Auto-grows via the `draft` effect above, capped at
                      max-h-32 with its own scroll past that point. rows=1 keeps the initial/empty
                      height matched to the surrounding buttons instead of the browser's fallback
                      2-line default. onFocus's scrollComposerIntoView + the visualViewport effect
                      above are the fix for "can't see what I'm typing" on mobile: the composer
                      staying out from under the on-screen keyboard even in mobile browsers/webviews
                      that handle 100dvh's keyboard-resize behavior inconsistently.

                      The AI "Generate" button lives inside this wrapper, floated in the textarea's
                      own top-right corner, rather than as a 5th icon in the outer row — on a narrow
                      phone (≤375px) four 44px touch-target buttons plus this textarea in one flex
                      row left barely 100px for actual typing. The textarea's pr-11/sm:pr-9 reserves
                      that corner on every line (CSS padding applies to the whole box, not just the
                      first line), so typed text can never run under the button regardless of how
                      many lines the draft grows to. */}
                  <div className="relative min-w-0 flex-1">
                    <textarea
                      ref={draftInputRef}
                      data-testid="thread-composer-input"
                      value={draft}
                      onChange={(e) => setDraft(e.target.value)}
                      onFocus={scrollComposerIntoView}
                      placeholder={attachedFiles.length > 0 ? 'Add a caption…' : 'Type a reply…'}
                      rows={1}
                      className="max-h-32 w-full resize-none overflow-y-auto rounded-2xl border border-zinc-300 py-2.5 pl-4 pr-11 text-base leading-normal sm:py-2 sm:pr-9 sm:text-sm"
                    />
                    <button
                      type="button"
                      data-testid="thread-composer-generate-button"
                      onClick={() => void generateDraft()}
                      disabled={drafting}
                      aria-label="Generate an AI reply suggestion"
                      title="Generate an AI reply suggestion"
                      className="absolute right-1 top-1 flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-zinc-400 hover:bg-zinc-100 hover:text-zinc-600 disabled:opacity-40 sm:h-7 sm:w-7"
                    >
                      {drafting ? (
                        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" className="animate-spin" aria-hidden>
                          <path d="M21 12a9 9 0 1 1-6.219-8.56" />
                        </svg>
                      ) : (
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
                          <path d="m12 3-1.4 3.6L7 8l3.6 1.4L12 13l1.4-3.6L17 8l-3.6-1.4Z" />
                          <path d="M5 17l-.8 2-2 .8 2 .8.8 2 .8-2 2-.8-2-.8Z" />
                          <path d="M19 15l-.6 1.4-1.4.6 1.4.6.6 1.4.6-1.4 1.4-.6-1.4-.6Z" />
                        </svg>
                      )}
                    </button>
                  </div>
                  <button
                    type="submit"
                    data-testid="thread-composer-send-button"
                    disabled={(!draft.trim() && attachedFiles.length === 0) || sending}
                    className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-sky-600 text-white disabled:opacity-40 sm:h-auto sm:w-auto sm:px-4 sm:py-2"
                    aria-label="Send"
                  >
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden className="sm:hidden">
                      <path d="m22 2-7 20-4-9-9-4Z" /><path d="M22 2 11 13" />
                    </svg>
                    <span className="hidden text-sm font-medium sm:inline">Send</span>
                  </button>
                </form>
              </div>
            )}
          </>
        )}
      </div>

      {/* Contact info — mobile: full-screen overlay toggled by the "i" button above; desktop:
          always-visible third column (sm:flex wins over the mobile-only hidden/flex toggle). */}
      {selectedPhone ? (
        <div
          data-testid="contact-info-panel-wrapper"
          className={`${showContactPanel ? 'flex' : 'hidden'} fixed inset-0 z-20 flex-col bg-white sm:static sm:z-auto sm:flex sm:w-96 sm:shrink-0 sm:border-l sm:border-zinc-200`}
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
            optedOut={selectedConversation?.optedOut ?? false}
            clickedGoogleReview={selectedConversation?.clickedGoogleReview ?? false}
            clickedFeedbackForm={selectedConversation?.clickedFeedbackForm ?? false}
            flaggedAsSpam={selectedConversation?.flaggedAsSpam ?? false}
            onClose={() => setShowContactPanel(false)}
          />
        </div>
      ) : null}
    </div>
  );
}
