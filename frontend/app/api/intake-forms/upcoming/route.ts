import { forwardToBackend } from '../../../lib/proxyBackend';

export const GET = (req: Request) => {
  const days = new URL(req.url).searchParams.get('days') ?? '7';
  return forwardToBackend(`/api/intake-forms/upcoming?days=${encodeURIComponent(days)}`, 'GET');
};
