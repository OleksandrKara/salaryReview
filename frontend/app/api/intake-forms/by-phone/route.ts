import { forwardToBackend } from '../../../lib/proxyBackend';

export const GET = (req: Request) => {
  const phone = new URL(req.url).searchParams.get('phone') ?? '';
  return forwardToBackend(`/api/intake-forms/by-phone?phone=${encodeURIComponent(phone)}`, 'GET');
};
