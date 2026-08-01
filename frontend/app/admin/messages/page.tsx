import { redirect } from 'next/navigation';
import { serverApi } from '../../lib/serverApi';
import PageHeader from '../../components/PageHeader';
import MessagesView from './MessagesView';

// Shared OWNER+MANAGER conversation view — see openspec/changes/lead-followup-and-manager-inbox
// design.md D6/D7. Lives under /admin/* (not /owner/*) since both roles use it, matching this
// app's existing /admin/redos, /admin/manual-adjustments convention. The automation registry +
// toggle + activity log now live at /owner/settings/sms.
//
// Mobile is edge-to-edge, full dynamic-viewport height (100dvh, not 100vh — dvh tracks the
// visual viewport as the on-screen keyboard opens/closes, so the composer never ends up hidden
// behind it) so this reads as a real chat app rather than a bounded card floating in page
// padding. Desktop keeps the bounded two-column card look (see MessagesView's own sm: classes).
//
// The title row (and the AdminMenu it renders) is hidden on mobile the instant a thread is open —
// MessagesView marks its thread column with a `thread-open` class only while one is selected, and
// `group-has-[.thread-open]/messages` reacts to that purely in CSS (no state lifted up from the
// client component). Without this, a manager mid-conversation saw two stacked header bars (this
// page's own title + AdminMenu, then the thread's own back/name/info bar right below it) eating
// real chat space on a small screen — collapsing to one, like any native messaging app, was the
// whole point of the fix. Desktop is unaffected (sm: below always shows it): there's room for a
// permanent title bar there. Losing AdminMenu access while inside a thread on mobile is the
// deliberate trade — the thread's own back button gets you out in one tap, same as a normal chat
// app never keeping its global nav visible over an open conversation.
//
// ?phone= deep-links straight into that customer's thread — used by the inbound-SMS Telegram
// alert's "Open chat" link (see TelegramNotificationService#chatLink on the backend), so tapping
// it lands directly in the conversation instead of the manager having to find it themselves.
export default async function MessagesPage({
  searchParams,
}: {
  searchParams: Promise<{ phone?: string }>;
}) {
  const me = await serverApi.getMe();
  if (me.role !== 'OWNER' && me.role !== 'MANAGER') redirect('/reports');

  const [{ phone }, conversations] = await Promise.all([searchParams, serverApi.listSmsConversations()]);

  return (
    // w-full is load-bearing, not decorative: `main` is a flex item of `body`'s column flex
    // container, and `mx-auto` gives it auto cross-axis margins — per the flexbox spec, auto
    // margins on the cross axis opt an item OUT of stretch sizing, so without an explicit width
    // it shrink-wraps to its own content's max-content size instead of the viewport. Any single
    // unbreakable string anywhere inside (a long SMS body, a tracked short link) then silently
    // grows the whole page to that string's width and the page scrolls horizontally — no amount
    // of overflow-x-hidden/truncate further down the tree can prevent that, since those only clip
    // *within* a box that's already been sized too wide. w-full forces the definite viewport width
    // this container needs before any of that inner clipping can do its job. Verified against a
    // real compiled-CSS/headless-browser repro, not just reasoned about: removing w-full reproduces
    // ~950px of horizontal overflow at 320/375/390px viewports; adding it brings overflow to 0.
    <main
      data-testid="messages-page-root"
      className="group/messages mx-auto flex w-full h-[100dvh] max-w-5xl flex-col sm:h-auto sm:p-8"
    >
      <div
        data-testid="messages-page-title-row"
        className="shrink-0 px-4 pt-4 sm:px-0 sm:pt-0 max-sm:group-has-[.thread-open]/messages:hidden"
      >
        <PageHeader title="Messages" role={me.role} language={me.preferredLanguage} />
      </div>
      <div className="min-h-0 flex-1">
        <MessagesView initialConversations={conversations} initialSelectedPhone={phone ?? null} />
      </div>
    </main>
  );
}
