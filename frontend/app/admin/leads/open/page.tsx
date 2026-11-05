import { redirect } from 'next/navigation';
import { serverApi } from '../../../lib/serverApi';

// Telegram "Open lead" link: resolves the phone to the lead and forwards to its card.
export default async function OpenLeadPage({ searchParams }: { searchParams: Promise<{ phone?: string }> }) {
  const { phone } = await searchParams;
  const found = phone ? await serverApi.lookupLead(phone) : {};
  redirect(found.id ? `/admin/leads/${found.id}` : '/admin/leads');
}
