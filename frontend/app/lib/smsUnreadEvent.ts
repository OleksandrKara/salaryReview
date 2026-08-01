'use client';

// Cross-component "unread SMS count changed" signal. MessagesView (the /admin/messages thread
// list) and MessagesNotifierIcon (the header badge, rendered in a completely separate branch of
// the page's component tree via PageHeader/AdminMenu) share no state and no persistent
// client-side layout in this app (see MessagesNotifierIcon's own doc comment). Reading a thread
// updates MessagesView's own local list + the backend immediately, but without this,
// MessagesNotifierIcon would only find out on its own next poll (up to its POLL_INTERVAL_MS
// later) — a plain window CustomEvent lets the header badge/favicon update in the same tick
// instead of waiting, or requiring a page refresh.
const EVENT_NAME = 'sms-unread-count-changed';

export function dispatchSmsUnreadCountChanged(unreadCount: number): void {
  window.dispatchEvent(new CustomEvent<number>(EVENT_NAME, { detail: unreadCount }));
}

/** Returns an unsubscribe function — call it from the effect cleanup. */
export function onSmsUnreadCountChanged(handler: (unreadCount: number) => void): () => void {
  function listener(e: Event) {
    handler((e as CustomEvent<number>).detail);
  }
  window.addEventListener(EVENT_NAME, listener);
  return () => window.removeEventListener(EVENT_NAME, listener);
}
