import { serverApi } from '../lib/serverApi';
import AdminMenu from './AdminMenu';
import DocTitle from './DocTitle';
import type { Language, MeBusinessOption, Role } from '../lib/types';

// The shared page header: a consistent title row with the navigation menu, on every authenticated
// page. Pages that already loaded `me` can pass role/language (and, since Phase 6.1/6.2,
// activeBusinessId/businesses) to avoid a second /api/me round-trip; otherwise it fetches them
// here so a page only has to supply a title.
function hasLeadsGrant(perms?: string[]) {
  return !!perms && (perms.includes('LEADS_ALL') || perms.includes('LEADS_ACADEMY'));
}

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
  let smsInbox = r === 'OWNER' || r === 'MANAGER';
  let leadsAccess = r === 'OWNER' || r === 'MANAGER';
  if (r === undefined) {
    const me = await serverApi.getMe();
    r = me.role;
    l = me.preferredLanguage;
    abid = me.activeBusinessId;
    biz = me.businesses;
    platformAdmin = me.platformAdmin;
    smsInbox = r === 'OWNER' || r === 'MANAGER' || !!me.permissions?.includes('SMS_INBOX');
    leadsAccess = r === 'OWNER' || r === 'MANAGER' || hasLeadsGrant(me.permissions);
  } else if (biz && biz.length > 1) {
    // Only needed to tell a platform admin from a manager of two studios (both have >1 option).
    platformAdmin = (await serverApi.getMe()).platformAdmin;
  }
  if (r === 'PROVIDER' && role !== undefined) {
    // A provider can be granted SMS_INBOX by the owner (Staff > Permissions).
    const perms = (await serverApi.getMe()).permissions;
    smsInbox = !!perms?.includes('SMS_INBOX');
    leadsAccess = hasLeadsGrant(perms);
  }
  // KB requests stay OWNER-only; the SMS unread badge is now also relevant to MANAGER (see
  // openspec/changes/lead-followup-and-manager-inbox — MANAGER gets read/reply access to the
  // same activity log via /admin/messages).
  const [kbRequestOpenCount, smsUnreadCount, leadsActionCount] = await Promise.all([
    r === 'OWNER' ? serverApi.getKbRequestOpenCount() : Promise.resolve(0),
    smsInbox ? serverApi.getSmsUnreadCount() : Promise.resolve(0),
    leadsAccess ? serverApi.getLeadActionCount() : Promise.resolve(0),
  ]);

  let businessName = biz?.find((b) => b.id === abid)?.name;
  if (!businessName) businessName = (await serverApi.getMe()).businesses.find((b) => b.id === abid)?.name;

  return (
    <div className="mb-6 flex items-center gap-3">
      <DocTitle title={businessName ? `${title} - ${businessName}` : title} />
      {/* AdminMenu is fixed to the viewport corner, not this row — the right padding here just
          keeps a long title from running underneath it on narrow screens. */}
      <h1 className="min-w-0 pr-44 text-xl sm:pr-32 font-semibold sm:text-2xl">
        {title}
        {businessName && (
          <span className="font-normal text-gray-500 dark:text-gray-400"> - <span className="break-words">{businessName}</span></span>
        )}
      </h1>
      <AdminMenu
        role={r}
        language={l ?? null}
        kbRequestOpenCount={kbRequestOpenCount}
        smsUnreadCount={smsUnreadCount}
        activeBusinessId={abid}
        businesses={biz}
        platformAdmin={platformAdmin}
        smsInbox={smsInbox}
        leadsAccess={leadsAccess}
        leadsActionCount={leadsActionCount}
      />
    </div>
  );
}
