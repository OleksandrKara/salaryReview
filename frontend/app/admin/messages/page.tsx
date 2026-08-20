import { redirect } from 'next/navigation';
import { serverApi, ApiError } from '../../lib/serverApi';
import type { SmsConversationPageDto } from '../../lib/types';
import PageHeader from '../../components/PageHeader';
import MessagesView from './MessagesView';
import SetupRequiredNotice from '../../components/SetupRequiredNotice';

// Shared OWNER+MANAGER conversation view — see openspec/changes/lead-followup-and-manager-inbox
// design.md D6/D7. Lives under /admin/* (not /owner/*) since both roles use it, matching this
// app's existing /admin/redos, /admin/manual-adjustments convention. The automation registry +
// toggle + activity log now live at /owner/settings/sms.
//
// Mobile is edge-to-edge, full-height (var(--vvh, 100dvh)) so this reads as a real chat app
// rather than a bounded card floating in page padding. Desktop keeps the bounded two-column card
// look (see MessagesView's own sm: classes).
//
// `--vvh` over a plain `100dvh`: despite the name, "dynamic viewport" units in most mobile
// browser engines only reliably track browser-chrome changes (the address bar hiding on scroll),
// not the on-screen keyboard — dvh staying full-height with the keyboard open was exactly why the
// composer kept ending up hidden behind it even after switching from 100vh to 100dvh. MessagesView
// sets `--vvh` on the document root from `window.visualViewport.height`, which every mobile engine
// *does* shrink for the keyboard; falls back to the dvh value itself (the var's second arg) before
// that effect has run (first paint, or a browser with no VisualViewport support at all).
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

  const { phone } = await searchParams;
  let conversationsPage: SmsConversationPageDto;
  try {
    conversationsPage = await serverApi.listSmsConversationsPage();
  } catch (err) {
    if (err instanceof ApiError && err.code === 'sms_not_available') {
      return (
        <main className="mx-auto max-w-5xl p-4 sm:p-8">
          <PageHeader title="Messages" role={me.role} language={me.preferredLanguage} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />
          <SetupRequiredNotice
            title="SMS isn't available for this business yet"
            message="Messaging needs its own Twilio number, which isn't set up per-business yet — this is planned for a future release."
          />
        </main>
      );
    }
    throw err;
  }

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
      // Desktop height used to be sm:h-auto — this container just shrink-wrapped its content, so
      // MessagesView had to invent its own fixed sm:h-[70vh] to have *any* bounded height to work
      // with, and 70% of the viewport doesn't track how much room a given monitor actually has: a
      // tall external display left a large dead strip below the card, a short laptop screen felt
      // cramped. calc(100vh-8rem) instead reserves just the title row + this container's own
      // sm:p-8 padding (≈2rem title-row block + 2×2rem padding) and gives everything else to the
      // conversation view, same "fill what's actually available" idea as --vvh below already
      // applies on mobile. min-h keeps it usable if that math ever comes up short.
      className="group/messages mx-auto flex w-full h-[var(--vvh,100dvh)] max-w-7xl flex-col sm:h-[calc(100vh-8rem)] sm:min-h-[560px] sm:p-8"
    >
      <div
        data-testid="messages-page-title-row"
        className="shrink-0 px-4 pt-4 sm:px-0 sm:pt-0 max-sm:group-has-[.thread-open]/messages:hidden"
      >
        <PageHeader title="Messages" role={me.role} language={me.preferredLanguage} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />
      </div>
      <div className="min-h-0 flex-1">
        <MessagesView
          initialConversations={conversationsPage.items}
          initialNextCursor={conversationsPage.nextCursor}
          initialHasMore={conversationsPage.hasMore}
          initialSelectedPhone={phone ?? null}
        />
      </div>
    </main>
  );
}
