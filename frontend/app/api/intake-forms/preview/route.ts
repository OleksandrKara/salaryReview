import { forwardToBackend } from '../../../lib/proxyBackend';

export const GET = (req: Request) => {
  const hours = new URL(req.url).searchParams.get('hours') ?? '48';
  return forwardToBackend(`/api/intake-forms/preview?hours=${encodeURIComponent(hours)}`, 'GET');
};
