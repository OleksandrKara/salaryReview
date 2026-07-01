import { forwardToBackend } from '../../lib/proxyBackend';

export const GET = (req: Request) => {
  const qs = new URL(req.url).searchParams.toString();
  return forwardToBackend(`/api/cancellations${qs ? `?${qs}` : ''}`, 'GET');
};
