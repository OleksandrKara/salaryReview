import Link from 'next/link';

/** Simple tab bar switching between the live SMS conversation inbox and the flat "Emails" log —
 * two separate pages/routes (not client-side tab state) since the email log has no notion of a
 * selected conversation to preserve across a switch. Kept deliberately tiny and separate from
 * MessagesView.tsx, which owns the conversation list's own considerably more complex state. */
export default function MessagesTabs({ active }: { active: 'conversations' | 'emails' }) {
  const tabClass = (tab: 'conversations' | 'emails') =>
    `rounded-full px-3 py-1.5 text-sm font-medium transition-colors ${
      // !text-* — found live 2026-09-08: landing.css's app-wide `a { color: inherit }` is
      // unlayered CSS, so it beats any Tailwind text-color *utility* (Tailwind wraps its own
      // utilities in a cascade layer, and an unlayered rule always wins over a layered one
      // regardless of specificity/order) — the active tab's white text and the inactive tab's
      // muted gray were both silently overridden back to the page's near-black body ink color,
      // making "Conversations" nearly unreadable against its own dark pill. Same `!text-white`
      // fix already used for this exact conflict in owner/overview/expenses/page.tsx.
      active === tab ? 'bg-zinc-900 !text-white' : '!text-zinc-500 hover:bg-zinc-100'
    }`;
  return (
    <div className="flex gap-1.5 px-4 pb-2 sm:px-0">
      <Link href="/admin/messages" className={tabClass('conversations')}>Conversations</Link>
      <Link href="/admin/messages/emails" className={tabClass('emails')}>Emails</Link>
    </div>
  );
}
