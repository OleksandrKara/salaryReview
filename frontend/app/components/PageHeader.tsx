import { serverApi } from '../lib/serverApi';
import AdminMenu from './AdminMenu';
import type { Language, MeBusinessOption, Role } from '../lib/types';

// The shared page header: a consistent title row with the navigation menu, on every authenticated
// page. Pages that already loaded `me` can pass role/language (and, since Phase 6.1/6.2,
// activeBusinessId/businesses) to avoid a second /api/me round-trip; otherwise it fetches them
// here so a page only has to supply a title.
export default async function PageHeader({
  title,
  role,
  language,
  activeBusinessId,
  businesses,
}: {
  title: string;
  role?: Role;
  language?: Language | null;
  activeBusinessId?: number;
  businesses?: MeBusinessOption[];
}) {
  let r = role;
  let l = language;
  let abid = activeBusinessId;
  let biz = businesses;
  let platformAdmin: boolean | undefined;
  if (r === undefined) {
    const me = await serverApi.getMe();
    r = me.role;
    l = me.preferredLanguage;
    abid = me.activeBusinessId;
    biz = me.businesses;
    platformAdmin = me.platformAdmin;
  } else if (biz && biz.length > 1) {
    // Only needed to tell a platform admin from a manager of two studios (both have >1 option).
    platformAdmin = (await serverApi.getMe()).platformAdmin;
  }
  // KB requests stay OWNER-only; the SMS unread badge is now also relevant to MANAGER (see
  // openspec/changes/lead-followup-and-manager-inbox — MANAGER gets read/reply access to the
  // same activity log via /admin/messages).
  const [kbRequestOpenCount, smsUnreadCount] = await Promise.all([
    r === 'OWNER' ? serverApi.getKbRequestOpenCount() : Promise.resolve(0),
    r === 'OWNER' || r === 'MANAGER' ? serverApi.getSmsUnreadCount() : Promise.resolve(0),
  ]);

  return (
    <div className="mb-6 flex items-center gap-3">
      {/* AdminMenu is fixed to the viewport corner, not this row — the right padding here just
          keeps a long title from running underneath it on narrow screens. */}
      <h1 className="pr-24 text-xl font-semibold sm:text-2xl">{title}</h1>
      <AdminMenu
        role={r}
        language={l ?? null}
        kbRequestOpenCount={kbRequestOpenCount}
        smsUnreadCount={smsUnreadCount}
        activeBusinessId={abid}
        businesses={biz}
        platformAdmin={platformAdmin}
      />
    </div>
  );
}
