import { forwardToBackend } from '../../../../../lib/proxyBackend';

export const GET = (req: Request) => {
  const customerId = new URL(req.url).searchParams.get('customerId');
  const qs = customerId ? `?customerId=${encodeURIComponent(customerId)}` : '';
  return forwardToBackend(`/api/owner/marketing/ads-report/customer-history${qs}`, 'GET');
};
