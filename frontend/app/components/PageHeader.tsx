import { serverApi } from '../lib/serverApi';
import AdminMenu from './AdminMenu';
import type { Language, Role } from '../lib/types';

// The shared page header: a consistent title row with the navigation menu, on every authenticated
// page. Pages that already loaded `me` can pass role/language to avoid a second /api/me round-trip;
// otherwise it fetches them here so a page only has to supply a title.
export default async function PageHeader({
  title,
  role,
  language,
}: {
  title: string;
  role?: Role;
  language?: Language | null;
}) {
  let r = role;
  let l = language;
  if (r === undefined) {
    const me = await serverApi.getMe();
    r = me.role;
    l = me.preferredLanguage;
  }
  const [kbRequestOpenCount, smsUnreadCount] = r === 'OWNER'
    ? await Promise.all([serverApi.getKbRequestOpenCount(), serverApi.getSmsUnreadCount()])
    : [0, 0];

  return (
    <div className="mb-6 flex items-center gap-3">
      {/* AdminMenu is fixed to the viewport corner, not this row — the right padding here just
          keeps a long title from running underneath it on narrow screens. */}
      <h1 className="pr-24 text-xl font-semibold sm:text-2xl">{title}</h1>
      <AdminMenu role={r} language={l ?? null} kbRequestOpenCount={kbRequestOpenCount} smsUnreadCount={smsUnreadCount} />
    </div>
  );
}
