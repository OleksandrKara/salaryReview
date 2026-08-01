'use client';

import Link from 'next/link';
import { useEffect, useRef, useState } from 'react';
import { api } from '../lib/api';
import type { SmsConversationDto } from '../lib/types';
import { setFaviconBadge } from '../lib/faviconBadge';
import { playNotificationSound, primeAudio } from '../lib/notificationSound';
import { onSmsUnreadCountChanged } from '../lib/smsUnreadEvent';

const POLL_INTERVAL_MS = 25_000;

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

/**
 * Live-updating "Messages" icon + unread badge, pinned next to the burger menu (see AdminMenu) so
 * a new customer text is visible without opening the dropdown. Polls the conversations list
 * (rather than just the plain unread-count endpoint) so it can also fire a browser Notification
 * with the actual sender + preview, not just a number.
 *
 * Three independent "a new message arrived" signals, each degrading gracefully on its own if the
 * browser doesn't support it — no single one is load-bearing for the others:
 *  - A system Notification (requires permission — requested on mount, best-effort).
 *  - A short chime via {@link playNotificationSound} (requires no permission at all, but does
 *    require the page to have seen at least one user gesture first — see {@link primeAudio}).
 *  - A red badge drawn onto the favicon itself via {@link setFaviconBadge} (requires no permission
 *    and works even on browsers with no Notification API support at all, e.g. most mobile
 *    browsers — this is the one signal that reliably "just works" everywhere).
 *
 * Foreground-only: no service worker/push registered, so none of these fire while no tab running
 * this app is open at all — "less chance to miss" during a work session, not a true background
 * push (that would need a push subscription + backend endpoint, out of scope here).
 *
 * Remounts on every full page navigation (this app has no persistent client-side layout state for
 * this), which resets the polling timer and the "already seen" snapshot below — an accepted
 * trade-off: worst case is a brief window of missed notification right at a navigation boundary,
 * not a systemic gap.
 */
export default function MessagesNotifierIcon({ initialUnreadCount }: { initialUnreadCount: number }) {
  const [unreadCount, setUnreadCount] = useState(initialUnreadCount);
  // phone -> last-seen lastMessageAt. Stays null until the first poll completes, so a page load
  // never fires a notification storm for unread messages that were already sitting there.
  const seenRef = useRef<Map<string, string> | null>(null);

  // Audio autoplay is blocked until the page has seen a real user gesture — primes it on the
  // very first click/keydown/touch anywhere in the app, so by the time a poll cycle wants to play
  // a chime (never itself a user gesture), playback is already unlocked.
  useEffect(() => {
    function unlock() {
      primeAudio();
      window.removeEventListener('pointerdown', unlock);
      window.removeEventListener('keydown', unlock);
    }
    window.addEventListener('pointerdown', unlock, { once: true });
    window.addEventListener('keydown', unlock, { once: true });
    return () => {
      window.removeEventListener('pointerdown', unlock);
      window.removeEventListener('keydown', unlock);
    };
  }, []);

  // Keeps the favicon badge in sync with the same unreadCount already shown in the icon itself —
  // requires no permission and degrades to "just doesn't show" rather than erroring on a browser
  // that can't draw/replace favicons.
  useEffect(() => {
    void setFaviconBadge(unreadCount);
  }, [unreadCount]);

  // MessagesView (the /admin/messages thread list) marks a thread read the moment it's opened —
  // without this, this icon (a separate component tree, mounted in the page header via
  // PageHeader/AdminMenu) wouldn't find out until its own next poll cycle up to POLL_INTERVAL_MS
  // later, which read as "the badge doesn't update unless I refresh the page." See
  // smsUnreadEvent's own doc comment.
  useEffect(() => onSmsUnreadCountChanged(setUnreadCount), []);

  useEffect(() => {
    if (typeof Notification !== 'undefined' && Notification.permission === 'default') {
      void Notification.requestPermission();
    }

    let cancelled = false;

    async function poll() {
      let conversations: SmsConversationDto[];
      try {
        conversations = await api.listSmsConversations();
      } catch {
        return; // transient network hiccup — skip this cycle, the next poll retries
      }
      if (cancelled) return;

      setUnreadCount(conversations.reduce((sum, c) => sum + c.unreadCount, 0));

      const previouslySeen = seenRef.current;
      let sawNewInbound = false;
      if (previouslySeen) {
        for (const c of conversations) {
          if (c.lastMessageDirection !== 'INBOUND') continue;
          const lastSeenAt = previouslySeen.get(c.phoneNumber);
          if (lastSeenAt !== undefined && c.lastMessageAt <= lastSeenAt) continue;
          sawNewInbound = true;
          if (typeof Notification !== 'undefined' && Notification.permission === 'granted') {
            const notification = new Notification(`New text from ${formatPhone(c.phoneNumber)}`, {
              body: c.lastMessageBody,
              tag: c.phoneNumber, // replaces any still-showing notification for the same customer
            });
            notification.onclick = () => {
              window.focus();
              window.location.href = '/admin/messages';
            };
          }
        }
        // Once per poll cycle, not once per conversation — several customers texting inside the
        // same 25s window should still only chime once, not stack into a cacophony.
        if (sawNewInbound) playNotificationSound();
      }

      const nextSeen = new Map<string, string>();
      for (const c of conversations) nextSeen.set(c.phoneNumber, c.lastMessageAt);
      seenRef.current = nextSeen;
    }

    void poll();
    const handle = setInterval(poll, POLL_INTERVAL_MS);
    return () => {
      cancelled = true;
      clearInterval(handle);
    };
  }, []);

  return (
    <Link
      href="/admin/messages"
      aria-label={unreadCount > 0 ? `Messages — ${unreadCount} unread` : 'Messages'}
      title="Messages"
      className="relative flex h-9 w-9 items-center justify-center rounded-full bg-white text-zinc-500 shadow-sm ring-1 ring-zinc-200 hover:bg-zinc-50 hover:text-zinc-700"
    >
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
        <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
      </svg>
      {unreadCount > 0 ? (
        <span className="absolute -right-1 -top-1 flex h-[18px] min-w-[18px] items-center justify-center rounded-full bg-red-600 px-1 text-[10px] font-semibold leading-none text-white">
          {unreadCount > 99 ? '99+' : unreadCount}
        </span>
      ) : null}
    </Link>
  );
}
